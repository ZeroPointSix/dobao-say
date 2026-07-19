# Android 系统基础设施（ZER-109 / ZER-130–133）

## 目标

把「与 Android 系统打交道」的能力从 `MainActivity` 抽成可复用基础设施，形成单活跃语音会话：

用户动作 → PermissionGate → VoiceCaptureService（FGS + 通知）→ MicPcmCapture + AudioFocus → ASR → UI/剪贴板

## 模块

| 类型 | 路径 | 职责 |
| --- | --- | --- |
| 采集 | `infra/MicPcmCapture.kt` | AudioRecord、16 kHz mono PCM16、20 ms 帧 |
| 焦点 | `infra/AudioFocusController.kt` | 申请/放弃 AUDIOFOCUS；丢失时停止采集 |
| 权限 | `infra/PermissionGate.kt` | `RECORD_AUDIO` + `POST_NOTIFICATIONS`（API 33+）、rationale、跳转设置 |
| 通知 | `infra/VoiceCaptureNotifications.kt` | 通道、前台通知、停止动作 |
| 总线 | `session/VoiceSessionBus.kt` | 进程内状态扇出（无 AndroidX） |
| 服务 | `session/VoiceCaptureService.kt` | 单会话 FGS（`microphone`）、ASR 编排 |

## Manifest

- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_MICROPHONE`
- `POST_NOTIFICATIONS`
- `VoiceCaptureService`：`foregroundServiceType="microphone"`，`exported=false`

## 行为契约

1. **单会话**：重复 `ACTION_START` 在已有会话时直接忽略。
2. **可见性**：进入会话后立刻 `startForeground`；通知可停止。
3. **幂等停止**：按钮松开与通知「停止」都走 `ACTION_STOP`。
4. **焦点丢失**：放弃采集并请求 ASR stop。
5. **Activity 重建**：通过 `VoiceSessionBus.latest` 恢复展示，不持有 AudioRecord。

## 明确不做（本切片）

- 悬浮窗 / `SYSTEM_ALERT_WINDOW`（ZER-110）
- Android Keystore 凭据持久化（ZER-127）
- 锁屏后台无限录音、输入法注入

## 验证

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew --no-daemon :app:assembleDebug
```

真机验收见 ZER-126。
