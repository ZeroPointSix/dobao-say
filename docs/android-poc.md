# Android PoC（ZER-102）

## 目标

本 PoC 新增 `:app` Android Application 模块，固定包名与 `applicationId` 为
`com.zeropointsix.dobaosay`，用于验证「按住说话、松开结束、最终文本复制到剪贴板」的最小闭环。

## 技术基线

| 项目 | 当前值 |
| --- | --- |
| Android Gradle Plugin | 9.1.0 |
| Kotlin | AGP 9 内置 Kotlin，仓库 JVM 模块仍为 Kotlin 2.4.10 |
| compileSdk / targetSdk / minSdk | 35 / 35 / 26 |
| UI | 原生 Android View XML，无 Compose / AndroidX |
| ASR 路径 | `DefaultAsrSession` + `DoubaoAsrProvider` |
| 网络传输 | `provider-doubao` 使用 OkHttp 5.3.2，避免 Android 不支持的 `java.net.http` |

选择 View XML 的原因是 PoC 只需要单屏单动作，且当前仓库启用了依赖锁定与校验元数据；不引入
Compose/Material3 可以降低新增依赖面和构建风险。

## 构建

本仓库不提交 `local.properties`。本地或 CI 需要先安装 Android SDK，并通过环境变量提供路径：

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
./gradlew --no-daemon --stacktrace :app:assembleDebug
```

本次云环境使用 `/tmp/android-sdk` 安装了：

- `platforms;android-35`
- `build-tools;35.0.1`
- `platform-tools`

AGP 9.1.0 在构建时还自动安装了 `build-tools;36.0.0`。

Debug APK 输出路径：

```text
app/build/outputs/apk/debug/app-debug.apk
```

新增或升级 Android/OkHttp/AGP 依赖后，需要继续使用 Gradle 官方写入方式刷新锁定与校验元数据：

```bash
./gradlew --no-daemon --stacktrace \
  --write-locks \
  --write-verification-metadata sha256 \
  :app:assembleDebug
```

## 运行行为

- 首屏只展示品牌名、状态文本、结果文本和一个「按住说话」主按钮。
- 首次录音前请求 `RECORD_AUDIO`，并在系统要求 rationale 时说明：只在用户按住按钮时录音，音频会发送给豆包 ASR 生成文本。
- 按下按钮后创建 `DefaultAsrSession`，通过 `DoubaoAsrProvider` 连接 ASR；Ready 后启动 `AudioRecord`，按 16 kHz、mono、20 ms、PCM16 LE 推送音频帧。
- 松开按钮后停止录音并请求最终结果。
- 收到最终文本后写入系统剪贴板，并在屏幕上展示。

## 限制

- PoC 只支持 Activity 前台按住录音；没有后台录音、锁屏录音或 Foreground Service。
- 由于麦克风只在前台 Activity 且用户持续按住按钮时使用，本 PoC 暂不创建前台通知。后续如果要支持后台、悬浮窗、锁屏或长时间录音，需要新增 Foreground Service 与持续通知。
- 未提交真实用户音频、设备凭据、token 或转写文本。
- 当前云环境完成了 Debug APK 构建，但未连接真机执行端到端录音验收。
