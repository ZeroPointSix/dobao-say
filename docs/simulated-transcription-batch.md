# ZER-125：小规模模拟转录批次

本批次仅用于验证纯 Kotlin/JVM 的离线音频管线和 ASR 会话状态机。输入是测试生成的
PCM16 字节，输出文本由 test-only `SimulatedTranscriptionDriver` 的固定脚本生成。

## 固定批次

- 12 个场景：8 success、3 failed、1 cancelled。
- 成功场景覆盖 manual final、VAD final，以及 8 秒/12 秒、transport capacity=2 的慢消费。
- 异常场景覆盖 connect timeout、partial 后 final timeout、Driver Failed。
- 每轮都检查事件序号、唯一终态、Driver cleanup 和 Loopback 守恒。
- 测试把结构化 JSON 写到 `asr-core/build/reports/simulated-transcription/batch.json`；该生成物不入库。
- 另有使用 `Dispatchers.Default` 和硬超时的真实调度 smoke，避免结论只来自虚拟时间。

## 结论边界

- 模拟文本不是语音识别结果，不计算 WER，也不代表任何真实 Provider 的准确率。
- 虚拟时间和测试脚本延迟不是线上服务延迟或性能基准。
- 本批次不连接网络，不读取真实音频、Token、Cookie、设备凭据或 `credential-core` Secret，
  不实现 Wave、WebSocket、Opus 或任何私有协议，也不读取隔离研究 refs。
- 本批次不证明 Android 麦克风、真机或真实 Provider 闭环，也不代表 ZER-107 完成或 M1 解锁。
- `InMemoryLoopbackTransport` 的容量保证只覆盖测试音频传输段；`DefaultAsrSession` 当前
  `commands`/`effects` 使用无界 Channel，因此端到端入口背压缺口仍然存在。
