# 豆包输入法（Doubao IME）逆向分析文档索引

> 样本仓库：[ZeroPointSix/dobao-say](https://github.com/ZeroPointSix/dobao-say)（`sample-apk` 分支）  
> 用途限定：授权安全研究 / 本地逆向学习 / 防御分析。

## 报告列表

| 编号 | 文档 | 内容 |
|------|------|------|
| 00 | [00-sample-inventory.md](./00-sample-inventory.md) | 样本清单、哈希、签名、DEX、组件初探 |
| 01 | [01-upload-verification.md](./01-upload-verification.md) | 入库完整性校验 |
| 02 | [02-stage1-static-re.md](./02-stage1-static-re.md) | 第一阶段静态逆向（加固判断、API 初图） |
| 03 | [03-ui-structure.md](./03-ui-structure.md) | UI 界面结构（skin DSL、工具栏、叠加面板） |
| **04** | **[04-full-reverse-engineering-report.md](./04-full-reverse-engineering-report.md)** | **全面逆向分析报告（本文档集核心）** |

## 样本基线（快速参考）

| 项 | 值 |
|---|---|
| 包名 | `com.bytedance.android.doubaoime` |
| versionName | `1.3.15` |
| versionCode | `100315010` |
| minSdk / targetSdk | 26 / 33 |
| SHA-256 | `c140d16625f1b8eddb21fa905f0e98a74b3e242d2638415801207c23c449d59a` |
| 大小 | 155,200,288 bytes（~149 MB） |
| ABI | arm64-v8a only（54 个 `.so`） |
| 云端域名 | `https://ime.doubao.com` |
| 构建标记 | `2b0b44c_20260714_124829_7662229808918579238` |

## 分析工具链

- `aapt` / `aapt2`（build-tools 33.0.2）
- `jadx` 1.5.1
- `apktool` 2.11.1
- `readelf` / `nm` / `strings`
- `git lfs`（APK 为 LFS 对象，clone 后需 `git lfs pull`）

## 当前状态

- [x] 样本入库与哈希校验
- [x] aapt 正式版本与权限
- [x] jadx 反编译（~10628 个 Java 文件）
- [x] 加固判断（无传统 APK 壳）
- [x] IME 入口与 KeyboardJni / libshell 关系
- [x] 云端 API 地图
- [x] UI 皮肤 DSL 结构
- [x] 全面逆向分析报告（04）
- [ ] 动态抓包与协议字段还原
- [ ] Ghidra 级 native 伪代码
- [ ] 词库格式文档
