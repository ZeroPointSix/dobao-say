# Doubao ASR 质量优化对照（逆向参考）

对照来源：

- Node：`liberta69/doubaoime-asr-nodejs`（本地 `/tmp/doubaoime-asr-nodejs`）
- Swift PTT：`gfreezy/DoubaoASR`（README 明确写了 VAD 分段拼接与 stop 等待）
- APK 逆向分支：`reverse-sample` / `cursor/dobao-ime-stage1-re-e3c0`（原始豆包输入法 1.3.15）

## 原始输入法「听起来更好」的原因（静态结论）

1. **本地 ONNX / 词库**：`libonnxruntime` + `assets/dict/**`，不只是云端裸 ASR。
2. **QUIC / TTNet 长连接**：`libime_net_sdk` 有 `must_connect` / zero-rtt / keepalive，握手成本被摊薄。
3. **云端两遍/三遍**：`enable_asr_twopass` / `enable_asr_threepass`（Node/我们 session payload 已开）。
4. **交互模型是连续说 + VAD 切段**，不是「收到一次 Final 就结束整次录音」。

APK stage1 报告未还原到 mic 环形缓冲的伪代码；质量差距更直接来自下面客户端行为差。

## Node / Swift 客户端关键行为

| 行为 | Node realtime / Swift DoubaoASR | 我们改前 |
| --- | --- | --- |
| 麦克风时机 | `start()` 即采集，与 WS 并行 | Ready 后才 `AudioRecord` |
| 中段 `is_vad_finished` Final | **拼接多段**，会话继续 | `commitTerminal` 立刻成功结束 |
| `SpeechEnded` | 仅元数据 | `autoStop` → `RequestStop` |
| 停止尾音 | Node 补 1 帧静音 LAST；Swift 再等 ≤2.5s `SessionFinished` | 仅 20ms 静音 LAST |
| 终态时机 | `FinishSession` 后等 Final / SessionFinished | 第一个 Final 即 Closed |

## 本仓库落地（0.5.1-asr-quality）

1. **PTT 会话配置**：`autoStopOnVad=false`、`commitFinalImmediately=false`，多段 VAD Final 拼接后再在手动 stop / RemoteClosed 时落终态。
2. **连接期预缓冲**：握手期间麦克风写入 ~3s 环形缓冲，Ready 后先冲刷再实时推流。
3. **停止静音尾垫**：`DoubaoAsrDriver.requestStop` 发送约 400ms（20×20ms）静音后再打 LAST + `FinishSession`。
4. **UI**：中段 Final 只更新「识别中」，成功态仍以 `Closed(Succeeded)` 为准。

## 仍未搬的 IME 能力（刻意延后）

- 系统悬浮窗入口（ZER-110）
- QUIC 传输替换 WebSocket
- 本地 ONNX VAD / ASR
- 跨录音复用 WS（Swift 明确不复用，避免 concurrent quota）
