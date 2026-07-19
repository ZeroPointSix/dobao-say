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

本次云环境使用了 `/tmp/android-sdk` 安装了：

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

## 可用版（0.4.0-usable）

- 前台服务录音 + 通知停止
- 空结果/取消/失败不自动复制；支持「手动复制」
- App 私有目录缓存豆包设备凭据，避免每次重新注册

## UI 对齐版（0.5.0-ui · ZER-119）

对齐 Notion「前端样式草稿」view-02（就地变态悬浮球）：

- Linear 暗色背景与蓝紫/红/琥珀/绿状态色
- 中心语音球：单击开始 / 再单击结束；Recording 脉冲；Finalizing spinner
- 本会话历史列表 + 设置（清空历史 / 清除凭证）
- 仍是 App 内入口，不是系统悬浮窗（ZER-110）

## ASR 质量版（0.5.1-asr-quality）

对照 Node / Swift 逆向客户端与 APK stage1 结论，修复 PTT 听写「像变笨」的主因：

- 连接期麦克风预缓冲（不再等 Ready 才开麦）
- 中段 VAD Final 拼接，不再提前终态
- 停止时约 400ms 静音尾垫

细节见 [asr-quality-from-reverse.md](asr-quality-from-reverse.md)。

## 运行行为

- 首屏展示品牌、中心语音球、状态 chip、转写区与手动复制。
- 首次录音前请求 `RECORD_AUDIO` / 通知权限。
- 单击语音球后创建会话，通过 `DoubaoAsrProvider` 连接 ASR；Ready 后启动 `AudioRecord`。
- 再单击结束录音并请求最终结果；成功后自动复制到剪贴板。

## 系统基础设施

会话由 `VoiceCaptureService`（foregroundServiceType=microphone）承载：

- `PermissionGate` 统一申请麦克风/通知权限
- `MicPcmCapture` + `AudioFocusController` 负责采集与焦点
- 前台通知标明正在录音，并提供停止动作
- `MainActivity` 以单击切换驱动开始/停止并观察 `VoiceSessionBus`

细节见 [android-system-infra.md](android-system-infra.md)。

## 限制

- 仍无悬浮窗全局入口、锁屏专用策略或 Keystore 凭据落盘（见 ZER-110 / ZER-127）。
- 未提交真实用户音频、设备凭据、token 或转写文本。
- 当前云环境可完成 Debug APK 构建；真机麦克风端到端见 ZER-126。
