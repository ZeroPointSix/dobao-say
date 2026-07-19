# Doubao IME ASR Provider 边界

## 范围

`provider-doubao` 是 Doubao IME ASR 协议面的非官方 Kotlin/JVM PoC，当前覆盖：

1. 设备注册；
2. 从 settings 接口获取 ASR `app_key`；
3. 使用 protobuf 消息和 raw Opus packet 的明文 WebSocket ASR。

本工作仅用于 ZER-102 MVP 内部测试，并基于产品负责人对 ZER-108 NO-GO 的 override 授权推进。它不是官方 Doubao SDK；在完成法务、许可证、产品和服务授权审查前，不得作为生产集成使用。

## 验证状态

- JVM 端文件转写 live smoke 已使用样例 WAV 对真实 Doubao IME ASR 验证通过；不在仓库记录凭据、Token、个人音频或完整转写文本。

## 安全边界

- 不提交真实 `device_id`、`install_id`、token、cookie、请求抓包或个人音频。
- CLI 输出会对 token 类字段脱敏；后续日志和异常信息必须保持这一不变量。
- 凭据文件路径由用户显式提供，仅用于本地 smoke 测试；在支持 POSIX 权限的文件系统上会写成 owner-only 权限。
- Wave crypto 不属于本 provider 范围，因为 ZER-102 已验证的 ASR 路径是 plain WebSocket protobuf，并在协议消息中携带 ASR token。

## 协议与 codec 说明

- 模块保留适配后的 `src/main/proto/asr.proto`，用于协议记录和后续迁移到生成代码。
- 当前实现使用手写最小 protobuf codec，只覆盖 ASR 路径已观测字段，以避免 PoC 阶段引入 protoc 工具链。
- 音频输入限制为 16 kHz mono PCM16 LE，按 20 ms 分帧后编码为 raw Opus packet。
- 默认 Opus 路径使用纯 JVM Concentus 包。请保留 `DoubaoOpusEncoder` 接口，方便后续因性能、许可证或兼容性审查改用 Android/JNI/native encoder。
