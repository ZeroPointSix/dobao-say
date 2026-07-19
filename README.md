# 豆包输入法（Doubao IME）逆向分析仓库

本仓库用于对**豆包安卓输入法**原始 APK 做结构化存放、基线取证与后续逆向分析。

> 用途限定：授权安全研究 / 本地逆向学习 / 防御分析。请勿用于未授权攻击或传播破解产物。

## 样本基线

| 项 | 值 |
|---|---|
| 原始文件名 | `豆包输入法.apk` |
| 规范化路径 | `originals/apk/doubao-ime-original.apk` |
| 大小 | 155,200,288 bytes（约 149 MB） |
| 格式 | ZIP/APK |
| SHA-256 | `c140d16625f1b8eddb21fa905f0e98a74b3e242d2638415801207c23c449d59a` |
| MD5 | `9addf8dfb8bcf3f7c3781d429dc8890f` |
| 包名 | `com.bytedance.android.doubaoime` |
| 疑似版本名 | `4.2.243.8-doubao`（来自 dex 字符串，待 aapt/jadx 复核） |
| 关联版本串 | `4.2.1-rc.8-oime`、`4.3.2-rc.13` |
| 构建标记 | `2b0b44c_20260714_124829_7662229808918579238`（`assets/slardar.properties`） |
| 构建任务 | `wave_ime_publish_pkg` |
| 云端域名 | `https://ime.doubao.com` |
| ABI | 仅 `arm64-v8a`（54 个 `.so`） |
| DEX | `classes.dex` + `classes2.dex` + `classes3.dex`（DEX 038） |
| 签名 | `META-INF/BYTESIGN.*`（自签） |
| 签名主体 | 北京春田知韵科技有限公司 |
| 证书有效期 | 2025-09-08 → 2050-09-08 |
| 证书 SHA-256 | `D4:7C:AF:B4:50:B9:C8:19:11:80:40:27:95:F3:5C:E5:0C:E2:D8:B3:7F:13:00:B4:2E:7D:86:33:BC:86:B7:DF` |

更完整的清单见：

- `reports/00-sample-inventory.md`
- `reports/01-upload-verification.md`
- `workspace/notes/native-libs-arm64.txt`
- `originals/hashes/`

## 目录结构

```text
dobao-say/
├── originals/                 # 只读基线，勿改（Git LFS）
│   ├── apk/doubao-ime-original.apk
│   └── hashes/                # sha256 / md5
├── workspace/
│   ├── extracted/sample/      # 关键提取：Manifest/DEX/签名
│   ├── decompiled/            # jadx/apktool 输出（后续）
│   ├── native/                # so 抽出与分析（后续）
│   └── notes/                 # 手工笔记、中间结果
├── tools/                     # 本地工具脚本/包装
├── docs/                      # 分析文档
└── reports/                   # 阶段性报告
```

> 本地投放文件名可为 `豆包输入法.apk`（根目录，已 `.gitignore`）；入库规范名一律用 `originals/apk/doubao-ime-original.apk`。

## 关键组件线索

从二进制 `AndroidManifest.xml` 字符串可见：

- **Application**: `com.bytedance.android.doubaoime.ImeApplication`
- **IME Service**: `com.bytedance.android.doubaoime.ImeService`（`BIND_INPUT_METHOD`）
- **无障碍**: `...recognition.accessibilityImpl.ImeAccessibilityService`
- **设置/引导**: `SettingsActivityNext`、`ImeGuideActivity`、`LauncherActivity` 等
- **厂商推送**: 华为 / 小米 / vivo / OPPO/HeyTap 等
- **输入法配置**: `res/xml/method.xml`、`spellchecker.xml`

## 关键 native 库（按体积）

| 库 | 约大小 | 初步判断 |
|---|---:|---|
| `libonnxruntime.so` | 12.1 MB | ONNX 推理运行时 |
| `libkeyboard.so` | 9.0 MB | 键盘/输入核心（优先逆向） |
| `libaudioeffect.so` | 7.1 MB | 音频效果（语音相关） |
| `libshell.so` | 6.9 MB | 壳/加载器嫌疑（优先确认是否加固） |
| `libime_net_sdk.so` | 6.5 MB | 输入法网络 SDK |
| `libsscronet.so` | 6.1 MB | Chromium/cronet 网络栈（字节系常见） |
| `libcurl.so` | 5.1 MB | curl |
| `libmetasec_ml.so` | 3.8 MB | MetaSec 风控/安全 |
| `libttffmpeg.so` | 3.3 MB | FFmpeg 变体 |
| `libbytenn.so` | 2.1 MB | 字节神经网络 |
| `libime_ui_android_platform.so` | 1.0 MB | IME UI 平台层 |
| `liboime-config.so` | 0.7 MB | oime 配置 |
| `libEncryptor.so` | 74 KB | 加密辅助 |
| `libnpth*.so` | 多个 | 字节 APM/崩溃监控（npth） |

词库/模型资源主要在 `assets/dict/**`（单个可达 40MB+），另有 ASR、手写（hw）、皮肤 `assets/skin` 等。

## 建议的逆向路线（后续）

1. **环境**：安装 `jadx`、`apktool`、`aapt2`、`adb`、可选 `Ghidra`/`IDA`
2. **Java 层**：`jadx -d workspace/decompiled/jadx originals/apk/doubao-ime-original.apk`
3. **资源层**：`apktool d -o workspace/decompiled/apktool ...`
4. **确认加固**：检查 `libshell.so`、Application 是否壳入口、是否有 360/腾讯/字节自研壳特征
5. **Native 优先目标**：`libkeyboard.so`、`libime_net_sdk.so`、`liboime-config.so`、`libEncryptor.so`
6. **协议面**：抓包/静态找 `ime.doubao.com`、cronet、签名校验、更新通道
7. **输出**：阶段报告写入 `reports/`

## 当前状态

- [x] 原始 APK 入库并规范化命名（Git LFS）
- [x] 哈希与签名证书提取
- [x] 包名/组件/so/大资源粗粒度清单
- [x] 2026-07-18 完整性复验（见 `reports/01-upload-verification.md`）
- [x] jadx / apktool 反编译（本地 `workspace/decompiled/`，gitignore）
- [x] 加固判断：无传统 APK 壳；`libshell.so` 为输入引擎壳层（见 `reports/02-stage1-static-re.md`）
- [x] IME 入口 / KeyboardJni / 云端 API 初图
- [ ] 动态抓包与协议字段还原
- [ ] Ghidra 级 native 伪代码
- [ ] 词库格式文档

## 快速校验

```bash
sha256sum -c originals/hashes/doubao-ime-original.sha256
ls -lah originals/apk/
```
