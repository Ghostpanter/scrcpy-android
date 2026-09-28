package com.ghostpanter.scrcpy;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

// Owns the tablet split-pane tools UI (file browser / app manager / device
// debug / terminal). Bound from Mirror when layout-sw600dp or layout-w600dp
// provides R.id.tools_pane (auto-detect + resource qualifiers).
public final class TabletTools {

    static final int RQ_PUSH_FILE = 7101;
    static final int RQ_PULL_FILE = 7102;
    static final int RQ_INSTALL_APK = 7103;

    private static final String REMOTE_APK = "/data/local/tmp/scrcpy-install.apk";

    private final Activity activity;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tablet-tools");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean destroyed = new AtomicBoolean();

    private volatile Adb adb;
    private volatile AdbRemote remote;

    private View toolsPane;
    private View panelFiles, panelApps, panelDebug, panelTerminal;
    private TextView navFiles, navApps, navDebug, navTerminal;

    // Files
    private EditText filesPath;
    private TextView filesStatus;
    private ListView filesList;
    private final List<AdbRemote.FileEntry> fileEntries = new ArrayList<>();
    private FileAdapter fileAdapter;
    private String currentDir = "/sdcard";
    private String selectedRemoteFile;

    // Apps
    private EditText appsSearch;
    private CheckBox appsUser, appsSystem;
    private TextView appsStatus;
    private ListView appsList;
    private final List<AdbRemote.AppInfo> allApps = new ArrayList<>();
    private final List<AdbRemote.AppInfo> shownApps = new ArrayList<>();
    private AppAdapter appAdapter;

    // Debug
    private TextView debugPhysicalSize, debugCurrentSize;
    private TextView debugPhysicalDpi, debugCurrentDpi, debugStatus;
    private EditText debugWidth, debugHeight, debugDpi;
    private AdbRemote.DisplaySize lastSize;
    private AdbRemote.DisplayDensity lastDpi;

    // Terminal
    private EditText terminalInput;
    private TextView terminalOutput, terminalStatus;
    private ScrollView terminalScroll;
    private final StringBuilder terminalLog = new StringBuilder();
    private static final int TERMINAL_MAX_CHARS = 200_000;

    public TabletTools(Activity activity) {
        this.activity = activity;
    }

    public boolean bind() {
        toolsPane = activity.findViewById(R.id.tools_pane);
        if (toolsPane == null) return false;

        panelFiles = activity.findViewById(R.id.panel_files);
        panelApps = activity.findViewById(R.id.panel_apps);
        panelDebug = activity.findViewById(R.id.panel_debug);
        panelTerminal = activity.findViewById(R.id.panel_terminal);
        navFiles = activity.findViewById(R.id.nav_files);
        navApps = activity.findViewById(R.id.nav_apps);
        navDebug = activity.findViewById(R.id.nav_debug);
        navTerminal = activity.findViewById(R.id.nav_terminal);

        navFiles.setOnClickListener(v -> showPanel(0));
        navApps.setOnClickListener(v -> showPanel(1));
        navDebug.setOnClickListener(v -> showPanel(2));
        navTerminal.setOnClickListener(v -> showPanel(3));

        bindFiles();
        bindApps();
        bindDebug();
        bindTerminal();
        showPanel(0);
        return true;
    }

    public void setAdb(Adb adb) {
        this.adb = adb;
        this.remote = adb == null ? null : new AdbRemote(adb);
        if (remote != null) {
            refreshFiles();
            refreshApps();
            refreshDebug();
        }
    }

    public void destroy() {
        destroyed.set(true);
        io.shutdownNow();
    }

    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return requestCode == RQ_PUSH_FILE || requestCode == RQ_PULL_FILE
                    || requestCode == RQ_INSTALL_APK;
        }
        Uri uri = data.getData();
        if (requestCode == RQ_PUSH_FILE) {
            pushUri(uri);
            return true;
        }
        if (requestCode == RQ_PULL_FILE) {
            pullToUri(uri);
            return true;
        }
        if (requestCode == RQ_INSTALL_APK) {
            installUri(uri);
            return true;
        }
        return false;
    }

    // ---- navigation ----

    private void showPanel(int index) {
        panelFiles.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        panelApps.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        panelDebug.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        panelTerminal.setVisibility(index == 3 ? View.VISIBLE : View.GONE);
        navFiles.setSelected(index == 0);
        navApps.setSelected(index == 1);
        navDebug.setSelected(index == 2);
        navTerminal.setSelected(index == 3);
        if (index == 1 && allApps.isEmpty() && remote != null) refreshApps();
        if (index == 2 && lastSize == null && remote != null) refreshDebug();
    }

    // ---- files ----

    private void bindFiles() {
        filesPath = activity.findViewById(R.id.files_path);
        filesStatus = activity.findViewById(R.id.files_status);
        filesList = activity.findViewById(R.id.files_list);
        fileAdapter = new FileAdapter();
        filesList.setAdapter(fileAdapter);

        filesPath.setText(currentDir);
        filesPath.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN)) {
                navigateTo(filesPath.getText().toString());
                return true;
            }
            return false;
        });

        activity.findViewById(R.id.files_up).setOnClickListener(v ->
                navigateTo(AdbRemote.parentPath(currentDir)));
        activity.findViewById(R.id.files_refresh).setOnClickListener(v -> refreshFiles());
        activity.findViewById(R.id.files_quick_root).setOnClickListener(v -> navigateTo("/"));
        activity.findViewById(R.id.files_quick_sdcard).setOnClickListener(v -> navigateTo("/sdcard"));
        activity.findViewById(R.id.files_quick_download).setOnClickListener(v ->
                navigateTo("/sdcard/Download"));
        activity.findViewById(R.id.files_quick_pictures).setOnClickListener(v ->
                navigateTo("/sdcard/Pictures"));
        activity.findViewById(R.id.files_quick_documents).setOnClickListener(v ->
                navigateTo("/sdcard/Documents"));

        activity.findViewById(R.id.files_push).setOnClickListener(v -> {
            if (!ensureRemote()) return;
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("*/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            activity.startActivityForResult(Intent.createChooser(i, activity.getString(R.string.files_push)),
                    RQ_PUSH_FILE);
        });
        activity.findViewById(R.id.files_pull).setOnClickListener(v -> {
            if (!ensureRemote()) return;
            if (selectedRemoteFile == null) {
                toast(R.string.files_select_first);
                return;
            }
            String name = selectedRemoteFile.substring(selectedRemoteFile.lastIndexOf('/') + 1);
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.setType("*/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.putExtra(Intent.EXTRA_TITLE, name);
            activity.startActivityForResult(i, RQ_PULL_FILE);
        });

        filesList.setOnItemClickListener((parent, view, position, id) -> {
            AdbRemote.FileEntry e = fileEntries.get(position);
            if (e.directory) {
                navigateTo(AdbRemote.joinPath(currentDir, e.name));
            } else {
                selectedRemoteFile = AdbRemote.joinPath(currentDir, e.name);
                fileAdapter.notifyDataSetChanged();
                filesStatus.setText(selectedRemoteFile);
            }
        });
    }

    private void navigateTo(String path) {
        currentDir = AdbRemote.normalizePath(path);
        selectedRemoteFile = null;
        filesPath.setText(currentDir);
        refreshFiles();
    }

    private void refreshFiles() {
        AdbRemote r = remote;
        if (r == null) {
            filesStatus.setText(R.string.files_need_connection);
            return;
        }
        filesStatus.setText(R.string.files_status_loading);
        final String dir = currentDir;
        io.execute(() -> {
            try {
                List<AdbRemote.FileEntry> list = r.listDir(dir);
                ui.post(() -> {
                    if (destroyed.get()) return;
                    fileEntries.clear();
                    fileEntries.addAll(list);
                    fileAdapter.notifyDataSetChanged();
                    filesStatus.setText(activity.getString(R.string.files_status_count, list.size()));
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (destroyed.get()) return;
                    filesStatus.setText(activity.getString(R.string.files_error, msg(e)));
                });
            }
        });
    }

    private void pushUri(Uri uri) {
        AdbRemote r = remote;
        if (r == null) { toast(R.string.files_need_connection); return; }
        String rawName = displayName(uri);
        if (rawName == null || rawName.isEmpty()) rawName = "upload.bin";
        final String name = rawName;
        final String remotePath = AdbRemote.joinPath(currentDir, name);
        filesStatus.setText(R.string.files_status_loading);
        io.execute(() -> {
            try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("cannot open " + uri);
                long n = r.push(in, remotePath);
                ui.post(() -> {
                    if (destroyed.get()) return;
                    filesStatus.setText(activity.getString(R.string.files_push_ok, name, (int) Math.min(n, Integer.MAX_VALUE)));
                    refreshFiles();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (destroyed.get()) return;
                    filesStatus.setText(activity.getString(R.string.files_error, msg(e)));
                });
            }
        });
    }

    private void pullToUri(Uri uri) {
        AdbRemote r = remote;
        if (r == null || selectedRemoteFile == null) return;
        final String remotePath = selectedRemoteFile;
        filesStatus.setText(R.string.files_status_loading);
        io.execute(() -> {
            try (OutputStream out = activity.getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IOException("cannot open " + uri);
                long n = r.pull(remotePath, out);
                ui.post(() -> {
                    if (destroyed.get()) return;
                    filesStatus.setText(activity.getString(R.string.files_pull_ok, remotePath,
                            (int) Math.min(n, Integer.MAX_VALUE)));
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (destroyed.get()) return;
                    filesStatus.setText(activity.getString(R.string.files_error, msg(e)));
                });
            }
        });
    }

    // ---- apps ----

    private void bindApps() {
        appsSearch = activity.findViewById(R.id.apps_search);
        appsUser = activity.findViewById(R.id.apps_user);
        appsSystem = activity.findViewById(R.id.apps_system);
        appsStatus = activity.findViewById(R.id.apps_status);
        appsList = activity.findViewById(R.id.apps_list);
        appAdapter = new AppAdapter();
        appsList.setAdapter(appAdapter);

        appsSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { filterApps(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        appsUser.setOnCheckedChangeListener((b, c) -> filterApps());
        appsSystem.setOnCheckedChangeListener((b, c) -> {
            if (c && allApps.stream().noneMatch(a -> a.system) && remote != null) {
                refreshApps();
            } else {
                filterApps();
            }
        });
        activity.findViewById(R.id.apps_refresh).setOnClickListener(v -> refreshApps());
        activity.findViewById(R.id.apps_install).setOnClickListener(v -> {
            if (!ensureRemote()) return;
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("application/vnd.android.package-archive");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            // Also allow */* for pickers that don't expose APK MIME
            Intent any = new Intent(Intent.ACTION_GET_CONTENT);
            any.setType("*/*");
            any.addCategory(Intent.CATEGORY_OPENABLE);
            Intent chooser = Intent.createChooser(i, activity.getString(R.string.apps_pick_apk));
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{any});
            activity.startActivityForResult(chooser, RQ_INSTALL_APK);
        });
    }

    private void refreshApps() {
        AdbRemote r = remote;
        if (r == null) {
            appsStatus.setText(R.string.files_need_connection);
            return;
        }
        appsStatus.setText(R.string.files_status_loading);
        final boolean withSystem = appsSystem.isChecked();
        io.execute(() -> {
            try {
                List<AdbRemote.AppInfo> list = r.listPackages(withSystem);
                ui.post(() -> {
                    if (destroyed.get()) return;
                    allApps.clear();
                    allApps.addAll(list);
                    filterApps();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (destroyed.get()) return;
                    appsStatus.setText(activity.getString(R.string.apps_error, msg(e)));
                });
            }
        });
    }

    private void filterApps() {
        String q = appsSearch.getText() == null ? ""
                : appsSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
        boolean user = appsUser.isChecked();
        boolean system = appsSystem.isChecked();
        shownApps.clear();
        for (AdbRemote.AppInfo a : allApps) {
            if (a.system && !system) continue;
            if (!a.system && !user) continue;
            if (!q.isEmpty() && !a.packageName.toLowerCase(Locale.ROOT).contains(q)) continue;
            shownApps.add(a);
        }
        appAdapter.notifyDataSetChanged();
        appsStatus.setText(activity.getString(R.string.apps_status_count, shownApps.size()));
    }

    private void installUri(Uri uri) {
        AdbRemote r = remote;
        if (r == null) { toast(R.string.files_need_connection); return; }
        appsStatus.setText(R.string.apps_installing);
        io.execute(() -> {
            try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("cannot open " + uri);
                r.push(in, REMOTE_APK);
                String result = r.installApk(REMOTE_APK);
                try { r.shell("rm -f " + AdbRemote.singleQuote(REMOTE_APK), true); }
                catch (Exception ignored) {}
                ui.post(() -> {
                    if (destroyed.get()) return;
                    appsStatus.setText(activity.getString(R.string.apps_install_ok, result));
                    refreshApps();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (destroyed.get()) return;
                    appsStatus.setText(activity.getString(R.string.apps_error, msg(e)));
                });
            }
        });
    }

    // ---- debug ----

    private void bindDebug() {
        debugPhysicalSize = activity.findViewById(R.id.debug_physical_size);
        debugCurrentSize = activity.findViewById(R.id.debug_current_size);
        debugPhysicalDpi = activity.findViewById(R.id.debug_physical_dpi);
        debugCurrentDpi = activity.findViewById(R.id.debug_current_dpi);
        debugWidth = activity.findViewById(R.id.debug_width);
        debugHeight = activity.findViewById(R.id.debug_height);
        debugDpi = activity.findViewById(R.id.debug_dpi);
        debugStatus = activity.findViewById(R.id.debug_status);

        activity.findViewById(R.id.debug_refresh).setOnClickListener(v -> refreshDebug());
        activity.findViewById(R.id.debug_apply_size).setOnClickListener(v -> applySize());
        activity.findViewById(R.id.debug_reset_size).setOnClickListener(v -> resetSize());
        activity.findViewById(R.id.debug_apply_dpi).setOnClickListener(v -> applyDpi());
        activity.findViewById(R.id.debug_reset_dpi).setOnClickListener(v -> resetDpi());
    }

    private void refreshDebug() {
        AdbRemote r = remote;
        if (r == null) {
            debugStatus.setText(R.string.files_need_connection);
            return;
        }
        debugStatus.setText(R.string.files_status_loading);
        io.execute(() -> {
            try {
                AdbRemote.DisplaySize size = r.readSize();
                AdbRemote.DisplayDensity dens = r.readDensity();
                ui.post(() -> {
                    if (destroyed.get()) return;
                    lastSize = size;
                    lastDpi = dens;
                    debugPhysicalSize.setText(activity.getString(
                            R.string.debug_physical_size, size.physicalLabel()));
                    debugCurrentSize.setText(activity.getString(
                            R.string.debug_current_size, size.currentLabel()));
                    debugPhysicalDpi.setText(activity.getString(
                            R.string.debug_physical_dpi, dens.physical));
                    debugCurrentDpi.setText(activity.getString(
                            R.string.debug_current_dpi, dens.current));
                    // Prefer physical values in the editors (native resolution first).
                    debugWidth.setText(String.valueOf(size.physicalW));
                    debugHeight.setText(String.valueOf(size.physicalH));
                    debugDpi.setText(String.valueOf(dens.physical));
                    debugStatus.setText(R.string.files_status_ready);
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (destroyed.get()) return;
                    debugStatus.setText(activity.getString(R.string.debug_error, msg(e)));
                });
            }
        });
    }

    private void applySize() {
        AdbRemote r = remote;
        if (r == null) { toast(R.string.files_need_connection); return; }
        int w, h;
        try {
            w = Integer.parseInt(debugWidth.getText().toString().trim());
            h = Integer.parseInt(debugHeight.getText().toString().trim());
        } catch (Exception e) {
            toast(R.string.debug_bad_size);
            return;
        }
        io.execute(() -> {
            try {
                r.applySize(w, h);
                ui.post(() -> {
                    if (destroyed.get()) return;
                    debugStatus.setText(R.string.debug_applied);
                    refreshDebug();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (destroyed.get()) return;
                    debugStatus.setText(activity.getString(R.string.debug_error, msg(e)));
                });
            }
        });
    }

    private void resetSize() {
        AdbRemote r = remote;
        if (r == null) return;
        io.execute(() -> {
            try {
                r.resetSize();
                ui.post(() -> {
                    if (destroyed.get()) return;
                    debugStatus.setText(R.string.debug_reset_ok);
                    refreshDebug();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (destroyed.get()) return;
                    debugStatus.setText(activity.getString(R.string.debug_error, msg(e)));
                });
            }
        });
    }

    private void applyDpi() {
        AdbRemote r = remote;
        if (r == null) return;
        int dpi;
        try {
            dpi = Integer.parseInt(debugDpi.getText().toString().trim());
        } catch (Exception e) {
            toast(R.string.debug_bad_dpi);
            return;
        }
        if (dpi < 72 || dpi > 640) {
            toast(R.string.debug_bad_dpi);
            return;
        }
        io.execute(() -> {
            try {
                r.applyDensity(dpi);
                ui.post(() -> {
                    if (destroyed.get()) return;
                    debugStatus.setText(R.string.debug_applied);
                    refreshDebug();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (destroyed.get()) return;
                    debugStatus.setText(activity.getString(R.string.debug_error, msg(e)));
                });
            }
        });
    }

    private void resetDpi() {
        AdbRemote r = remote;
        if (r == null) return;
        io.execute(() -> {
            try {
                r.resetDensity();
                ui.post(() -> {
                    if (destroyed.get()) return;
                    debugStatus.setText(R.string.debug_reset_ok);
                    refreshDebug();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (destroyed.get()) return;
                    debugStatus.setText(activity.getString(R.string.debug_error, msg(e)));
                });
            }
        });
    }

    // ---- helpers ----

    // ---- terminal ----

    private void bindTerminal() {
        terminalInput = activity.findViewById(R.id.terminal_input);
        terminalOutput = activity.findViewById(R.id.terminal_output);
        terminalStatus = activity.findViewById(R.id.terminal_status);
        terminalScroll = activity.findViewById(R.id.terminal_scroll);

        activity.findViewById(R.id.terminal_run).setOnClickListener(v -> runTerminal());
        activity.findViewById(R.id.terminal_clear).setOnClickListener(v -> clearTerminal());
        terminalInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN)) {
                runTerminal();
                return true;
            }
            return false;
        });
    }

    private void clearTerminal() {
        terminalLog.setLength(0);
        terminalOutput.setText("");
        terminalStatus.setText(R.string.terminal_status_ready);
    }

    private void runTerminal() {
        if (!ensureRemote()) return;
        String raw = terminalInput.getText() == null ? "" : terminalInput.getText().toString();
        String cmd = sanitizeTerminalCommand(raw);
        if (cmd.isEmpty()) {
            toast(R.string.terminal_empty);
            return;
        }
        AdbRemote r = remote;
        terminalStatus.setText(R.string.terminal_status_running);
        appendTerminal("$ " + cmd + "\n");
        io.execute(() -> {
            try {
                String out = r.shell(cmd, false);
                if (out == null) out = "";
                if (!out.isEmpty() && !out.endsWith("\n")) out = out + "\n";
                String finalOut = out;
                ui.post(() -> {
                    if (destroyed.get()) return;
                    appendTerminal(finalOut);
                    terminalStatus.setText(R.string.terminal_status_ready);
                    // Keep the command for quick re-run / edit; select all for overwrite.
                    terminalInput.selectAll();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (destroyed.get()) return;
                    appendTerminal(activity.getString(R.string.terminal_error, msg(e)) + "\n");
                    terminalStatus.setText(activity.getString(R.string.terminal_error, msg(e)));
                });
            }
        });
    }

    /** Strip habitual "adb " / "adb shell " prefixes; trim. */
    private static String sanitizeTerminalCommand(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.regionMatches(true, 0, "adb shell ", 0, 10)) {
            s = s.substring(10).trim();
        } else if (s.regionMatches(true, 0, "adb ", 0, 4)) {
            s = s.substring(4).trim();
            if (s.regionMatches(true, 0, "shell ", 0, 6)) {
                s = s.substring(6).trim();
            } else if (s.equalsIgnoreCase("shell")) {
                s = "";
            }
        }
        return s;
    }

    private void appendTerminal(String chunk) {
        if (chunk == null || chunk.isEmpty()) return;
        terminalLog.append(chunk);
        if (terminalLog.length() > TERMINAL_MAX_CHARS) {
            terminalLog.delete(0, terminalLog.length() - TERMINAL_MAX_CHARS);
        }
        terminalOutput.setText(terminalLog.toString());
        if (terminalScroll != null) {
            terminalScroll.post(() -> terminalScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private boolean ensureRemote() {
        if (remote == null) {
            toast(R.string.files_need_connection);
            return false;
        }
        return true;
    }

    private void toast(int res) {
        Toast.makeText(activity, res, Toast.LENGTH_SHORT).show();
    }

    private static String msg(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }

    private String displayName(Uri uri) {
        String name = null;
        try (Cursor c = activity.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) {}
        if (name == null) {
            String path = uri.getLastPathSegment();
            if (path != null) {
                int slash = path.lastIndexOf('/');
                name = slash >= 0 ? path.substring(slash + 1) : path;
            }
        }
        if (name != null) {
            // Strip path separators that could escape the destination dir.
            name = name.replace('/', '_').replace('\\', '_');
        }
        return name;
    }

    private final class FileAdapter extends BaseAdapter {
        @Override public int getCount() { return fileEntries.size(); }
        @Override public AdbRemote.FileEntry getItem(int position) { return fileEntries.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(activity).inflate(R.layout.file_row, parent, false);
            }
            AdbRemote.FileEntry e = getItem(position);
            TextView icon = v.findViewById(R.id.file_icon);
            TextView name = v.findViewById(R.id.file_name);
            icon.setText(e.directory ? "📁" : "📄");
            name.setText(e.name);
            String full = AdbRemote.joinPath(currentDir, e.name);
            boolean sel = !e.directory && full.equals(selectedRemoteFile);
            v.setBackgroundColor(sel
                    ? activity.getColor(R.color.sidebar_selected)
                    : 0x00000000);
            return v;
        }
    }

    private final class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return shownApps.size(); }
        @Override public AdbRemote.AppInfo getItem(int position) { return shownApps.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(activity).inflate(R.layout.app_row, parent, false);
            }
            AdbRemote.AppInfo a = getItem(position);
            TextView name = v.findViewById(R.id.app_name);
            TextView pkg = v.findViewById(R.id.app_package);
            String label = a.packageName;
            int dot = label.lastIndexOf('.');
            name.setText(dot >= 0 ? label.substring(dot + 1) : label);
            pkg.setText(a.packageName + (a.system ? "  [system]" : ""));
            return v;
        }
    }
}
