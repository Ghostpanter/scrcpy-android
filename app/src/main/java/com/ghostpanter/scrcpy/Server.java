package com.ghostpanter.scrcpy;

import android.content.Context;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

    public Streams bringUp() throws Exception {
        serverEof = false;
        String version = readVersion();
        long pushed = push();
        Log.i("push %s bytes=%d", REMOTE_PATH, pushed);

        // Prefer root-elevated spawn when the user wants secure-layer capture
        // and the target has a working su (Magisk / KernelSU). Official
        // scrcpy-server.jar is unchanged; we only wrap the launch as uid 0
        // (scrcpy-root style) so createDisplay(secure) can succeed on
        // Android 12+. Any elevate failure falls back to a normal shell start.
        boolean wantElevate = Settings.rootCaptureSecure(ctx);
        boolean suAvailable = wantElevate && probeSu();
        if (wantElevate && !suAvailable) {
            Log.i("server: root capture on but su not available; using shell");
        }

        if (suAvailable) {
            String scid = newScid();
            String plain = buildCmdline(version, scid);
            String elevated = elevateCmd(plain);
            Log.i("spawn server (root) ver=%s scid=%s", version, scid);
            Log.i("cmdline: %s", elevated);
            try {
                return spawnAndConnect(elevated, scid);
            } catch (Exception e) {
                Log.w("server: root elevate failed, falling back to shell: %s", e);
                closeShell();
                serverEof = false;
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

    // Magisk / KernelSU: `su 0` runs the command as uid 0.
    private static String elevateCmd(String plain) {
        return "su 0 sh -c " + shellSingleQuote(plain);
    }

    private static String shellSingleQuote(String s) {
        // Wrap for sh -c; embed apostrophes as '\''.
        return "'" + s.replace("'", "'\''") + "'";
    }

    // Best-effort: true only when `su 0 id -u` reports 0 within a short budget.
    private boolean probeSu() {
        AdbStream s = null;
        Thread reader = null;
        final StringBuilder out = new StringBuilder();
        try {
            s = adb.openShell("su 0 id -u");
            AdbStream ref = s;
            reader = new Thread(() -> {
                try (InputStream in = ref.openInputStream()) {
                    byte[] buf = new byte[64];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        for (int i = 0; i < n; i++) {
                            char c = (char) (buf[i] & 0xff);
                            if (c >= 0x20 && c <= 0x7e) out.append(c);
                        }
                        if (out.length() > 32) break;
                    }
                } catch (IOException ignored) {}
            }, "su-probe");
            reader.setDaemon(true);
            reader.start();
            reader.join(2_500L);
            String textOut = out.toString().trim();
            boolean ok = false;
            if (!textOut.isEmpty()) {
                int end = 0;
                while (end < textOut.length() && Character.isDigit(textOut.charAt(end))) end++;
                if (end > 0) {
                    try { ok = Integer.parseInt(textOut.substring(0, end)) == 0; }
                    catch (NumberFormatException ignored) {}
                }
                if (!ok) ok = textOut.contains("uid=0");
            }
            Log.i("server: su probe -> %s (raw=%s)", ok, textOut);
            return ok;
        } catch (Exception e) {
            Log.i("server: su probe failed: %s", e);
            return false;
        } finally {
            if (reader != null && reader.isAlive()) reader.interrupt();
            closeQuietly(s);
        }
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
