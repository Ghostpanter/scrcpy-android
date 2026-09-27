# scrcpy-android (Ghostpanter) — build notes

Updated: 2026-09-18 00:30 HKT (UTC+8)

## Rebuild from a fresh clone

```sh
git clone https://github.com/Ghostpanter/scrcpy-android.git
cd scrcpy-android
# JDK 17+ and Android SDK required
export JAVA_HOME=…   # e.g. path to JDK 17
export ANDROID_HOME=…  # Android SDK root
./scripts/update-server   # fetches scrcpy-server.jar into app/src/main/assets/
./gradlew :app:assembleDebug
```

Requirements for `assembleDebug`:
- Gradle wrapper (`gradlew` + `gradle/wrapper/*`)
- App sources under `app/`
- `:adb` module at `vendor/libadb-android/libadb` (see `settings.gradle`)
- `scripts/update-server` (or a pre-placed `app/src/main/assets/scrcpy-server.jar`)

## Package / SDK

| Field | Value |
|-------|-------|
| applicationId / namespace | `com.ghostpanter.scrcpy` |
| versionName | `0.5-ghostpanter` |
| versionCode | `6` |
| minSdk | 31 |
| compileSdk / targetSdk | 37 |
| Upstream | https://gitlab.com/0xlena/scrcpy-android (v0.5) |
| License | Apache-2.0 (+ bundled notices) |

## Ghostpanter deltas vs upstream v0.5

1. Package `invalid.lena.scrcpy` → `com.ghostpanter.scrcpy`
2. compileSdk/targetSdk 36 → 37
3. Latency settings: max FPS + prefer low-latency encoder (`i-frame-interval=1`)
4. QR Wireless Debugging pairing (display QR; target scans; mDNS discovery)
5. scrcpy-server asset via `scripts/update-server` (scrcpy 4.1)

## Notes

- `app/src/main/assets/scrcpy-server.jar` is intentionally gitignored; regenerate with `./scripts/update-server`.
- Debug builds use the AGP debug keystore.
- Do not commit `local.properties`, ADB identity keys, or keystores.


## Install / signing (v0.5.9)

Root cause of “无法安装 / App not installed” on the first v0.5.9 GitHub
asset: the APK was **unsigned** (`assembleRelease` without `KEYSTORE_*`).
Android PackageInstaller rejects unsigned APKs.

Release builds now **require** `KEYSTORE_PATH` / `KEYSTORE_PASS` /
`KEY_ALIAS` / `KEY_PASS` and fail the Gradle task if they are missing.
APKs are signed V1+V2 with the Ghostpanter release keystore (kept outside
the repo under `/workspace/secrets/`).

| Field | Value |
|-------|-------|
| applicationId | `com.ghostpanter.scrcpy` |
| versionName | `0.5.13-ghostpanter` |
| versionCode | `24` |
| minSdk | 31 (Android 12+) |
| ABIs | `arm64-v8a`, `x86_64` |

**Upgrade notes**

- Fresh install: open the APK (allow install from this source) or
  `adb install scrcpy-android.apk`.
- If a previous **debug**-signed Ghostpanter build is installed, Android
  will refuse the update (different signing key). Uninstall
  `com.ghostpanter.scrcpy` first, then install this release.
- The broken unsigned v0.5.9 asset never installed, so most users only
  need a fresh install of the replaced asset.




### Secure capture / elevate (vc21 / 0.5.10)

**Root cause of elevate-OK but lock/secure still black:** stock scrcpy-server 4.1
calls `Os.setuid(2000)` at startup (irreversible) and creates a virtual display
**without** `VIRTUAL_DISPLAY_FLAG_SECURE` / `createDisplay(secure=true)`. So Magisk
uid=0 never helped FLAG_SECURE on Android 12+ — the server dropped root before
capture and never asked for secure layers. Log clue: `feature-secure-playback=0`
with normal home frames but black lock/banking.

**Code fix (pinned server 4.1, Ghostpanter patch):**
1. `Server.dropRootPrivileges`: `seteuid(2000)` instead of `setuid(2000)` — keep ruid=0.
2. `ScreenCapture`: when `getuid()==0`, temporarily `seteuid(0)` and create a
   secure mirror via `DisplayManager` (`AUTO_MIRROR|SECURE`) or SurfaceControl
   `createDisplay(secure=true)` (vvb2060 / Genymobile#4947 approach).
3. Client: clipboard flood guard (skip >4096 chars + 2s dedupe); throttle encoder
   reset on video-sink overflow to ≥2s.

**Honesty / OEM caveat:** On some Android 14/16 OEM builds (incl. HyperOS), even
AID_SYSTEM/root secure virtual displays can still black-out lock/secure apps.
Then Magisk + LSPosed + **Disable FLAG_SECURE** (or equivalent Zygisk module) is
required — not bundled. Blind unlock keypad remains as non-root fallback.

**Build patched server jar:** `./scripts/install-patched-server` (not
`update-server`, which fetches stock upstream). Asset stays gitignored; rebuild
before `assembleRelease`.



### Connect / audio + A16 shell start (vc24 / 0.5.13)

**Root cause (MI 9 HyperOS Android 16, 0.5.12):** Display path succeeded after
`setuid(2000)` safety net, but `AudioRecord.startRecording()` threw
`UnsupportedOperationException`. Only API 30 wrapped that as
`AudioCaptureException`; on A16 the UOE escaped `AudioEncoder.start()`,
`finally` called `onTerminated(false)`, then the default
`UncaughtExceptionHandler` on `app_process` killed the whole server →
control+video EOF → reconnect 1/5…5/5.

**Fix:**
1. `AudioDirectCapture.start()`: catch UOE on **all** API levels →
   `AudioCaptureException` (soft retry once on non-30).
2. `AudioEncoder.encode()`: capture failures use `writeDisableStream(false)`;
   only config/encoder failures use `true`. Catch leftover `RuntimeException`
   as soft disable.
3. `AudioEncoder.start()` / `AudioRawRecorder.start()`: catch `Throwable`,
   log, **never rethrow** after `onTerminated`; capture failures stay
   `fatalError=false` so video+control survive.
4. Android 16+: `Server.dropRootPrivileges()` uses stock `Os.setuid(2000)`
   when `SDK_INT>=36` or SurfaceControl has no `createDisplay` (restore vc20
   shell start). Older devices keep seteuid retain-ruid for secure VD.
5. Client `AudioStream`: fourcc==1 soft-lands like disabled (no scary IOE).

### Connect / A16 HyperOS setuid fix (vc23 / 0.5.12)

**Root cause (MI 9 HyperOS Android 16, 0.5.11 failed):** `Server.dropRootPrivileges()`
does `Os.seteuid(2000)` keeping ruid=0. The ScreenCapture safety net then called
`Os.setuid(2000)` while **euid was already 2000**. On Linux/Android, `setuid`
requires euid==0 (or CAP_SETUID), so it failed with
`IOException: setuid(2000) failed after display create failure` → reconnect loop.
FakeContext package=`android` under uid==0 did not fix DisplayManager on this OEM.

**Fix:**
1. Safety net: `Os.seteuid(0)` then `Os.setuid(2000)`, sync Workarounds to
   `com.android.shell`, log ruid/euid before and after, wrap ErrnoException clearly.
2. A16 shortcut: if SurfaceControl has no createDisplay and secure already failed,
   skip futile non-secure DM/SC attempts and go to the drop path sooner. Still try
   secure once with runAsRootEuid when ruid==0.
3. After successful drop, normal DisplayManager mirror (vc20-like). Secure capture
   abandoned after drop — OK; UI still mentions LSPosed for lock/secure on some OEMs.

### Connect / A16 HyperOS (vc22 / 0.5.11)

**Root cause (MI 9 HyperOS Android 16):** v0.5.10 kept `ruid=0` via `seteuid(2000)`
for secure capture, but DisplayManager checks that FakeContext package
`com.android.shell` matches calling uid — uid 0 mismatches. Meanwhile
`SurfaceControl.createDisplay(String,boolean)` was removed on A16. Both secure
and fallback paths failed → `AssertionError: Could not create display` →
reconnect loop. vc20 (full `setuid(2000)`) could still create a display.

**Fix:**
1. `FakeContext` / `Workarounds`: when `getuid()==0`, report package `"android"`
   and AttributionSource ROOT_UID so DisplayManager package checks pass under
   retained root; keep `com.android.shell` when uid is 2000.
2. `SurfaceControl.createDisplay`: try known signatures; clear Exception if gone
   so callers fall through (never assume SC works on A16).
3. `openSecureDisplay`: if DM SECURE fails and SC has no createDisplay, rethrow
   the DM exception (do not mask with missing SC method).
4. `ScreenCapture.start` safety net: if still no display under ruid=0, log
   `Display: falling back to setuid(2000) after root display create failed`,
   permanently `setuid(2000)`, sync Workarounds package to shell, retry stock
   `createVirtualDisplay` once (vc20-like connectivity).
5. Failures throw `IOException`, not `AssertionError`, so the session is not
   killed solely because the secure path failed.

**Honesty:** Normal mirroring must work without LSPosed. Some HyperOS A16 builds
may still need Magisk+LSPosed+Disable FLAG_SECURE for lock/secure apps only.

### Elevate / connect (vc20)

- **Root cause (user Magisk logs):** Magisk granted uid 0, then
  `scrcpy-gp-start.sh[11]: CLASSPATH=/data/local/tmp/scrcpy-server.jar: inaccessible or not found`
  → `server exited before opening scrcpy_*`. Under Magisk/mksh, `exec CLASSPATH=jar app_process`
  treats `CLASSPATH=...` as the binary name.
- **Fix:** start script now `export CLASSPATH=<jar>` then
  `exec /system/bin/app_process / com.genymobile.scrcpy.Server ...` (absolute path).
- **Jar visibility:** copy jar into `/dev/.scrcpy-gp-server.jar` or `/data/adb/` inside the
  elevated script so Magisk mount-ns isolation cannot hide adbd's `/data/local/tmp`.
- **Recipes:** prefer Magisk-friendly `su -c`; demote `nsenter`; on confirmed uid0 +
  structural CLASSPATH/exec failure **stop cascading** (keeps ADB connection alive —
  do not close the AdbConnection on recipe failure).
- vc19 file-uid verify + in-app log viewer retained.

### Elevate / connect (vc19)
### Elevate / connect (vc19)

- Quick su probe (~1.5s). If Magisk already granted ADB shell → skip 60s wait.
- Long Magisk wait only when the quick probe hangs (first grant).
- Elevated start script writes uid to `/data/local/tmp/scrcpy-gp-uid` and prints
  `scrcpy-gp:uid=`; aborts unless root. UI toasts 已获 Root / 正在等待 Magisk 授权 /
  提权失败仍黑屏, with dialog → 设置→日志.
- **vc19 fix (root cause):** Magisk `su -c` + ADB `shell:cmd` often fully-buffers
  stdout until process exit; `exec app_process` never exits, so a banner-only
  `awaitElevatedUid` falsely FALLBACKs even when Magisk Superuser shows Shell ALLOWED.
  Now the start script writes uid to a file and the client polls it via a **separate**
  non-elevated shell. Multiple start recipes (`su -c`, `su 0 -c`, `/system/bin/su`,
  `/debug_ramdisk/su`, `nsenter`, interactive `su` stdin) — every probe/start
  transcript goes into the in-app Log ring buffer (Settings → 日志).
- **In-app log viewer:** Settings → 日志 — Verbose/Debug/Info/Warn/Error, scroll,
  copy/share/clear.

