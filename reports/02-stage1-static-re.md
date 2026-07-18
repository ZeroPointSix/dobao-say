# 02 - 第一阶段逆向报告（静态）

- 日期：2026-07-18
- 样本：`originals/apk/doubao-ime-original.apk`
- 工具：`aapt`、`jadx 1.5.1`、`apktool 2.11.1`、`readelf/nm/strings`
- 结论摘要：
  1. **未发现传统 APK 加固壳**（360/腾讯乐固/梆梆等）；DEX 可直接反编译。
  2. `libshell.so` **不是加固壳**，而是 **oime 输入引擎壳层**（`shell::ShellImpl`）。
  3. Java ↔ Native 主桥是 `KeyboardJni`，静态加载 `ime_ui_android_platform` → `keyboard`（连带 `libshell.so`）→ `track`。
  4. 云端域名为 `https://ime.doubao.com`，同时存在 QUIC 云输入路径与 Retrofit REST API。

## 1. 正式版本信息（aapt）

| 字段 | 值 |
|---|---|
| package | `com.bytedance.android.doubaoime` |
| versionName | `1.3.15` |
| versionCode | `100315010` |
| minSdk | 26 |
| targetSdk / compileSdk | 33 |
| label | 豆包输入法 |
| Application | `com.bytedance.android.doubaoime.ImeApplication` |
| IME Service | `com.bytedance.android.doubaoime.ImeService`（`BIND_INPUT_METHOD`） |
| settingsActivity | `...activity.LauncherActivity`（`res/xml/method.xml`） |

> 此前从 dex 字符串看到的 `4.2.243.8-doubao` 更像内部 oime/引擎版本串，不是 aapt 正式 versionName。

构建标记（`assets/slardar.properties`）：

- `release_build=2b0b44c_20260714_124829_7662229808918579238`
- `jekins_name=wave_ime_publish_pkg`

## 2. 加固 / 保护判断

### 2.1 不是 APK 壳

证据：

- `classes.dex` / `classes2.dex` / `classes3.dex` 均为可读 DEX 038；jadx 产出约 **10628** 个 `.java`。
- `ImeApplication.attachBaseContext()` 直接 `super.attachBaseContext` + 业务初始化，**无** `DexClassLoader` 解壳逻辑。
- 未检出 jiagu/legu/bangcle/ijiami 等典型壳特征。

### 2.2 仍有安全组件（非壳）

| 组件 | 作用 |
|---|---|
| `libmetasec_ml.so` + `com.bytedance.mobsec.metasec.ml.MSB` | MetaSec 风控/设备指纹；`LIBNAME=metasec_ml` |
| `libEncryptor.so` + `EncryptorUtil.ttEncrypt` | 字节通用加密辅助 |
| `KeyboardJni.encodeEncrpty` → `shell::ShellImpl::EncodeEncrpty` | 反馈/ASR bug 描述等字段加密 |
| `network_security_config` | `cleartextTrafficPermitted=true`，且 `overridePins=true`（便于抓包，但生产也较松） |

## 3. 运行时架构（静态还原）

```text
ImeApplication
  ├─ InitScheduler / ImeKv / Environment
  ├─ initTTNet（非 Obric 系统时）
  └─ CLOUD_DOMAIN = https://ime.doubao.com

ImeService (LifecycleInputMethodService)
  ├─ onCreate → KeyboardJni.SetImeService(this)
  ├─ onCreateInputView → KeyboardView / InputView
  └─ onStartInputView → KeyboardJni.startInputView(...)

KeyboardJni (Java bridge, ~178 native methods)
  loadLibrary:
    ime_ui_android_platform → keyboard(+shell) → track
  关键能力:
    输入状态机 / 候选 / 联想 / 手写 / ASR 协作 /
    云请求回调 ParseCloud* / LLM 候选 / encodeEncrpty /
    ExecuteQUICPost(path, body, timeout)

Native:
  libkeyboard.so  ──NEEDED──► libshell.so (shell::ShellImpl / InputState / Pinyin*Mode)
       │                         ├─ onnxruntime（本地模型）
       │                         └─ oime-config / track
       └─ curl / onnxruntime / ime_ui_android_platform

  libime_net_sdk.so ← ImeNetSDK / Streaming（QUIC）
```

### 3.1 `libshell.so` 正名

导出符号可见完整 C++ API，例如：

- `shell::ShellImpl::Attach / MakeCloudRequest / MakeLLMRequest / ParseCloudResponse`
- `shell::ShellImpl::StartHandWriting / SelectLLMCand / EncodeEncrpty`
- `shell::InputState` + `Pinyin9/Pinyin26/English26/Wubi` 状态机
- 大量 `Engine::LoadSysDict::*` / 用户词库加载失败日志字符串

因此后续 native 逆向应把 **`libshell.so` + `libkeyboard.so`** 当作输入核心，而不是当壳去脱。

## 4. 云端接口地图（已静态确认）

### 4.1 Native/QUIC 云输入（`ExecuteQUICPost` / so 字符串）

| 路径 | 用途（静态判断） |
|---|---|
| `/obric/ime/cloud/convert` | 云端转换/候选 |
| `/obric/ime/cloud/asso` | 联想 |
| `/obric/ime/cloud/wordasso` | 词联想 |
| `/obric/ime/cloud/prefetch_ctx_regulator` | 预取上下文调节 |
| `/obric/ime/cloud/gethotword?version=0` | 热词 |
| `/obric/ime/cloud/getdelword?version=0` | 删除词同步 |
| `https://ime.doubao.com/api/v1/translate` | 翻译 |
| `https://ime.doubao.com/api/v1/sdk/dns_analysis?domain=` | DNS 分析 |

### 4.2 Java Retrofit（`IRecommends` 等，host=`https://ime.doubao.com`）

| 路径 | 接口线索 |
|---|---|
| `/api/v1/ai/process` | AI / LLM 处理（`LLMApi` / Streaming） |
| `/api/v1/ailab/transform` | AI Lab 变换 |
| `/api/v1/asr/fmt` | ASR 格式化 |
| `/api/v1/asr/record` | ASR 录音上报 |
| `/api/v1/keyboard/record` | 键盘行为记录 |
| `/api/v1/user/check_auth` | 鉴权检查 |
| `/api/v1/translate` | 翻译 |
| `/api/v1/bug_report` / `/api/v1/bug/file_push` | 反馈 |
| `/api/v1/ip/info` | IP 信息 |
| `/api/v1/event/report` | 事件上报 |
| `/api/v1/rectify_text` | 纠错 |
| `/api/v1/quick_reply` | 快捷回复 |
| `/api/v1/topic_make` | 话题 |
| `/api/v1/bot/chat` / `/api/v1/bot/rich_chat` | AI 写作/对话流 |
| `/api/v2/config/onboarding_page?platform=android` | 引导页配置 |

另有 push 配置域名线索：`https://rocket.snssdk.com/service/2/...`

## 5. 敏感权限与能力

已由 aapt 确认（节选）：

- `RECORD_AUDIO`（语音输入）
- `READ_SMS` / 验证码相关（`getSmsCodeNumber` native）
- `READ_CONTACTS`（联系人词库）
- `SYSTEM_ALERT_WINDOW` / `REQUEST_INSTALL_PACKAGES`
- `DETECT_SCREEN_RECORDING`
- 多厂商 Push（华为/小米/vivo/OPPO）

## 6. 本地资源

`assets/dict/**` 体积很大（单目录可达 45MB），含系统词库、手写 `hw`、ASR 等；`libonnxruntime.so` + `libbytenn.so` 支撑本地推理。

## 7. 产出物位置

| 路径 | 说明 |
|---|---|
| `workspace/decompiled/jadx/` | Java 反编译（gitignore，本地可再生） |
| `workspace/decompiled/apktool/` | 资源/smali（gitignore） |
| `workspace/native/arm64-v8a/` | 抽出的 so（gitignore `*.so`） |
| `workspace/notes/aapt-badging.txt` | 正式版本信息 |
| `workspace/notes/KeyboardJni-native-methods.txt` | 178 个 native 方法 |
| `workspace/notes/cloud-api-endpoints.txt` | native 侧云路径 |
| `workspace/notes/native-dep-chain.txt` | so 依赖链 |
| `workspace/notes/native/libshell-ShellImpl-apis.txt` | ShellImpl 导出 API |

## 8. 下一阶段建议（按收益）

1. **协议面**：对 `ExecuteQUICPost` / `ImeNetSDK` 做动态抓包（TTNet/QUIC），还原 `convert/asso/wordasso` 请求体与签名字段。
2. **Native 核心**：Ghidra/IDA 打开 `libshell.so`，从 `ShellImpl::MakeCloudRequest` / `ParseCloudResponse` / `EncodeEncrpty` 下断。
3. **LLM 链路**：跟 `LLMRequest` → `IRecommends.LLMApi` `/api/v1/ai/process` 的参数拼装与流式响应。
4. **鉴权**：梳理 `check_auth`、MetaSec header（`x-metasec*`）与 `SetAndroidID` native 入参。
5. **词库格式**：抽样解析 `assets/dict/*` 最大几个文件的文件头/索引结构。

## 9. 当前状态勾选

- [x] aapt 正式版本与权限
- [x] jadx / apktool 反编译
- [x] 加固判断（结论：无传统壳）
- [x] IME 入口与 KeyboardJni / libshell 关系
- [x] 云端 API 初图
- [ ] 动态抓包与协议字段还原
- [ ] Ghidra 级 native 伪代码
- [ ] 词库格式文档
