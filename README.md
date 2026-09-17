# scrcpy-android (Ghostpanter)

基于 [0xlena/scrcpy-android](https://gitlab.com/0xlena/scrcpy-android) 的 Android 投屏客户端，面向 **Android 17**（API 37）。

Based on the F-Droid scrcpy Android client. Not affiliated with Genymobile.

## 功能 Features

- 无线调试 **配对码** / **二维码**配对（本机显示 QR，被控端扫描）
- 传输分辨率（max size）、码率、延迟（最高帧率 + 低延迟关键帧）
- 包名 `com.ghostpanter.scrcpy`，可与 F-Droid 版并存

## 安装 Install

安装构建产物 `scrcpy-android.apk`（当前为 debug 签名）：

```bash
adb install -r scrcpy-android.apk
```

被控端：开发者选项 → 无线调试 → 配对码或「使用二维码配对设备」。无需 root。

| 字段 | 值 |
|------|----|
| applicationId | `com.ghostpanter.scrcpy` |
| versionName | `0.5-ghostpanter` |
| minSdk | 31 |
| compileSdk / targetSdk | 37 (Android 17) |

## 许可 License

Apache-2.0。请保留上游 Lena / Genymobile scrcpy 相关归属与声明。
