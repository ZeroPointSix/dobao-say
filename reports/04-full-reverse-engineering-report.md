# 04 - 豆包输入法全面逆向分析报告

- **日期**：2026-07-19
- **分析人**：Cursor Agent（Cloud）
- **样本**：`originals/apk/doubao-ime-original.apk`（Git LFS）
- **工具**：aapt 33.0.2、jadx 1.5.1、apktool 2.11.1、readelf/nm/strings
- **状态**：静态分析完成；动态分析待续

---

## 目录

1. [执行摘要](#1-执行摘要)
2. [样本元数据](#2-样本元数据)
3. [整体架构](#3-整体架构)
4. [Java 层分析](#4-java-层分析)
5. [Native 层分析](#5-native-层分析)
6. [UI 与皮肤系统](#6-ui-与皮肤系统)
7. [输入链路与「丝滑连招」机制](#7-输入链路与丝滑连招机制)
8. [云端 API 地图](#8-云端-api-地图)
9. [本地资源与词库](#9-本地资源与词库)
10. [安全与保护机制](#10-安全与保护机制)
11. [权限与隐私面](#11-权限与隐私面)
12. [第三方 SDK 与基础设施](#12-第三方-sdk-与基础设施)
13. [关键类与文件索引](#13-关键类与文件索引)
14. [后续研究方向](#14-后续研究方向)
15. [附录](#15-附录)

---

## 1. 执行摘要

豆包输入法（`com.bytedance.android.doubaoime` v1.3.15）是一款**字节系自研 Android 输入法**，核心特征如下：

| 维度 | 结论 |
|------|------|
| **架构** | Java 壳 + JNI 桥（`KeyboardJni`）+ Native 引擎（`libshell.so` / `libkeyboard.so`）+ 自研 skin DSL UI |
| **加固** | **无传统 APK 加固壳**（360/乐固/梆梆等）；DEX 可直接 jadx 反编译 |
| **libshell.so** | **不是加固壳**，而是 oime 输入引擎壳层（`shell::ShellImpl`） |
| **网络** | QUIC 优先（`libime_net_sdk.so`）+ HTTP 回退（`libcurl.so` / Retrofit） |
| **ML** | 端侧 ONNX Runtime（`libonnxruntime.so` + `libbytenn.so`） |
| **丝滑感来源** | 双阶段候选刷新 + 云预取 + AssociateRefresh 防抖取消 + Native 直绘 |

**一句话**：这不是常规 Android XML 键盘，而是 **C++ 输入引擎 + 自研 XML UI DSL + 云增强联想** 的三层产品。

---

## 2. 样本元数据

### 2.1 文件与哈希

| 字段 | 值 |
|------|-----|
| 规范化路径 | `originals/apk/doubao-ime-original.apk` |
| 字节数 | 155,200,288（~149 MB） |
| MD5 | `9addf8dfb8bcf3f7c3781d429dc8890f` |
| SHA-256 | `c140d16625f1b8eddb21fa905f0e98a74b3e242d2638415801207c23c449d59a` |
| ZIP 条目数 | 2,778 |
| 未压缩合计 | ~298,822,171 bytes |

> APK 通过 Git LFS 存储；clone 后必须执行 `git lfs pull` 才能获取真包。

### 2.2 版本信息（aapt 确认）

| 字段 | 值 |
|------|-----|
| package | `com.bytedance.android.doubaoime` |
| versionName | `1.3.15` |
| versionCode | `100315010` |
| minSdkVersion | 26 |
| targetSdkVersion | 33 |
| compileSdkVersion | 33 |
| label | 豆包输入法 |
| native-code | `arm64-v8a` only |

### 2.3 内部版本串（非 aapt versionName）

| 字段 | 值 | 来源 |
|------|-----|------|
| oime 引擎版本 | `4.2.243.8-doubao` | dex 字符串 |
| 邻近版本 | `4.2.1-rc.8-oime`、`4.3.2-rc.13` | dex 字符串 |
| release_build | `2b0b44c_20260714_124829_7662229808918579238` | `assets/slardar.properties` |
| jenkins 任务 | `wave_ime_publish_pkg` | 同上 |

### 2.4 签名证书

| 字段 | 值 |
|------|-----|
| 签名文件 | `META-INF/BYTESIGN.RSA` / `.SF` / `MANIFEST.MF` |
| 主体 | 北京春田知韵科技有限公司 |
| 自签 | 是 |
| 有效期 | 2025-09-08 → 2050-09-08 |
| Cert SHA-256 | `D4:7C:AF:B4:50:B9:C8:19:11:80:40:27:95:F3:5C:E5:0C:E2:D8:B3:7F:13:00:B4:2E:7D:86:33:BC:86:B7:DF` |

### 2.5 DEX 层

| 文件 | 大小 | Magic |
|------|-----:|-------|
| `classes.dex` | 9,632,348 | `dex\n038\0` |
| `classes2.dex` | 9,063,168 | `dex\n038\0` |
| `classes3.dex` | 19,176 | `dex\n038\0` |

jadx 反编译产出约 **10,628** 个 `.java` 文件。

---

## 3. 整体架构

### 3.1 分层图

```text
┌─────────────────────────────────────────────────────────────┐
│  Android 应用层                                              │
│  ImeApplication → ImeService → InputView / InputViewRoot    │
│  叠加面板：LLM候选 / Emoji / 剪贴板 / AI写作 / 语音 / QuickReply │
└──────────────────────────┬──────────────────────────────────┘
                           │ inflate + 事件分发
┌──────────────────────────▼──────────────────────────────────┐
│  JNI 桥接层  KeyboardJni.java（178 个 native 方法）            │
│  loadLibrary: ime_ui_android_platform → keyboard → track      │
└──────────────────────────┬──────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────┐
│  Native 键盘层  libkeyboard.so                                │
│  Jni_* 入口 / controller::BoardController / CloudRequest      │
└──────────────────────────┬──────────────────────────────────┘
                           │ NEEDED
┌──────────────────────────▼──────────────────────────────────┐
│  输入引擎层  libshell.so                                      │
│  shell::ShellImpl / InputState / ConvertUtils / oime_engine   │
│  Pinyin9/26 / English26 / Wubi 状态机                       │
└──────────┬───────────────────────────────┬──────────────────┘
           │                               │
┌──────────▼──────────┐         ┌──────────▼──────────────────┐
│ libime_ui_android_  │         │ libonnxruntime.so           │
│ platform.so (绘制)   │         │ libbytenn.so (神经网络)      │
└─────────────────────┘         └─────────────────────────────┘
           │
┌──────────▼──────────────────────────────────────────────────┐
│  网络层  libime_net_sdk.so (QUIC) + libcurl.so (HTTP)        │
│  Java: ExecuteQUICPost → ime.doubao.com                     │
└─────────────────────────────────────────────────────────────┘
```

### 3.2 启动与输入生命周期

```text
ImeApplication.onCreate()
  ├─ InitScheduler / ImeKv / Environment
  ├─ initTTNet（非 Obric 系统时）
  └─ CLOUD_DOMAIN = https://ime.doubao.com

ImeService.onCreate()
  └─ KeyboardJni.SetImeService(this)

ImeService.onCreateInputView()
  └─ inflate ime_inputview.xml → InputView
       ├─ KeyboardView (soft_view)   # 主键盘 native skin
       └─ InputViewRoot              # 叠加面板容器

ImeService.onStartInputView()
  └─ KeyboardJni.startInputView(StartInputViewParams)
       └─ Jni_StartInputView → shell::ShellImpl

按键/触控
  └─ KeyboardView.nativeTouch(id, x, y, action, time)
       └─ shell::ShellImpl::Input → InputState::HandleInput
            └─ InsertAndConvert → DoConvert → UpdateResult

绘制
  └─ KeyboardView.onDraw → nativeDraw(id, dirtyRect)
```

### 3.3 工具栏状态机

`KeyboardJni.ToolbarState` 枚举控制候选/工具栏 UI 切换：

| 枚举值 | 皮肤文件 | 场景 |
|--------|----------|------|
| `kToolbar` | `input_toolbar_idle.xml` | 空闲工具栏 |
| `kCandList` | `input_toolbar_typing.xml` | 输入中候选 |
| `kQuickinput` | `input_toolbar_quickinput.xml` | 快捷输入（短信/剪贴板/符号） |
| `kTip` | `input_toolbar_tip.xml` | 提示条 |
| `kAiWriting` | `input_toolbar_ai_writing.xml` | AI 写作工具栏 |

---

## 4. Java 层分析

### 4.1 核心入口类

| 类 | 包路径 | 职责 |
|----|--------|------|
| `ImeApplication` | `com.bytedance.android.doubaoime` | Application 初始化、TTNet、环境 |
| `ImeService` | 同上 | `LifecycleInputMethodService`，IME 主服务 |
| `KeyboardJni` | 同上 | JNI 桥，178 个 native 方法 |
| `InputView` | `com.bytedance.android.input.keyboard` | 键盘容器 FrameLayout |
| `InputViewRoot` | `...keyboard.areacontrol` | 叠加面板根布局 |
| `KeyboardView` | `com.bytedance.android.input.keyboard` | Native 皮肤渲染 Surface |
| `LLMCandidate` | `com.bytedance.android.input.llm` | LLM 流式候选 |
| `QuickReply` | `com.bytedance.android.input.u` | 聊天场景 AI 快捷回复 |
| `ImeAssociateExperimentManager` | `com.bytedance.android.input.i` | 联想刷新实验/防抖 |

### 4.2 KeyboardJni native 方法分类（178 个）

| 分类 | 数量 | 代表方法 |
|------|-----:|----------|
| Toolbar/UI | 17 | `setToolbarState`, `setQuickInputData` |
| CandidateBar | 17 | `selectCand`, `getAssociations` |
| Commit/Selection | 16 | `commitString`, `directCommitCandByEngine` |
| Dictionary | 13 | `createCloudDictUpdateTask` |
| Config/Settings | 9 | `updateRemoteServerConfig` |
| ASR/Speech | 9 | ASR 相关回调 |
| Lifecycle/InputView | 9 | `startInputView`, `finishInputView` |
| Keyboard/InputMode | 8 | 模式切换 |
| ModelSchema | 8 | 端侧模型 schema 生命周期 |
| Handwriting | 7 | 手写板 |
| Association | 7 | `doAssociations`, `setAssociationEnabled` |
| LLM | 5 | `updateLLMCand`, `selectLLMCand` |
| Cloud/Network | 4 | `UpdateQUICConfig`, `setIpInfo` |
| 其他 | 21 | 追踪、反馈等 |

### 4.3 关键 Java 静态方法（非 native）

**`ExecuteQUICPost(String path, String body, int timeout)`** — Native 回调 Java 发起云端请求：

1. 解析 body 中 JSON `Data` 字段
2. 包装 `app_id`、`did`、`version`、`os_type`、可选 `abtest_param`
3. `wordasso` 路径使用独立超时（`association_second_refresh` 配置）
4. 通过 `com.bytedance.android.input.quic.b` → `libime_net_sdk.so` QUIC 发送
5. `translate` 路径在网络不可用时回退 HTTP `ExecutePost`

### 4.4 业务输入模式

`KeyboardInputMode` 枚举：`PY26` / `PY9` / `ENGLISH26` / `DOUBLE_SPELL` / `HANDWRITING`

---

## 5. Native 层分析

### 5.1 库依赖链

```text
libkeyboard.so
├── libshell.so
│   ├── libtrack.so
│   ├── liboime-config.so
│   ├── libonnxruntime.so
│   └── libc / liblog / libm / libdl / libandroid
├── libime_ui_android_platform.so
├── libcurl.so
├── libonnxruntime.so
├── libtrack.so
└── liboime-config.so

libime_net_sdk.so（独立 QUIC SDK）
└── liblog / libdl / libm / libc
```

### 5.2 libshell.so — 输入引擎核心

**关键 C++ API（`shell::ShellImpl`）**：

| 符号 | 职责 |
|------|------|
| `Init(ShellConfig)` | 引擎初始化 |
| `Input(...)` | 核心按键输入 |
| `DoConvert()` | 拼音→候选转换 |
| `Associate(string, int)` | 词联想 |
| `SelectAssociate(uint)` | 选择联想候选 |
| `MakeCloudRequest(string)` | 构建云 convert 请求 |
| `MakeLLMRequest(...)` | LLM 候选请求 |
| `SelectLLMCand(string)` | 选择 LLM 候选 |
| `EncodeEncrpty(string)` | 敏感字段加密 |
| `EngineTask(...)` | 异步引擎任务 |
| `FinishInputView()` | 结束输入会话 |

**状态机（`shell::InputState`）**：

- `HandleInput` → `InsertAndConvert` → `LoopDispatch`
- 模式：`Pinyin9` / `Pinyin26` / `English26` / `Wubi`

**转换工具（`shell::ConvertUtils`）**：

- `UpdateResult` / `SelectCand`
- `ParseCloudResponse` / `ParseCloudAndLLMResponse`
- `ParseCloudWordAssoResponse` / `ParseCloudWordAssociationPrefetchResponse`

### 5.3 libkeyboard.so — JNI 与云请求

| 符号 | 职责 |
|------|------|
| `Jni_InitKeyboard` | JNI 引擎初始化 |
| `Jni_StartInputView` / `Jni_FinishInputView` | 输入视图生命周期 |
| `Jni_CommitString` | 上屏 |
| `Jni_DoAssociations` | 执行联想 |
| `Jni_UpdateLLMCand` | 更新 LLM 候选 |
| `Jni_EncodeEncrpty` | JNI 加密入口 |
| `keyboard::CloudRequest::TryRequestServer` | 同步云请求 |
| `keyboard::CloudRequest::TryAsyncRequest` | 异步云请求 |
| `keyboard::AsyncCloudRequest::Request/CancelRequest` | 带取消的异步请求 |
| `controller::BoardController::Associate` | 从 Board 触发联想 |

### 5.4 全部 54 个 arm64-v8a .so（按体积排序 Top 15）

| 库 | 大小 (MB) | 判断 |
|----|----------:|------|
| `libonnxruntime.so` | 12.15 | ONNX 推理运行时 |
| `libkeyboard.so` | 8.96 | 键盘 JNI + 云请求 |
| `libaudioeffect.so` | 7.05 | 音频效果（语音） |
| `libshell.so` | 6.88 | **输入引擎核心** |
| `libime_net_sdk.so` | 6.52 | QUIC 网络 SDK |
| `libsscronet.so` | 6.06 | Chromium Cronet |
| `libcurl.so` | 5.10 | HTTP |
| `libmetasec_ml.so` | 3.80 | MetaSec 风控 |
| `libttffmpeg.so` | 3.32 | FFmpeg 变体 |
| `libbytenn.so` | 2.05 | 字节神经网络 |
| `libttvideouploader.so` | 1.26 | 视频上传 |
| `libttcrypto.so` | 1.18 | TLS 加密 |
| `libtrack.so` | 1.11 | 埋点追踪 |
| `libime_ui_android_platform.so` | 0.96 | UI 平台层 |
| `libc++_shared.so` | 0.87 | C++ 运行时 |

完整列表见附录 A。

---

## 6. UI 与皮肤系统

### 6.1 双层 UI 架构

1. **Native 皮肤引擎**（`libime_ui_android_platform.so` + `assets/skin/default/*.xml`）— 键盘本体、工具栏、候选栏
2. **Android View 层**（`res/layout/*`）— 容器、LLM 候选条、Emoji/剪贴板/AI 面板

### 6.2 界面层次

```text
ImeService.onCreateInputView()
└─ InputView (FrameLayout, layout/ime_inputview.xml)
   ├─ KeyboardView soft_view          # 主键盘（skin: input_main）
   ├─ KeyboardView translate_view     # 翻译键盘
   ├─ InputViewRoot (layout/ime_inputview_root.xml)
   │  ├─ keyboard_whole
   │  │  ├─ common_phrase_edit_view
   │  │  ├─ keyboard_whole_llm
   │  │  │  ├─ llm_view               # LLM 候选条
   │  │  │  └─ keyboard_main_container_area
   │  │  │     └─ native_candidate_bar  # 56dp，native 工具栏/候选
   │  │  └─ navigation_bar / drag_bar
   │  ├─ EmojiLayout
   │  ├─ Clipboard / CommonPhrase 面板
   │  ├─ AsrEditorLayoutView
   │  └─ 其它 overlay
   └─ AiPanelView
```

### 6.3 皮肤 DSL 目录

```text
assets/skin/default/
├── input_main.xml              # 主窗口：键盘页表
├── style.xml                   # 样式类（~60KB）
├── translate_main.xml
├── layout/
│   ├── input_kbd_pinyin26.xml  # 拼音 26 键
│   ├── input_kbd_pinyin9.xml   # 拼音 9 键
│   ├── input_kbd_english26.xml
│   ├── input_kbd_symbol.xml / more_symbol.xml
│   ├── input_kbd_number*.xml
│   ├── input_toolbar_idle.xml
│   ├── input_toolbar_typing.xml
│   ├── input_toolbar_quickinput.xml
│   └── input_toolbar_ai_writing.xml
├── values/colors.xml + dark_colors.xml
├── drawable/（logo、voice_space.gif 等）
└── 2x / 3x / land / floating / dark   # 分辨率与形态适配
```

### 6.4 主窗口键盘页表（`input_main.xml`）

`InputBoardLayout name=input_kbd_table` 内 index 切换：

| id | 布局 | 说明 |
|---:|------|------|
| 0 | `input_kbd_pinyin26` | 拼音 26 键 |
| 1 | `input_kbd_pinyin9` | 拼音 9 键 |
| 2 | `input_kbd_english26` | 英文全键 |
| 3 | `input_kbd_symbol` | 中文符号 |
| 4 | `input_kbd_more_symbol` | 更多符号 |
| 5 | `input_kbd_number` | 数字键盘 |
| 6 | `input_kbd_bihua` | 手写（skin 空壳，实际在 Android View） |
| 7 | `input_kbd_wubi` | 五笔（空壳占位） |
| 11 | `input_switch_keyboard` | 键盘选择页 |
| 13 | `input_settings` | 键盘内设置页 |

### 6.5 渲染与交互链路

```text
触控 → KeyboardView.nativeTouch(nativeId, x, y, action, time)
定时 → nativeTimerCallback
布局 → nativeOnLayout / nativeOnSize / nativeOnScale
绘制 → onDraw → nativeDraw(nativeId, dirtyRect)
字体 → skin 资产字体 或 res/font/oimeui2023|2025
```

品牌主色：`rgba(79,132,255)`（约 `#4F84FF`）。

---

## 7. 输入链路与「丝滑连招」机制

> APK 内**无「连招」字面功能名**。用户感知的「丝滑小连招」= **上屏 → 联想 → 再选候选 → 再联想** 的低延迟闭环。

### 7.1 完整时序

```text
用户按键 (action_down)
  ├─ [0ms]     key_click 埋点 (RenderPathTrack)
  ├─ [~0ms]    本地 convert 开始 (first_refresh_start)
  ├─ [~50ms]   本地候选上屏 (first_refresh_finish → draw finish)
  ├─ [并行]    云 convert/asso QUIC 请求发出
  ├─ [~200ms]  second_refresh_start（云联想 wordasso）
  ├─ [~500ms]  云联想候选刷新 (second_refresh_finish)
  │
用户选候选上屏 (commitString)
  ├─ OnCandidateCommitted → CancelRequest（取消过期云请求）
  ├─ Associate(index) → 本地联想 + 云 wordasso
  ├─ DelayProcessCloudWordAssociationResponse（防闪烁）
  ├─ prefetch_ctx_regulator 预取下一词 n-gram 上下文
  └─ 候选条更新 → 用户可立刻选下一个词 → 循环
```

### 7.2 双阶段候选刷新

`RenderPathTrack` 性能埋点将一次按键拆为两拍：

| 阶段 | 日志标记 | 作用 | 默认延迟 |
|------|----------|------|----------|
| First Refresh | `[c++][ui] start first_refresh` | 本地 convert，先画候选 | 200ms |
| Second Refresh | `[c++][ui] start second_refresh` | 云 wordasso 回来后再刷新 | 500ms |

配置类 `CandidateConfig`（`com.bytedance.android.input.basic.settings.api.c.d`）：

- `association_first_refresh` = 200（默认）
- `association_second_refresh` = 500（默认）
- `speedMode` = (50, 200, 500)
- `qualityMode` = (100, 200, 500)
- `isSupportAssociationOptV2` = true
- `isSupportCloudPreset` = true

### 7.3 云预取（Prefetch）

| 组件 | 路径/文件 | 作用 |
|------|-----------|------|
| `prefetch_ctx_regulator` | `/obric/ime/cloud/prefetch_ctx_regulator` | n-gram 上下文预调节 |
| `cloud_prefetch_word.dat` | assets | 本地预取词库 |
| `ConvertByCloudPrefetch` | libshell | 用预取结果做 convert |
| `llm_asso_prefetch` | libshell | LLM 联想预取 |

上屏时下一词联想**已在路上**，不需等用户选完才发请求。

### 7.4 AssociateRefresh 防抖与取消

`AssociateRefresh` 模块（libkeyboard.so 字符串 + Java `ImeAssociateExperimentManager`）：

1. **500ms 防抖** — `ImeAssociateExperimentManager.doAssociate` 使用 `Throttler(500ms)`
2. **`request_series_` 序列号** — 新请求发出即 `CancelRequest` 旧云请求
3. **`DelayProcessCloudWordAssociationResponse`** — commit 后延迟处理云联想，避免候选条闪烁
4. **`CandidateClickOpt`** — 候选点击优化，pending 状态管理
5. **聊天历史上下文** — `AssociateRefresh-HistoryInput` 按 App 包名去重保存输入历史，增强云联想

关键日志字符串：

```text
[AssociateRefresh] AsyncCloudRequest::CancelRequest
[AssociateRefresh] OnCandidateCommitted call CancelRequest
[AssociateRefresh] DelayProcessCloudWordAssociationResponse skip: is typing
[AssociateRefresh][CandidateClickOpt] DelayProcessCloudWordAssociationResponse pending after commit
```

### 7.5 快捷输入层（一键连招 UI）

`input_toolbar_quickinput.xml` 支持三种快捷输入：

| 模式 | 控件 | 功能 |
|------|------|------|
| 短信验证码 | `btn_quickinput_sms` | 读取 SMS 验证码一键上屏 |
| 剪贴板 | `btn_quickinput_clipboard` | 剪贴板内容横滑候选 |
| 最近符号 | `recent_symbol_list` | 最近使用符号快速点选（`sliderstopslowdown` 减速滑动） |

Java 层：`CandidateQuickInputView` + `QuickInputSmsView` + `QuickInputSymbolRecentView`

### 7.6 QuickReply（聊天场景连招）

- API：`POST /api/v1/quick_reply`
- 类：`com.bytedance.android.input.u.v`（QuickReply）
- 通过无障碍 `IRecognizer` 识别当前 App 是否为聊天场景
- 拉取 AI 生成的快捷回复列表，展示在候选/工具栏

### 7.7 LLM 候选流

- `LLMCandidate.updateCandidateList` → `KeyboardJni.updateLLMCand`
- API：`POST /api/v1/ai/process`（流式）
- Native：`shell::ShellImpl::MakeLLMRequest` / `SelectLLMCand`
- 与 convert 并行，second_refresh 阶段可能合并云+LLM 结果

---

## 8. 云端 API 地图

### 8.1 基础域名

- **主域名**：`https://ime.doubao.com`
- **语音 WebSocket**：`wss://frontier-audio-ime-quic.doubao.com`
- **推送配置**：`https://rocket.snssdk.com/service/2/...`

### 8.2 Native QUIC 云输入（`/obric/ime/cloud/*`）

| 路径 | app_id | 用途 |
|------|--------|------|
| `/obric/ime/cloud/convert` | `ime-cloud` | 云端拼音→候选转换 |
| `/obric/ime/cloud/asso` | `ime-cloud` | 云端联想 |
| `/obric/ime/cloud/wordasso` | `ime-cloud-word_association` | **词联想（连招核心）** |
| `/obric/ime/cloud/prefetch_ctx_regulator` | `ime-cloud-prefetch_ctx_regulator` | **预取上下文调节** |
| `/obric/ime/cloud/gethotword?version=0` | — | 热词下发 |
| `/obric/ime/cloud/getdelword?version=0` | — | 删除词同步 |

### 8.3 Java Retrofit REST API

| 路径 | 方法 | 用途 |
|------|------|------|
| `/api/v1/ai/process` | POST | AI / LLM 处理（流式） |
| `/api/v1/ailab/transform` | POST | AI Lab 变换 |
| `/api/v1/asr/fmt` | POST | ASR 格式化 |
| `/api/v1/asr/record` | POST | ASR 录音上报 |
| `/api/v1/bot/chat` | POST | AI 写作对话 |
| `/api/v1/bot/rich_chat` | POST | AI 写作（富文本） |
| `/api/v1/bug_report` | POST | 反馈 |
| `/api/v1/bug/file_push` | POST | 反馈文件上传 |
| `/api/v1/event/report` | POST | 事件上报 |
| `/api/v1/ip/info` | GET | IP 信息 |
| `/api/v1/keyboard/record` | POST | 键盘行为记录 |
| `/api/v1/quick_reply` | POST | **快捷回复** |
| `/api/v1/rectify_text` | POST | 纠错 |
| `/api/v1/topic_make` | POST | 话题生成 |
| `/api/v1/translate` | POST | 翻译 |
| `/api/v1/user/check_auth` | GET | 鉴权 |
| `/api/v1/user/get_config` | GET | 用户配置 |
| `/api/v1/ws` | WS | 语音 frontier |
| `/api/v2/config/onboarding_page` | GET | 引导页配置 |
| `/api/v2/emoticons/*` | GET/POST | 表情包 |
| `/api/v2/im/message/*` | — | 消息中心 |
| `/api/v2/version/list` | GET | 版本检查 |
| `/api/v3/context/ime/modify_pair` | — | ASR 修改对 |
| `/api/v3/context/ime/ner` | — | NER 上下文 |
| `/api/v3/context/ime/user_words` | — | 用户词上下文 |
| `/service/settings/v3/` | POST | 远程设置 |

### 8.4 请求包装格式（ExecuteQUICPost）

```json
{
  "app_id": "ime-cloud-word_association",
  "version": "<elapsedRealtime>",
  "Data": "<native 原始 payload>",
  "did": "<deviceId>",
  "app_version": "1.3.15",
  "os_type": "Android",
  "app_id": "401734",
  "abtest_param": {
    "asso_args": "...",
    "enable_asso_history": true
  }
}
```

---

## 9. 本地资源与词库

### 9.1 assets/dict（约 153 MB）

| 类别 | 路径 | 说明 |
|------|------|------|
| 主词库 | `assets/dict/c48352298dbd`（46.6 MB）等 | 系统词库（哈希命名） |
| 手写 | `assets/dict/hw/android/*` | 手写识别模型 |
| ASR | `assets/dict/asr/*` | 语音识别模型 |
| 预取词库 | `cloud_prefetch_word.dat` | 云预取词 |

### 9.2 其它 assets

| 路径 | 说明 |
|------|------|
| `assets/skin/` | 皮肤 DSL |
| `assets/lottie/` | Lottie 动效 |
| `assets/symbol/` | 符号表 |
| `default_translation_zh-CN.json` | 默认翻译 |
| `slardar.properties` | 构建元数据 |
| `grs_*` / `hms*.bks` | 华为 GRS/HMS 配置 |

### 9.3 端侧 ML

- `libonnxruntime.so`（12.15 MB）— 本地推理
- `libbytenn.so`（2.05 MB）— 字节神经网络
- 用于：手写识别、ModelSchema、可能 NER

---

## 10. 安全与保护机制

### 10.1 加固判断：无传统 APK 壳

| 证据 | 说明 |
|------|------|
| DEX 可读 | 3 个 dex 均为 DEX 038，jadx 直接反编译 |
| Application 无壳逻辑 | `ImeApplication.attachBaseContext()` 直接 `super`，无 DexClassLoader |
| 无壳特征 | 未检出 jiagu/legu/bangcle/ijiami 等 |

### 10.2 安全组件（非壳）

| 组件 | 作用 |
|------|------|
| `libmetasec_ml.so` + `MSB` | MetaSec 设备指纹 / 请求签名（`x-metasec-*` header） |
| `libEncryptor.so` + `EncryptorUtil.ttEncrypt` | 字节通用加密 |
| `KeyboardJni.encodeEncrpty` → `ShellImpl::EncodeEncrpty` | 反馈/ASR 敏感字段加密 |
| `libttcrypto.so` / `libttboringssl.so` | TLS |
| `libshadowhook.so` / `libbytehook.so` | 内联 hook（监控/反调试嫌疑） |
| `libnpth*.so`（14 个） | 字节 APM/崩溃监控 |

### 10.3 网络安全配置

- `network_security_config.xml`：`cleartextTrafficPermitted=true`，`overridePins=true`
- 便于抓包调试，生产环境也较宽松

---

## 11. 权限与隐私面

### 11.1 敏感权限

| 权限 | 用途（静态判断） |
|------|------------------|
| `RECORD_AUDIO` | 语音输入 |
| `READ_SMS` + `RECEIVE_VERIFY_CODE_SMS` | 短信验证码快捷输入 |
| `READ_CONTACTS` | 联系人词库 |
| `SYSTEM_ALERT_WINDOW` | 悬浮窗 |
| `DETECT_SCREEN_RECORDING` | 检测录屏 |
| `REQUEST_INSTALL_PACKAGES` | 应用内更新 |
| `POST_NOTIFICATIONS` | 推送通知 |
| `BLUETOOTH` / `BLUETOOTH_ADMIN` | 蓝牙设备（语音？） |
| `WRITE_SETTINGS` | 系统设置 |
| `FOREGROUND_SERVICE` | 前台服务 |

### 11.2 无障碍服务

- `ImeAccessibilityService` — 用于场景识别（聊天 App 检测 → QuickReply）、可能辅助输入

### 11.3 数据上报

- 键盘行为：`/api/v1/keyboard/record`
- 事件：`/api/v1/event/report`
- Slardar APM + NPTH 崩溃

---

## 12. 第三方 SDK 与基础设施

| SDK/组件 | 库/包 | 用途 |
|----------|-------|------|
| TTNet / Cronet | `libsscronet.so` | 网络栈 |
| QUIC NetSDK | `libime_net_sdk.so` | 低延迟云输入 |
| MetaSec MSSDK | `libmetasec_ml.so` | 安全签名 |
| ONNX Runtime | `libonnxruntime.so` | 端侧 ML |
| Keva | `libkeva.so` | KV 存储 |
| Fresco | `libimagepipeline.so` 等 | 图片加载 |
| Lottie | Java 依赖 | 动效 |
| 华为 HMS Push | manifest 组件 | 推送 |
| 小米 MiPush | manifest 组件 | 推送 |
| vivo / OPPO Push | manifest 组件 | 推送 |
| NPTH | `libnpth*.so` | 崩溃监控 |
| ShadowHook | `libshadowhook.so` | Hook 框架 |

---

## 13. 关键类与文件索引

### 13.1 逆向优先阅读清单

| 优先级 | 文件/路径 | 原因 |
|--------|-----------|------|
| P0 | `KeyboardJni.java` | 全部 native 入口 |
| P0 | `libshell.so`（Ghidra） | 输入引擎核心 |
| P0 | `libkeyboard.so`（Ghidra） | JNI + 云请求 |
| P1 | `assets/skin/default/input_main.xml` | 键盘页表 |
| P1 | `assets/skin/default/layout/input_toolbar_*.xml` | 工具栏状态 |
| P1 | `InputView.java` / `KeyboardView.java` | Android 容器 |
| P1 | `ImeAssociateExperimentManager` | 联想刷新策略 |
| P2 | `LLMCandidate.kt` | LLM 候选流 |
| P2 | `QuickReply.kt` | 快捷回复 |
| P2 | `libime_net_sdk.so` | QUIC 协议 |

### 13.2 Manifest 核心组件

| 类型 | 类名 |
|------|------|
| Application | `com.bytedance.android.doubaoime.ImeApplication` |
| IME Service | `com.bytedance.android.doubaoime.ImeService` |
| 设置入口 | `com.bytedance.android.doubaoime.activity.LauncherActivity` |
| 无障碍 | `...recognition.accessibilityImpl.ImeAccessibilityService` |
| ContentProvider | `ImeContentProvider` / `SettingsContentProvider` |

---

## 14. 后续研究方向

### 14.1 高优先级

1. **动态抓包**：hook `ExecuteQUICPost` / `ImeNetSDK.call`，还原 `wordasso` / `prefetch_ctx_regulator` 请求体 protobuf/JSON 字段
2. **Ghidra 分析 `libshell.so`**：从 `ShellImpl::MakeCloudRequest` / `ParseCloudResponse` / `Associate` 下断
3. **词库格式**：抽样解析 `assets/dict/c48352298dbd`（46.6 MB）文件头与索引结构

### 14.2 中优先级

4. **LLM 链路**：`LLMRequest` → `/api/v1/ai/process` 流式响应解析
5. **鉴权**：`check_auth` + MetaSec `x-metasec-*` header 生成逻辑
6. **无障碍场景识别**：`IRecognizer` 如何判断聊天 App

### 14.3 产品复刻参考

| 能力 | 实现要点 |
|------|----------|
| 双阶段刷新 | 本地先出 + 200ms 云请求 + 500ms 合并 |
| 云预取 | 上屏时异步 prefetch n-gram 上下文 |
| 防抖取消 | requestId 递增 + 回调校验 |
| 联想链 | commit 后立即 associate |
| 快捷输入 | 剪贴板/验证码/符号横滑条 |
| 渲染 | Native Canvas 直绘候选条 |

---

## 15. 附录

### 附录 A：全部 54 个 arm64-v8a .so

| 库 | 大小 (bytes) |
|----|-------------:|
| libalog.so | 121,208 |
| libaudioeffect.so | 7,396,920 |
| libbdzstd.so | 227,152 |
| libbytehook.so | 59,016 |
| libbytenn.so | 2,152,440 |
| libc++_shared.so | 911,696 |
| libcurl.so | 5,348,152 |
| libEncryptor.so | 75,744 |
| libgifimage.so | 39,408 |
| libiesapplogger.so | 67,616 |
| libimagepipeline.so | 10,328 |
| libime_net_sdk.so | 6,835,944 |
| libime_ui_android_platform.so | 1,002,088 |
| libkeva.so | 412,576 |
| libkeyboard.so | 9,399,064 |
| libmetasec_ml.so | 3,989,328 |
| libmonitorcollector-lib.so | 313,744 |
| libnative-filters.so | 26,680 |
| libnative-imagetranscoder.so | 448,616 |
| libnewep.so | 43,040 |
| libnpth.so | 178,264 |
| libnpth_bt.so | 7,344 |
| libnpth_dl.so | 27,352 |
| libnpth_dumper.so | 104,520 |
| libnpth_fd_tracker.so | 39,176 |
| libnpth_fp_unw.so | 6,112 |
| libnpth_heap_tracker.so | 67,056 |
| libnpth_logcat.so | 16,856 |
| libnpth_ref_monitor.so | 27,792 |
| libnpth_repair.so | 27,856 |
| libnpth_tls_monitor.so | 15,904 |
| libnpth_unwind.so | 217,144 |
| libnpth_unw.so | 24,536 |
| libnpth_vm_monitor.so | 52,688 |
| libnpth_xasan.so | 40,736 |
| liboime-config.so | 751,816 |
| libonnxruntime.so | 12,735,104 |
| libprofiler.so | 471,424 |
| libshadowhook.so | 79,440 |
| libshadowhook_nothing.so | 1,112 |
| libshell.so | 7,211,864 |
| libsliver.so | 141,424 |
| libsscronet.so | 6,353,248 |
| libstatic-webp.so | 396,080 |
| libtailor.so | 63,584 |
| libtrack.so | 1,161,904 |
| libttboringssl.so | 380,208 |
| libttcrypto.so | 1,240,008 |
| libttffmpeg.so | 3,484,752 |
| libttmverify.so | 9,920 |
| libttmverifylite.so | 26,480 |
| libttvideouploader.so | 1,325,136 |
| libvcn.so | 210,912 |
| libvcnverify.so | 9,992 |

### 附录 B：分析环境

| 工具 | 版本 |
|------|------|
| jadx | 1.5.1 |
| apktool | 2.11.1 |
| aapt | build-tools 33.0.2 |
| Java | 17 |
| OS | Linux 6.12 |

### 附录 C：参考文献

- 本仓库 `00` ~ `03` 阶段报告
- [dobao-say/sample-apk 分支](https://github.com/ZeroPointSix/dobao-say/tree/sample-apk)

---

*报告结束。如需动态分析或 Ghidra 伪代码，请参阅第 14 节后续方向。*
