# dobao-say

后端优先的随身语音输入项目。

## 当前状态

当前包含 Kotlin/JVM 的 ASR Provider 契约、会话状态机、离线测试，Doubao Provider，以及 Android 系统基础设施 + PoC UI。

Android 侧已具备：`PermissionGate`、`MicPcmCapture`、`AudioFocusController`、`VoiceCaptureService`（microphone FGS + 通知停止）、按住说话驱动服务并将最终文本复制到剪贴板。详见 [android-system-infra.md](docs/android-system-infra.md)。

Doubao Provider 的 JVM 端文件转写 live smoke 已使用样例 WAV 对真实 Doubao IME ASR 验证通过；文档不记录凭据、Token 或完整转写文本。

## 技术边界

- 后端核心：Kotlin/JVM 2.4.10。
- 构建与 CI：JDK 17、Gradle Wrapper 9.5.0。
- Android PoC：`:app`，AGP 9.1.0，`applicationId` 固定为 `com.zeropointsix.dobaosay`，min/target/compile SDK 为 26/35/35。
- `:provider-doubao` 为 ZER-102 产品负责人 override ZER-108 NO-GO 后的非官方 Doubao IME ASR PoC；仅供内部验证，正式分发前仍需授权与法务审查。
- 核心模块继续保持 provider-neutral；真实凭据、用户音频与抓包不得入库。

## 构建

安装 JDK 17 后，在干净检出中执行：

```bash
./gradlew --no-daemon --stacktrace --warning-mode=fail clean check
```

该单一入口会编译后端核心、运行单元测试、将 Kotlin 编译警告视为错误，并通过 Kotlinter 5.6.0（ktlint）执行 Kotlin 静态格式检查。仓库同时保留基础空白检查，但不把它冒充静态分析。Wrapper 固定 Gradle 9.5.0，下载分发包时会校验 SHA-256。

详细版本、命名空间与暂缓决策见 [构建基线](docs/build-baseline.md)。

Android PoC 需要本机安装 Android SDK，并通过 `ANDROID_HOME` 或 `ANDROID_SDK_ROOT` 指定路径：

```bash
./gradlew --no-daemon --stacktrace :app:assembleDebug
```

详见 [Android PoC](docs/android-poc.md)。

## 安全

禁止提交 Token、Cookie、设备凭据、签名文件、真实音频、用户转写文本、抓包或私有协议常量。

## 许可证

当前尚未作出正式许可证决定。仓库公开可见不代表授予复制、修改或分发权限。

## 跟踪

- Linear：ZER-102
- 工程基线：ZER-103
- Provider 核心：ZER-104
