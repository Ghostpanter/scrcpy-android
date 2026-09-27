package com.ghostpanter.scrcpy;

import android.content.Context;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.net.ConnectException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import io.github.muntashirakon.adb.AdbStream;

// Bring the scrcpy server up on the target:
//   1. Push assets/scrcpy-server.jar to /data/local/tmp/scrcpy-server.jar
//      via the adb sync protocol.
//   2. Spawn `app_process / com.genymobile.scrcpy.Server <ver> key=value...`
//      via an adb shell stream and hold it open. cleanup=true means the
//      server exits when we close that stream.
//   3. Open three localabstract:scrcpy_<scid> streams in order
//      (video, audio, control). With tunnel_forward=true the server is
//      the listener, so we just dial.
//   4. Drain 1 probe byte + 64-byte device name from the FIRST stream
//      that the server accepts (video, if requested).
//
// Returns a Streams record with everything Session needs: typed
// InputStream/OutputStream wrappers (opened ONCE per AdbStream so the
// reader's offset is unambiguous), plus the underlying AdbStream
// handles so close() can release them.
public final class Server {

    private static final String REMOTE_PATH    = "/data/local/tmp/scrcpy-server.jar";
    // Written before su elevate so Magisk grants a short, stable cmdline
    // (avoids quoting breakage on long CLASSPATH=... app_process lines).
    private static final String REMOTE_START   = "/data/local/tmp/scrcpy-gp-start.sh";
    private static final String REMOTE_PROBE   = "/data/local/tmp/scrcpy-gp-probe.sh";
    private static final int    SCRIPT_MODE    = 0100755;         // regular file, 0755
    private static final String ASSET_JAR      = "scrcpy-server.jar";
    private static final String ASSET_VERSION  = "scrcpy-server.version";
    private static final int    FILE_MODE      = 0100644;         // regular file, 0644
    private static final long   LISTENER_DEADLINE_MS = 20_000;
    private static final long   LISTENER_RETRY_MS = 100;
    public static final class Streams {
        public final AdbStream    videoAds, audioAds, controlAds;
        public final InputStream  videoIn, audioIn, controlIn;
        public final OutputStream controlOut;

        Streams(AdbStream va, AdbStream aa, AdbStream ca,
                InputStream vi, InputStream ai, InputStream ci, OutputStream co) {
            this.videoAds = va; this.audioAds = aa; this.controlAds = ca;
            this.videoIn = vi;  this.audioIn = ai;  this.controlIn = ci;
            this.controlOut = co;
        }
    }

    private final Context ctx;
    private final Adb     adb;
    private AdbStream     shell;
    private Thread        shellPump;
    private Streams       streams;
    private volatile boolean serverEof;

    public Server(Context ctx, Adb adb) {
        this.ctx = ctx;
        this.adb = adb;
    }

    public enum ElevateStatus {
        /** Settings toggle off — shell spawn only. */
        DISABLED,
        /** scrcpy-server launched as uid 0. */
        ROOT,
        /** No working su on the target (or binary missing). */
        UNAVAILABLE,
        /** Magisk/KernelSU prompt timed out or denied. */
        DENIED,
        /** su worked in probe but elevated spawn failed; fell back to shell. */
        FALLBACK
    }

    private volatile ElevateStatus elevateStatus = ElevateStatus.DISABLED;
    /** Which elevate wrapper succeeded in probe: "su0" or "su_c". */
    private String suForm;

    public ElevateStatus elevateStatus() {
        return elevateStatus;
    }

    public Streams bringUp() throws Exception {
        serverEof = false;
        elevateStatus = ElevateStatus.DISABLED;
        suForm = null;
        String version = readVersion();
        long pushed = push();
        Log.i("push %s bytes=%d", REMOTE_PATH, pushed);

        // Prefer root-elevated spawn when the user wants secure-layer capture
        // and the target has a working su (Magisk / KernelSU). Official
        // scrcpy-server.jar is unchanged; we only wrap the launch as uid 0
        // (scrcpy-root style) so createDisplay(secure) can succeed on
        // Android 12+. Any elevate failure falls back to a normal shell start.
        //
        // Magisk note: the first su request from ADB shell often shows a
        // Superuser dialog (or auto-grants + toast if Magisk already allowed
        // "shell"/ADB). The old 2.5s probe closed the AdbStream before the
        // user could tap Allow, cancelling the request so no lasting dialog
        // appeared and we silently fell back to shell — FLAG_SECURE stayed
        // black. Wait long enough for an interactive grant.
        boolean wantElevate = Settings.rootCaptureSecure(ctx);
        if (wantElevate) {
            SuProbeResult probe = probeSu();
            if (probe == SuProbeResult.OK) {
                String scid = newScid();
                String plain = buildCmdline(version, scid);
                String elevated = elevateCmd(plain);
                Log.i("spawn server (root) ver=%s scid=%s form=%s", version, scid, suForm);
                Log.i("cmdline: %s", elevated);
                try {
                    Streams streams = spawnAndConnect(elevated, scid);
                    elevateStatus = ElevateStatus.ROOT;
                    return streams;
                } catch (Exception e) {
                    Log.w("server: root elevate failed, falling back to shell: %s", e);
                    closeShell();
                    serverEof = false;
                    elevateStatus = ElevateStatus.FALLBACK;
                }
            } else if (probe == SuProbeResult.DENIED) {
                elevateStatus = ElevateStatus.DENIED;
                Log.i("server: su denied/timeout; using shell (secure layers will stay black)");
            } else {
                elevateStatus = ElevateStatus.UNAVAILABLE;
                Log.i("server: root capture on but su not available; using shell");
            }
        }

        String scid = newScid();
        String cmd = buildCmdline(version, scid);
        Log.i("spawn server ver=%s scid=%s", version, scid);
        Log.i("cmdline: %s", cmd);
        return spawnAndConnect(cmd, scid);
    }

    private Streams spawnAndConnect(String cmd, String scid) throws Exception {
        AdbStream va = null, aa = null, ca = null;
        boolean committed = false;
        try {
            shell = adb.openShell(cmd);
            AdbStream shellRef = shell;
            shellPump = new Thread(() -> pump(shellRef.openInputStream()), "server-stdout");
            shellPump.setDaemon(true);
            shellPump.start();

            // These accepts are ordered. If one times out, the whole ADB
            // connection is discarded by Session; retrying an individual
            // open could shift video/audio/control onto the wrong sockets.
            va = openAbstract(scid);
            aa = openAbstract(scid);
            ca = openAbstract(scid);

            InputStream  vi = va.openInputStream();
            InputStream  ai = aa.openInputStream();
            InputStream  ci = ca.openInputStream();
            OutputStream co = ca.openOutputStream();

            Log.i("device name=%s", readDeviceMeta(vi));
            streams = new Streams(va, aa, ca, vi, ai, ci, co);
            committed = true;
            return streams;
        } finally {
            if (!committed) {
                closeQuietly(va);
                closeQuietly(aa);
                closeQuietly(ca);
                closeShell();
            }
        }
    }

    // Magisk / KernelSU: `su 0` runs as uid 0; `su -c` is the portable form.
    // Prefer running a pushed script so Magisk sees a short request and we
    // avoid shell-quoting hazards on the long CLASSPATH/app_process line.
    private String elevateCmd(String plain) throws Exception {
        pushTextFile(REMOTE_START, "#!/system/bin/sh\n" + plain + "\n", SCRIPT_MODE);
        if (suForm != null && suForm.startsWith("su_c")) {
            return "su -c " + shellSingleQuote("sh " + REMOTE_START);
        }
        return "su 0 sh " + REMOTE_START;
    }

    private static String shellSingleQuote(String s) {
        // Wrap for sh -c; embed apostrophes as '\''.
        return "'" + s.replace("'", "'\''") + "'";
    }

    private enum SuProbeResult { OK, UNAVAILABLE, DENIED }

    // Wait long enough for Magisk/KernelSU to show the Superuser dialog on
    // the TARGET and for the user to tap Allow. Closing early cancels the
    // request (dialog never sticks / never appears). Magisk may also
    // auto-grant ADB "shell" with only a toast — that still returns OK.
    private static final long SU_PROMPT_MS = 60_000L;

    private SuProbeResult probeSu() {
        // Prefer Magisk-style `su 0`, then portable `su -c`.
        // Stop on DENIED/timeout — a second 60s wait would only annoy the user
        // after they already ignored/denied the Magisk dialog.
        // Push a tiny probe script first so Magisk's Superuser UI gets a
        // stable short path (some Magisk builds are flaky with long inline -c).
        try {
            pushTextFile(REMOTE_PROBE, "#!/system/bin/sh\nid -u\n", SCRIPT_MODE);
        } catch (Exception e) {
            Log.w("server: could not push su probe script: %s", e);
        }
        String[][] forms = {
                {"su0_script", "su 0 sh " + REMOTE_PROBE},
                {"su_c_script", "su -c " + shellSingleQuote("sh " + REMOTE_PROBE)},
                {"su0", "su 0 id -u"},
                {"su_c", "su -c " + shellSingleQuote("id -u")},
        };
        SuProbeResult worst = SuProbeResult.UNAVAILABLE;
        for (String[] form : forms) {
            SuProbeResult r = probeSuOnce(form[0], form[1]);
            if (r == SuProbeResult.OK) return SuProbeResult.OK;
            if (r == SuProbeResult.DENIED) return SuProbeResult.DENIED;
            worst = r;
        }
        return worst;
    }

    private SuProbeResult probeSuOnce(String formName, String cmd) {
        AdbStream s = null;
        Thread reader = null;
        final StringBuilder out = new StringBuilder();
        final long started = monotonicMs();
        try {
            Log.i("server: su probe begin form=%s (wait up to %d ms for Magisk grant on target)",
                    formName, SU_PROMPT_MS);
            s = adb.openShell(cmd);
            AdbStream ref = s;
            reader = new Thread(() -> {
                try (InputStream in = ref.openInputStream()) {
                    byte[] buf = new byte[128];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        for (int i = 0; i < n; i++) {
                            char c = (char) (buf[i] & 0xff);
                            if (c >= 0x20 && c <= 0x7e) out.append(c);
                            else if (c == '\n' || c == '\r') out.append(' ');
                        }
                        if (out.length() > 96) break;
                    }
                } catch (IOException ignored) {}
            }, "su-probe");
            reader.setDaemon(true);
            reader.start();
            reader.join(SU_PROMPT_MS);
            boolean timedOut = reader.isAlive();
            String textOut = out.toString().trim();
            boolean ok = looksLikeUid0(textOut);
            long elapsed = monotonicMs() - started;
            if (ok) {
                suForm = formName;
                Log.i("server: su probe OK form=%s elapsed=%d ms raw=%s",
                        formName, elapsed, textOut);
                return SuProbeResult.OK;
            }
            if (looksLikeSuDenied(textOut)) {
                Log.i("server: su probe DENIED form=%s elapsed=%d ms raw=%s",
                        formName, elapsed, textOut);
                return SuProbeResult.DENIED;
            }
            if (timedOut) {
                Log.i("server: su probe TIMEOUT form=%s (Magisk dialog not granted in time) raw=%s",
                        formName, textOut);
                return SuProbeResult.DENIED;
            }
            Log.i("server: su probe UNAVAILABLE form=%s elapsed=%d ms raw=%s",
                    formName, elapsed, textOut);
            return SuProbeResult.UNAVAILABLE;
        } catch (Exception e) {
            Log.i("server: su probe failed form=%s: %s", formName, e);
            return SuProbeResult.UNAVAILABLE;
        } finally {
            if (reader != null && reader.isAlive()) reader.interrupt();
            closeQuietly(s);
        }
    }

    private static boolean looksLikeUid0(String textOut) {
        if (textOut == null || textOut.isEmpty()) return false;
        if (textOut.contains("uid=0")) return true;
        int end = 0;
        while (end < textOut.length() && Character.isDigit(textOut.charAt(end))) end++;
        if (end > 0) {
            try { return Integer.parseInt(textOut.substring(0, end)) == 0; }
            catch (NumberFormatException ignored) {}
        }
        return false;
    }

    private static boolean looksLikeSuDenied(String textOut) {
        if (textOut == null || textOut.isEmpty()) return false;
        String lower = textOut.toLowerCase(Locale.ROOT);
        return lower.contains("permission denied")
                || lower.contains("not allowed")
                || lower.contains("access denied")
                || lower.contains("request rejected")
                || lower.contains("denied")
                || lower.startsWith("su:");
    }

    // Idempotent. Closes the three media/control streams first (lets
    // the wire drain), then the shell (which makes the scrcpy server
    // exit via cleanup=true), then gives the cleanup helper up to
    // CLOSE_GRACE_MS to restore device state - display power, screen
    // timeout, brightness, etc. - before we kill the adb connection
    // out from under it. Best-effort: cleanup is server-side and we
    // can't synchronously confirm it.
    public void close() {
        if (streams != null) {
            closeQuietly(streams.videoAds);
            closeQuietly(streams.audioAds);
            closeQuietly(streams.controlAds);
            streams = null;
        }
        closeShell();
    }

    private void closeShell() {
        Thread t = shellPump;
        AdbStream s = shell;
        shell = null;
        shellPump = null;
        closeQuietly(s);
        if (t != null) {
            try {
                t.join(CLOSE_GRACE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (t.isAlive()) t.interrupt();
        }
    }

    private static final long CLOSE_GRACE_MS = 500;

    private static void closeQuietly(AdbStream s) {
        if (s == null) return;
        try { s.close(); } catch (IOException ignored) {}
    }

    // ---- helpers ----

    private String readVersion() throws IOException {
        try (InputStream in = ctx.getAssets().open(ASSET_VERSION)) {
            byte[] buf = new byte[64];
            int n = 0;
            while (n < buf.length) {
                int r = in.read(buf, n, buf.length - n);
                if (r < 0) break;
                if (r == 0) throw new IOException("scrcpy-server.version read made no progress");
                n += r;
            }
            if (n == buf.length && in.read() >= 0) {
                throw new IOException("scrcpy-server.version is too long");
            }
            String v = Wire.decodeUtf8(buf, 0, n).trim();
            if (!v.matches("[0-9]+(\\.[0-9]+)*")) {
                throw new IOException("scrcpy-server.version is invalid");
            }
            return v;
        }
    }

    private static String newScid() {
        // 31-bit random, 8 lowercase hex chars - matches scrcpy upstream client.
        int v = new SecureRandom().nextInt() & 0x7fffffff;
        return String.format(Locale.ROOT, "%08x", v);
    }

    private String buildCmdline(String version, String scid) {
        String videoCodec = Settings.videoCodec(ctx);
        String audioCodec = Settings.audioCodec(ctx);
        int maxSize     = Settings.maxSize(ctx);
        int videoBitR   = Settings.videoBitRate(ctx);
        int maxFps      = Settings.maxFps(ctx);
        boolean lowLat  = Settings.lowLatency(ctx);
        List<String> args = new ArrayList<>();
        args.add("CLASSPATH=" + REMOTE_PATH);
        args.add("app_process");
        args.add("/");
        args.add("com.genymobile.scrcpy.Server");
        args.add(version);
        args.add("scid=" + scid);
        args.add("log_level=info");
        args.add("video=true");
        args.add("audio=true");
        args.add("control=true");
        args.add("video_codec=" + videoCodec);
        args.add("audio_codec=" + audioCodec);
        args.add("max_size=" + maxSize);
        args.add("video_bit_rate=" + videoBitR);
        args.add("max_fps=" + maxFps);
        // Prefer the lowest-latency encoder option when the
        // target MediaCodec supports it (scrcpy server option).
        if (lowLat) args.add("video_codec_options=i-frame-interval=1");
        // Off means off at the source: the server never sends the
        // target's clipboard, rather than us receiving and discarding it.
        args.add("clipboard_autosync=" + Settings.clipboardSync(ctx));
        args.add("tunnel_forward=true");
        args.add("cleanup=true");
        args.add("power_on=true");
        return String.join(" ", args);
    }

    private AdbStream openAbstract(String scid) throws Exception {
        String name = "scrcpy_" + scid;
        long deadline = monotonicMs() + LISTENER_DEADLINE_MS;
        ConnectException last = null;
        while (monotonicMs() < deadline) {
            if (serverEof) {
                throw new IOException("server exited before opening " + name, last);
            }
            try {
                AdbStream stream = adb.openAbstract(name);
                Log.i("openAbstract %s ok", name);
                return stream;
            } catch (ConnectException e) {
                // A rejected OPEN consumed no server accept. Retry while the
                // server creates its listener; timeout failures remain fatal
                // because their acceptance state is ambiguous.
                last = e;
                Thread.sleep(LISTENER_RETRY_MS);
            }
        }
        throw new IOException("server did not open " + name + " within "
                + LISTENER_DEADLINE_MS + " ms", last);
    }

    private static String readDeviceMeta(InputStream in) throws IOException {
        byte[] probe = new byte[1];
        Wire.readFully(in, probe);
        if (probe[0] != 0) throw new IOException("invalid scrcpy probe byte");
        byte[] name = new byte[64];
        Wire.readFully(in, name);
        int n = 0;
        while (n < name.length && name[n] != 0) n++;
        for (int i = n; i < name.length; i++) {
            if (name[i] != 0) throw new IOException("invalid device-name padding");
        }
        return safeLogText(Wire.decodeUtf8(name, 0, n));
    }

    private void pump(InputStream in) {
        // Do not use BufferedReader.readLine(): a hostile target can emit an
        // unterminated line of arbitrary size and make it allocate until OOM.
        byte[] bytes = new byte[2048];
        try (InputStream source = in) {
            int n;
            while ((n = source.read(bytes)) >= 0) {
                if (n == 0) throw new IOException("server stdout made no progress");
                String chunk = safeLogBytes(bytes, n).trim();
                if (!chunk.isEmpty()) Log.i("server: %s", chunk);
            }
        } catch (IOException e) {
            if (!Thread.currentThread().isInterrupted()) Log.w("server-stdout closed: %s", e);
        } finally {
            serverEof = true;
            Log.i("server: stream end");
        }
    }

    // ---- adb sync push ----

    private void pushTextFile(String remotePath, String body, int mode) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        AdbStream sync = adb.openSync();
        try (InputStream src = new ByteArrayInputStream(bytes)) {
            int mtime = (int) (System.currentTimeMillis() / 1000L);
            Sync.push(src, sync.openOutputStream(), sync.openInputStream(),
                    remotePath, mode, mtime);
        } finally {
            try { sync.close(); } catch (IOException ignored) {}
        }
        Log.i("push script %s bytes=%d", remotePath, bytes.length);
    }

    private long push() throws Exception {
        AdbStream sync = adb.openSync();
        // sync.openInput/OutputStream() return wrapper streams whose close()
        // is a no-op; the AdbStream itself owns the channel. Close it once
        // in finally.
        try (InputStream src = ctx.getAssets().open(ASSET_JAR)) {
            int mtime = (int)(System.currentTimeMillis() / 1000L);
            return Sync.push(src, sync.openOutputStream(), sync.openInputStream(),
                    REMOTE_PATH, FILE_MODE, mtime);
        } finally {
            try { sync.close(); } catch (IOException ignored) {}
        }
    }

    private static String safeLogBytes(byte[] data, int len) {
        StringBuilder out = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            int b = data[i] & 0xff;
            if (b == '\n' || b == '\r' || b == '\t') out.append(' ');
            else if (b >= 0x20 && b <= 0x7e) out.append((char) b);
            else out.append('.');
        }
        return out.toString();
    }

    private static String safeLogText(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int type = Character.getType(c);
            out.append(Character.isISOControl(c) || type == Character.FORMAT ? '?' : c);
        }
        return out.toString();
    }

    private static long monotonicMs() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
    }
}
