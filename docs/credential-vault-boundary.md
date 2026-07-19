# ZER-122：凭据保险库威胁边界

本切片是 provider-neutral 的纯 Kotlin/JVM 契约与生命周期实现。它不连接网络、不获取或刷新凭据，也不知道任何 Provider、账号、设备、端点或私有协议。

## 已提供的保证

- Secret API 只接受字节，不接受 `String`；Secret、租约、受保护载荷和结果的文本表示不包含内容。
- 构造输入、跨端口数据和读取临时数组均采用防御性复制。
- `useBytes` 只提供临时副本，并在正常返回、异常或取消时于 `finally` 中尽力覆零。
- 注入 `Clock` 后，`expiresAt <= now` 一律为 Expired；Missing、Corrupt 和 Unavailable 是不同的类型化结果，不触发联网、刷新或重新注册。
- 存储端口要求 revision compare-and-set 原子提交；seal 或提交失败不替换旧版本，冲突对调用方可见。
- 诊断只接收固定枚举；诊断回调失败会被隔离，不改变业务结果或已提交状态。
- 默认 Vault 不创建后台协程。取消在存储提交前发生时不得留下可见新版本；具体 Store 必须遵守其端口的取消/原子性契约。

## 不提供的保证

- 没有 Android Keystore、硬件密钥、文件、数据库或其他持久化适配器，因此不保证磁盘静态加密。
- 没有生产 `CredentialProtector`；测试 Protector 只是合成数据测试替身，不是加密算法，严禁用于生产。
- 同进程恶意代码、调试器、堆转储、交换分区、JVM/GC 复制或操作系统被攻破均不在本切片防护范围。
- JVM 不提供可验证的绝对内存擦除；覆零仅为 best-effort，不能证明所有运行时副本已消失。
- metadata（key、revision、过期时间）被定义为非敏感；调用方不得在其中放入 Token、Cookie、票据、账号或设备标识。
- 不包含真实凭据、注册、Wave、签名、密钥派生、网络、Provider DTO、撤销或刷新流程。
- 本切片不证明 ZER-106 完成，不改变 ZER-108 的 NO-GO/CONDITIONAL GO 门禁，也不解锁 M1。

## 适配器责任

未来生产 Protector/Store 必须位于独立适配模块，通过公开 scoped `useBytes` 读取临时副本，并保证不保留该数组；Store 必须防御性复制载荷、实现原子 CAS，并保证取消在提交点之前不会产生可见半成品。任何 Android 或持久化适配器都需要单独威胁建模、平台测试和授权审查。
