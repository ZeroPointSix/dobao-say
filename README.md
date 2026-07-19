# dobao-say

后端优先的随身语音输入项目。

## 当前状态

当前只建设纯 Kotlin/JVM 的 ASR Provider 契约、会话状态机和离线测试。暂不包含产品 UI、Android Activity、Compose、真实 ASR 协议或凭据逻辑。

## 技术边界

- 后端核心：Kotlin/JVM 2.4.10。
- 构建与 CI：JDK 17、Gradle Wrapper 9.5.0。
- Android/UI 在独立任务中进行特化实现。
- 正式代码采用 clean-room 独立实现。
- 未完成许可证和服务授权核查前，禁止复制参考仓库源码或接入私有协议。

## 构建

安装 JDK 17 后，在干净检出中执行：

```bash
./gradlew --no-daemon --stacktrace --warning-mode=fail clean check
```

该单一入口会编译后端核心、运行单元测试、将 Kotlin 编译警告视为错误，并执行稳定的基础格式检查。Wrapper 固定 Gradle 9.5.0，下载分发包时会校验 SHA-256。

详细版本、命名空间与暂缓决策见 [构建基线](docs/build-baseline.md)。

## 安全

禁止提交 Token、Cookie、设备凭据、签名文件、真实音频、用户转写文本、抓包或私有协议常量。

## 许可证

当前尚未作出正式许可证决定。仓库公开可见不代表授予复制、修改或分发权限。

## 跟踪

- Linear：ZER-102
- 工程基线：ZER-103
- Provider 核心：ZER-104
