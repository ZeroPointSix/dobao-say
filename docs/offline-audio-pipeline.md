# ZER-121：离线音频管线边界

本切片是完全独立设计的 provider-neutral Kotlin/JVM 工具，只处理内存中的 PCM 数据和确定性生命周期。

## 音频帧

AudioFrame 包含递增序号、相对流起点的单调毫秒时间、AudioFormat 和 PCM 数据。构造输入与每次读取都会复制，调用方不能在构造后修改帧内容。默认格式为 16 kHz、mono、PCM16 little-endian、20 ms，对应每帧 640 字节。

## 分帧与尾帧

Pcm16Framer 接受任意边界的连续字节块，并只生成完整的 20 ms 帧：

- 空输入不生成帧；
- 639 字节保留为尾部；
- 640 字节生成一帧；
- 641 字节生成一帧并保留 1 字节尾部；
- DROP（默认）在 finish() 时丢弃不完整尾部并记录字节数；
- PAD_WITH_ZERO 在 finish() 时补零生成最后一帧并记录补零字节数。

finish() 幂等，完成后继续写入会被拒绝。

## 背压与 Loopback

InMemoryLoopbackTransport 使用固定容量令牌和固定容量 Channel。队列满时，额外 send 会挂起，不会进入无界队列；消费者释放容量后发送继续。stats 可观察当前队列、历史最大队列、传递中帧、接受/接收/关闭丢弃帧、活跃发送者和关闭次数。

stop()、cancel() 与 close() 汇聚到同一个幂等关闭操作：

- 关闭丢弃仍在队列中的帧并记账；
- 队列满时挂起的发送会被唤醒并返回 Closed；
- 传递延迟中取消接收仍会在 finally 释放容量；
- 实现不创建 worker 协程，因此不存在关闭后的残留 worker。

## 不证明的事项

本切片不包含也不证明：

- Opus 编码或兼容性；
- WebSocket、Wave 或任何私有协议；
- 真实端点、Token、Cookie、设备凭据或 Provider 服务可用性；
- Android 音频采集、真机转写、UI 或输入法闭环；
- ZER-107 已完成。

真实 Provider 路径继续受 ZER-106 与 ZER-108 的授权、凭据和 Go/No-Go 门禁约束。
