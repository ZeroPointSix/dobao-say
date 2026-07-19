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
- `kotlinQualityCheck`，检查 Kotlin 源码与 Kotlin 构建脚本的 Tab、行尾空白和文件末尾换行。

CI 与本地使用同一命令。CI 使用的 GitHub Actions 固定到完整 commit SHA，避免可变标签漂移。

## Android 决策暂缓

当前仓库没有 Android Application 模块，因此：

- `com.zeropointsix.dobaosay` 只是 JVM group/namespace，不是已确认的 Android `applicationId`；
- Android `applicationId`、`namespace`、`minSdk`、`targetSdk`、`compileSdk` 尚未决定；
- 当前基线不能构建 Debug APK，也不声称满足 Android 真机验收；
- Android SDK 与应用模块由后续 Android 特化任务基于产品和设备证据决定。

## 依赖锁定与校验

本次没有手工创建 dependency lock 或 verification metadata。原因是当前执行环境不能完成一次可信的完整依赖解析；手工拼写这些生成物会制造虚假的可复现性。后续应在可信 JDK 17 环境中通过 Gradle 官方命令生成、审查并提交，然后将其纳入同一 `check` 入口。
