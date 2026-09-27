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
| versionName | `0.5.9-ghostpanter` |
| versionCode | `18` |
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

### Elevate / connect (vc18)

- Quick su probe (~1.5s). If Magisk already granted ADB shell → skip 60s wait.
- Long Magisk wait only when the quick probe hangs (first grant).
- Elevated start script prints `scrcpy-gp:uid=0` and aborts unless root; UI toasts 已获 Root / 正在等待 Magisk 授权 / 提权失败仍黑屏.
- **vc18 fix:** start script flushes `scrcpy-gp:uid=` via a child `sh -c echo` (+ stderr)
  before `exec app_process`. ADB `shell:cmd` is non-TTY (fully buffered); echo+exec
  previously discarded the uid=0 banner so probe-OK devices still FALLBACK'd.
- Start wrapper uses `su -c` / `su 0 -c` (not bare `su 0 sh path`) for Magisk + KernelSU.

