# 可复现构建基线

## 仓库角色与可见性

- 正式仓库：`ZeroPointSix/dobao-say`。
- 当前可见性：Public。
- 仓库是全新、非 Fork 的正式产品仓库；参考资料只读，产品代码必须 clean-room 独立实现。
- 公开可见不等于授予许可证。项目 LICENSE 决策仍未完成，在作出明确决定前不得假设复制、修改或分发授权。

## 后端版本基线

| 项目 | 固定值 |
| --- | --- |
| JDK | Temurin 17（CI） |
| Kotlin | 2.4.10 |
| Gradle Wrapper | 9.5.0 |
| Gradle 分发包 SHA-256 | `553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746` |
| Wrapper JAR SHA-256 | `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7` |
| JVM group/namespace | `com.zeropointsix.dobaosay` |

Wrapper 脚本和 JAR 来自 Gradle 官方 `v9.5.0` 标签；JAR 在提交前按上表 SHA-256 核验。Wrapper 属性同时校验下载的 Gradle 分发包。

## 单一验证入口

在仓库根目录执行：

```bash
./gradlew --no-daemon --stacktrace --warning-mode=fail clean check
```

`check` 包含：

- Kotlin/JVM 编译，且编译警告视为错误；
- JUnit Platform 单元测试；
- Kotlinter 5.6.0（ktlint）的 `lintKotlin`，执行真正的 Kotlin 静态格式检查；
- `kotlinQualityCheck`，只作为基础空白检查，检查 Kotlin 源码与 Kotlin 构建脚本的 Tab、行尾空白和文件末尾换行，不代表静态分析。

CI 与本地使用同一命令。CI 使用的 GitHub Actions 固定到完整 commit SHA，避免可变标签漂移。

## Android PoC 基线

当前仓库包含 ZER-102 最小 Android PoC：

- 模块：`:app`；
- `applicationId` / `namespace`：`com.zeropointsix.dobaosay`（PoC 冻结）；
- `minSdk` / `targetSdk` / `compileSdk`：`26` / `35` / `35`；
- Android Gradle Plugin：`9.1.0`，使用 AGP 9 内置 Kotlin 支持；
- UI：原生 View XML 单屏单按钮，不引入 Compose / AndroidX；
- ASR 会话：`DefaultAsrSession` + `DoubaoAsrProvider`。

Android SDK 不写入 `local.properties`。本地构建需通过 `ANDROID_HOME` / `ANDROID_SDK_ROOT`
指向已安装 SDK，再执行：

```bash
./gradlew --no-daemon --stacktrace :app:assembleDebug
```

详细运行行为、限制和 APK 路径见 [Android PoC](android-poc.md)。

## 依赖锁定与校验

所有项目配置启用 Gradle dependency locking。提交的 `gradle.lockfile` 由 Gradle 在 JDK 17 环境实际解析 `check` 所需配置后生成，不手工猜测版本。

`gradle/verification-metadata.xml` 同样由 Gradle 对实际解析产物生成，至少记录 SHA-256。生成后必须人工审查组件来源、算法与条目结构；普通 `check` 在依赖校验失败时直接失败。新增或升级依赖时，必须用 Gradle 官方 `--write-locks` 与 `--write-verification-metadata sha256` 重新生成并审查差异，禁止手工填写未知校验值。
