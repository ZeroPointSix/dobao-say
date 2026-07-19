# 00 - 样本清单与初探

- 日期：2026-07-18
- 样本：豆包安卓输入法原始 APK
- 状态：已入库（Git LFS），完整性已复验；未做完整反编译
- 复验报告：`reports/01-upload-verification.md`

## 1. 文件与哈希

| 字段 | 值 |
|---|---|
| 用户投放路径 | `豆包输入法.apk`（仓库根） |
| 规范化副本 | `originals/apk/doubao-ime-original.apk` |
| 字节数 | 155200288 |
| MD5 | `9addf8dfb8bcf3f7c3781d429dc8890f` |
| SHA-256 | `c140d16625f1b8eddb21fa905f0e98a74b3e242d2638415801207c23c449d59a` |
| 容器 | Zip archive（APK） |
| 条目数 | 2778 |
| 未压缩合计 | ~298,822,171 bytes |

> 根目录原始文件与 `originals/apk/` 副本应保持一致；分析请基于 `originals/`，避免污染基线。

## 2. 身份信息

### 2.1 包与版本（静态线索）

| 字段 | 值 | 来源 |
|---|---|---|
| package | `com.bytedance.android.doubaoime` | binary AndroidManifest 字符串 |
| versionName（正式） | `1.3.15` | `aapt dump badging` |
| versionCode | `100315010` | 同上 |
| 内部引擎版本串（疑似） | `4.2.243.8-doubao` | `classes.dex` 字符串池 |
| 邻近版本串 | `4.2.1-rc.8-oime`、`4.3.2-rc.13` | 同上 |
| release_build | `2b0b44c_20260714_124829_7662229808918579238` | `assets/slardar.properties` |
| jekins_name | `wave_ime_publish_pkg` | 同上 |
| 业务域名 | `https://ime.doubao.com` | dex 字符串 |

说明：`versionName` / `versionCode` 已由 `aapt dump badging` 确认；dex 中的 `4.2.243.8-doubao` 等为内部引擎版本串，反编译后可对照 `BuildConfig` / manifest。

### 2.2 签名证书

| 字段 | 值 |
|---|---|
| 签名文件 | `META-INF/BYTESIGN.RSA` / `.SF` / `MANIFEST.MF` |
| 算法线索 | PKCS7 SignedData + sha256 / sha256WithRSAEncryption |
| Subject/Issuer | C=CN, ST=北京, L=北京市西城区阜成门外大街31号4层408D, O=北京春田知韵科技有限公司, OU=北京春田知韵科技有限公司 |
| 自签 | 是（subject == issuer） |
| notBefore | 2025-09-08 16:00:00 GMT |
| notAfter | 2050-09-08 15:59:59 GMT |
| Serial | `C3CCAA9905085479C9C415CE2C5A816C6A0E6F22` |
| Cert SHA-256 | `D4:7C:AF:B4:50:B9:C8:19:11:80:40:27:95:F3:5C:E5:0C:E2:D8:B3:7F:13:00:B4:2E:7D:86:33:BC:86:B7:DF` |
| PEM 导出 | `workspace/notes/signing-cert-only.pem` |

## 3. DEX / 代码层

| 文件 | 大小 | Magic |
|---|---:|---|
| `classes.dex` | 9,632,348 | `dex\n038\0` |
| `classes2.dex` | 9,063,168 | `dex\n038\0` |
| `classes3.dex` | 19,176 | （已抽出） |

已抽出到：`workspace/extracted/sample/`。

## 4. 关键组件（Manifest 字符串）

### 4.1 核心

- `com.bytedance.android.doubaoime.ImeApplication`
- `com.bytedance.android.doubaoime.ImeService`（输入法服务）
- `com.bytedance.android.input.basic.recognition.accessibilityImpl.ImeAccessibilityService`
- `com.bytedance.android.doubaoime.TaskService`
- `com.bytedance.android.doubaoime.contentProvider.ImeContentProvider`
- `com.bytedance.android.doubaoime.contentProvider.SettingsContentProvider`

### 4.2 UI / 引导

- `LauncherActivity`
- `ImeGuideActivity` / `ImeGuideSettingsActivity` / `ImeGuideSettingsNewActivity`
- `SettingsActivityNext`
- `PrivacyStatementActivity` / `PrivacyStatementActivityExt`
- `SystemPermissionActivity`
- `WebviewActivity`
- `FeedbackActivity` / `FeedbackVoiceDetailsActivity`
- `GuideAnimationActivity`

### 4.3 推送 / 厂商通道

可见华为 HMS Push、小米 MiPush、vivo Push、OPPO/HeyTap MCS、以及字节内部 push/wschannel 相关组件与 provider。

### 4.4 权限线索（不完整，待 apktool 解码后补全）

字符串中出现包括但不限于：

- `BIND_INPUT_METHOD`
- `RECORD_AUDIO`
- `READ_SMS` / 验证码相关
- `READ_CONTACTS`
- `SYSTEM_ALERT_WINDOW`
- `REQUEST_INSTALL_PACKAGES`
- `POST_NOTIFICATIONS`
- `DETECT_SCREEN_RECORDING`
- `BLUETOOTH` / `BLUETOOTH_ADMIN`
- 网络与前台服务相关权限

## 5. Native 库（arm64-v8a only，54 个）

完整列表：`workspace/notes/native-libs-arm64.txt`

### 5.1 分析优先级建议

**P0（输入法核心）**

1. `libkeyboard.so`（9.0MB）
2. `libime_net_sdk.so`（6.5MB）
3. `libime_ui_android_platform.so`（1.0MB）
4. `liboime-config.so`（0.73MB）

**P1（安全/壳/加固）**

1. `libshell.so`（6.9MB）— 是否加固入口
2. `libmetasec_ml.so`（3.8MB）— MetaSec
3. `libEncryptor.so`（74KB）
4. `libttcrypto.so` / `libttboringssl.so`

**P2（模型/语音/网络基础设施）**

1. `libonnxruntime.so` + `libbytenn.so`
2. `libaudioeffect.so`
3. `libsscronet.so` + `libcurl.so`
4. `libttffmpeg.so`

**P3（可观测性，通常后看）**

- `libnpth*.so`、`libalog.so`、`libprofiler.so`、`libmonitorcollector-lib.so` 等

## 6. 资源与资产

### 6.1 大体量条目（>1.5MB，节选）

| 路径 | 大小 |
|---|---:|
| `assets/dict/c48352298dbd` | 46.6 MB |
| `assets/dict/9f9ec5e462c2` | 26.1 MB |
| `assets/dict/991bca749d55` | 22.5 MB |
| `assets/dict/7cbf0cdb728e` | 19.4 MB |
| `res/font/noto_color_emoji.ttf` | 18.6 MB |
| `lib/.../libonnxruntime.so` | 12.7 MB |
| `assets/dict/6ee457976f14` | 11.9 MB |
| `res/font/qihei.ttf` | 9.0 MB |
| `assets/dict/hw/android/2803c3e16533` | 7.9 MB |
| `assets/dict/asr/90afacc4f089` | 7.2 MB |

### 6.2 assets 顶层类别

- `dict/`：主词库、手写 hw、ASR
- `skin/`：皮肤（含 dark）
- `lottie/`：动效
- `symbol/`：符号
- `default_translation_zh-CN.json` / `en.json`
- 华为 GRS / HMS 证书与路由配置（`grs_*`、`hms*.bks`）
- `slardar.properties`：发布构建元数据
- `mena.czl` / `mend`：未知封装资源（后续识别）

### 6.3 关键 res/xml

- `method.xml` — IME 方法定义
- `spellchecker.xml`
- `settings_preferences.xml` / `root_preferences.xml`
- `network_security_config.xml`
- `accessibility_config.xml`
- `file_paths.xml` 等 FileProvider 路径

## 7. 环境与缺口

本机已有：

- `java` 17
- `unzip` / `openssl` / `python`

本机暂缺（后续补齐更顺）：

- Android SDK `aapt` / `aapt2`
- `apktool`
- `jadx`
- `adb`（动态分析时）

## 8. 下一步

1. 安装 jadx + apktool，产出 `workspace/decompiled/`
2. 解码 manifest，固化 versionCode/minSdk/targetSdk/完整权限表
3. 判断 `libshell.so` 是否加固，决定 dump 策略
4. 定位 `InputMethodService` 实现与 JNI 到 `libkeyboard.so` 的边界
5. 梳理 `ime.doubao.com` 相关 API 与更新/词库下发

## 9. 已落盘产物

```text
originals/apk/doubao-ime-original.apk
originals/hashes/doubao-ime-original.sha256
originals/hashes/doubao-ime-original.md5
workspace/extracted/sample/AndroidManifest.xml  # 二进制 AXML
workspace/extracted/sample/classes*.dex
workspace/extracted/sample/BYTESIGN.RSA
workspace/extracted/sample/MANIFEST.MF
workspace/notes/native-libs-arm64.txt
workspace/notes/signing-cert-only.pem
workspace/notes/signing-cert.pem
reports/00-sample-inventory.md
README.md
```
