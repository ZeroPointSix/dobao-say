# 03 - UI 界面结构逆向

- 日期：2026-07-18
- 重点：键盘主界面层次、皮肤 XML、工具栏/候选栏、模式切换、Android 叠加面板
- 样本：豆包输入法 `1.3.15`（`com.bytedance.android.doubaoime`）

## 1. 一句话结论

豆包输入法 UI 是 **双层架构**：

1. **Native 皮肤引擎**（`libime_ui_android_platform.so` + `assets/skin/default/*.xml`）负责画键盘本体、工具栏、候选栏、按键布局。
2. **Android View 层**（`InputView` / `InputViewRoot` / `res/layout/*`）负责容器、LLM 候选条、Emoji/剪贴板/AI 面板/语音编辑区等叠加面板。

主渲染路径：`KeyboardView.onDraw()` → `nativeDraw(mNativeViewId, dirtyRect)`，皮肤描述在 `assets/skin/default/input_main.xml`。

## 2. 界面总层次

```text
ImeService.onCreateInputView()
└─ InputView (FrameLayout, layout/ime_inputview.xml)
   ├─ KeyboardView soft_view     # 主键盘（native skin: input_main）
   ├─ KeyboardView translate_view# 翻译键盘（另一套 native window）
   ├─ InputViewRoot (layout/ime_inputview_root.xml)
   │  ├─ keyboard_whole
   │  │  ├─ common_phrase_edit_view      # 常用语编辑（默认 gone）
   │  │  ├─ keyboard_whole_llm
   │  │  │  ├─ llm_view                  # LLM 候选条（默认 gone）
   │  │  │  └─ keyboard_main_container_area
   │  │  │     └─ native_candidate_bar   # 56dp，承载 native 工具栏/候选
   │  │  └─ navigation_bar / drag_bar    # 悬浮键盘拖拽条
   │  ├─ EmojiLayout
   │  ├─ Clipboard / CommonPhrase 面板
   │  ├─ AsrEditorLayoutView（语音编辑）
   │  └─ 其它 overlay（AI 面板由 InputView 直接挂载）
   └─ AiPanelView（AI 写作/助手面板）
```

## 3. Native 皮肤系统（核心 UI）

### 3.1 目录

```text
assets/skin/default/
├── input_main.xml          # 主窗口：键盘页表
├── style.xml               # 字体/样式类（超大）
├── translate_main.xml
├── layout/
│   ├── input_kbd_pinyin26.xml
│   ├── input_kbd_pinyin9.xml
│   ├── input_kbd_english26.xml
│   ├── input_kbd_symbol.xml / more_symbol.xml
│   ├── input_kbd_number*.xml
│   ├── input_kbd_bihua.xml      # 手写占位 BoardLayout
│   ├── input_kbd_wubi.xml       # 五笔占位
│   ├── input_switch_keyboard.xml
│   ├── input_more_cand.xml
│   ├── input_settings.xml
│   ├── input_toolbar_idle.xml
│   ├── input_toolbar_typing.xml
│   ├── input_toolbar_quickinput.xml
│   ├── input_toolbar_tip.xml
│   ├── input_toolbar_ai_writing.xml
│   └── input_translate.xml
├── values/colors.xml + dark_colors.xml + transparent_colors.xml
├── drawable/（logo、voice_space.gif 等）
├── 2x / 3x / 3.5x / land / floating / dark   # 分辨率与形态适配
```

加载前缀由 `EnvironmentImpl` 处理：资源路径自动补 `skin/default/`。  
字体：`misans_*` 从 assets；图标字体 `oimeui2023` / `oimeui2025` 从 `res/font`。

### 3.2 主窗口键盘页表（`input_main.xml`）

`InputBoardLayout name=input_kbd_table` 内切换（index）：

| id | 布局 | 说明 |
|---:|---|---|
| 0 | `input_kbd_pinyin26` | 拼音 26 键 |
| 1 | `input_kbd_pinyin9` | 拼音 9 键 |
| 2 | `input_kbd_english26` | 英文全键（默认 selectedid=2 仅初始占位，业务模式另控） |
| 3 | `input_kbd_symbol` | 中文符号 |
| 4 | `input_kbd_more_symbol` | 更多符号 |
| 5 | `input_kbd_number` | 数字键盘 |
| 6 | `input_kbd_bihua` | 手写（skin 内为空壳，实际手写板在 Android View） |
| 7 | `input_kbd_wubi` | 五笔（空壳占位） |
| 8/9/10 | number_symbol_* / number_from26 | 数字符号变体 |
| 11 | `input_switch_keyboard` | 键盘选择页 |
| 13 | `input_settings` | 键盘内设置页 |

另有浮层：`FusiyinBubbleLayout`（长按多音/符号气泡）、`ButtonTouchTip`（按键触感提示）。

### 3.3 拼音 26 键底栏（用户可见主键盘）

底行 `26key_bottom_line` 从左到右：

1. `123`（切数字/符号板）
2. `，。` 合并键（长按气泡）
3. `/` 符号适配键
4. **空格**（`ButtonSpace`，可叠 `voice_space.gif` 语音态）
5. `.` 符号键
6. **中/英切换**（`ButtonSwitchChineseEnglish`）
7. **回车**（`ButtonEnter`）

上排还有：分词键、退格；字母键普遍带 `ficon_text` 上标符号与长按气泡（fusiyin）。

### 3.4 拼音 9 键

左侧：`FilterListLayout`（`. / www. .com .cn http ...`）+「符号」；  
中间：九键字母区；右侧功能列（退格等）。可滑动过滤。

### 3.5 工具栏状态（KeyboardJni.ToolbarState）

Java 枚举：

- `kToolbar`：空闲工具栏（`input_toolbar_idle.xml`）
- `kCandList`：输入中候选（`input_toolbar_typing.xml`）
- `kQuickinput`：快捷输入
- `kTip`：提示条
- `kAiWriting`：AI 写作工具栏

**空闲工具栏按钮（idle）**：Logo/设置、点击说话、剪贴板、翻译、Emoji、常用语、选键盘、繁简、单手、手写查字、AI 入口（默认 hidden）、隐藏键盘等。

**输入中工具栏（typing）**：

- 上行：拼音/预编辑 `Composition`（`comp_half` / `comp_right`）
- 下行：`CandListLayout` 候选列表 + 更多候选按钮 + 可选语音候选键

**AI 写作工具栏**：关闭 / 检查 / 重写 / 总结。

### 3.6 视觉主题

品牌主色（亮色）：`theme_color = rgba(79,132,255)`（约 `#4F84FF`）  
键盘底：`default_bk = #EBEBEB`；按键白底；功能键灰。  
暗色：`default_bk = #3D3D3D`，文字近白，按键半透明白。  
首候选高亮同主题蓝。

## 4. Android 容器与叠加 UI

### 4.1 入口

`ImeService` inflate `R.layout.ime_inputview` → `InputView`；  
`InputViewRoot` inflate `R.layout.ime_inputview_root`。

`native_candidate_bar` 高度约 **56dp**，给 native 工具栏/候选占用。

### 4.2 主要叠加面板（Android layout）

| 能力 | 代表 layout / 类 |
|---|---|
| Emoji | `emoji_layout.xml` / `EmojiLayout` |
| 剪贴板 | `layout_toolbar_clipboard*.xml` / Clipboard*View |
| 常用语 | `common_phrase_*` / CommonPhrase* |
| LLM 候选 | `llm_candidate_container.xml` |
| AI 写作 | `layout_aiwrite_*.xml` / `AiPanelView` |
| AI 聊天助手 | `fragment_ai_chat_assistant.xml` + `layout_aichat_*` |
| 语音输入设置/动作 | `fragment_voice_input_*.xml` / `AsrEditorLayoutView` |
| 悬浮键盘 | `floating_keyboard_tip_view.xml`；尺寸约 288×211dp |
| 单手 | `ime_onehand_left/right.xml` |
| 设置页 | `fragment_settings_main.xml`、`fragment_keyboard_layout_root.xml` 等 |

业务输入模式枚举 `KeyboardInputMode`：`PY26 / PY9 / ENGLISH26 / DOUBLE_SPELL / HANDWRITING`。

## 5. 渲染与交互链路

```text
触控 → KeyboardView.nativeTouch(nativeId, x, y, action, time)
定时 → nativeTimerCallback
布局 → nativeOnLayout / nativeOnSize / nativeOnScale
绘制 → onDraw → nativeDraw(nativeId, dirtyRect)
字体 → skin/default 资产字体 或 res/font/oimeui2023|2025
```

`InputView` 内同时持有两套 `KeyboardView`：

- `soft_view`：主输入
- `translate_view`：翻译模式

## 6. 对“界面还原/复刻”最有价值的文件

优先读：

1. `assets/skin/default/input_main.xml`
2. `layout/input_kbd_pinyin26.xml` / `pinyin9.xml` / `english26.xml`
3. `layout/input_toolbar_idle.xml` / `typing.xml`
4. `res/layout/ime_inputview_root.xml`
5. `KeyboardView.java` + `InputView.java` + `InputViewRoot.java`
6. `values/colors.xml` + `dark_colors.xml`

## 7. 当前判断（产品视角）

- 键盘本体不是常规 Android XML 逐键布局，而是 **自研 XML UI DSL + Native 绘制**。
- Android 层主要做壳与“面板业务”（AI/Emoji/剪贴板/语音）。
- 要复刻主键盘观感：应解析 skin DSL（ButtonChar/ButtonSpace/CandListLayout 等）与 style 类，而不是只抄 `res/layout`。
- 手写/五笔在 skin 里是空 `BoardLayout`，真实手写板在 Java `HandWritingBoardView` 等组件。

## 8. 下一刀（UI 方向）

1. 完整枚举 `style.xml` 中 `cls_keyboard_button*` 尺寸/圆角/阴影参数（可直接出 UI token）。
2. 从 `libime_ui_android_platform.so` 导出符号，确认 XML 控件类注册表。
3. 动态截图对照：idle / typing / 9键 / 英文 / AI 面板。
4. 若要做 clean-room 自研键盘，按 soft_view 页表 + toolbar 状态机实现最小可运行 UI。
