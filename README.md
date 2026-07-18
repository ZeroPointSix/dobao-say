# dobao-say

后端优先的随身语音输入项目。

## 当前状态

当前只建设纯 Kotlin/JVM 的 ASR Provider 契约、会话状态机和离线测试。暂不包含产品 UI、Android Activity、Compose、真实 ASR 协议或凭据逻辑。

## 技术边界

- 后端核心：Kotlin/JVM 2.4.10。
- 构建与 CI：JDK 17、Gradle 9.5.0。
- Android/UI 在独立任务中进行特化实现。
- 正式代码采用 clean-room 独立实现。
- 未完成许可证和服务授权核查前，禁止复制参考仓库源码或接入私有协议。

## 构建

```bash
gradle --no-daemon clean test
```

CI 会安装固定 Gradle 版本，因此仓库当前不提交未经本地生成并校验的 Wrapper 二进制。

## 安全

禁止提交 Token、Cookie、设备凭据、签名文件、真实音频、用户转写文本、抓包或私有协议常量。

## 许可证

当前尚未作出正式许可证决定。仓库公开可见不代表授予复制、修改或分发权限。

## 跟踪

- Linear：ZER-102
- 工程基线：ZER-103
- Provider 核心：ZER-104
