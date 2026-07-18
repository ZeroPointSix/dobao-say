# 01 - 原始样本上传核验

- 日期：2026-07-18
- 分支：`reverse-sample`
- 核验人：Cursor Cloud Agent（bc-78d38d49-e64b-4297-8ec9-c8bfd9bde3c0）
- 结论：**原始 APK 已完整入库，可供后续逆向使用**

## 1. 入库位置

| 项 | 值 |
|---|---|
| 仓库 | https://github.com/ZeroPointSix/dobao-say |
| 分支 | `reverse-sample` |
| 规范化样本 | `originals/apk/doubao-ime-original.apk` |
| 存储方式 | Git LFS（`*.apk` / `originals/apk/**`） |
| LFS OID | `sha256:c140d16625f1b8eddb21fa905f0e98a74b3e242d2638415801207c23c449d59a` |
| 媒体直链 | https://media.githubusercontent.com/media/ZeroPointSix/dobao-say/reverse-sample/originals/apk/doubao-ime-original.apk |

说明：根目录 `豆包输入法.apk` 为本地投放副本，已在 `.gitignore` 中忽略；分析请一律使用 `originals/apk/doubao-ime-original.apk`。

## 2. 完整性校验（本机复算）

| 检查项 | 结果 |
|---|---|
| 字节数 | `155200288`（与 LFS pointer / 清单一致） |
| SHA-256 | `c140d16625f1b8eddb21fa905f0e98a74b3e242d2638415801207c23c449d59a` ✅ |
| MD5 | `9addf8dfb8bcf3f7c3781d429dc8890f` ✅ |
| 文件类型 | `Android package (APK), with classes.dex` ✅ |
| ZIP 魔数 | `50 4B 03 04`（PK..）✅ |
| `unzip -l` | 可读，含 `classes.dex` / `lib/arm64-v8a/*.so` / `assets/dict/**` ✅ |

## 3. 样本身份（与 00 清单一致）

| 字段 | 值 |
|---|---|
| 包名 | `com.bytedance.android.doubaoime` |
| 用户口语称呼 | “exe”（实为安卓 APK） |
| 用途 | 后续逆向 / 防御分析基线 |

## 4. 后续建议

1. 在隔离环境安装 `jadx` / `apktool` / `aapt2`，对 `originals/apk/doubao-ime-original.apk` 做完整反编译。
2. 优先确认 `libshell.so` 是否加固入口，再进入 `libkeyboard.so` / `libime_net_sdk.so`。
3. 仓库当前为 **public**；若样本不宜公开，建议改为 private 或仅保留哈希 + 私有对象存储。
