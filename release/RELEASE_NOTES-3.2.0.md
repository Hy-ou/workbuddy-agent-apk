# WorkBuddy 3.2.0

一体化 APK：内置 WorkBuddy 网关 + 原生 Android 助手 + DeepSeek Harness（DSH）内核独立进程，
无需电脑或服务器，装上即可管理账号池，并用 DSH Agent 检索、操作手机文件与界面。

## 本次更新（3.2.0）

- **所有文件访问**：`MANAGE_EXTERNAL_STORAGE` 申请与状态卡片（设置 → 文件访问），
  授权后 DSH 的工作区切到 `/storage/emulated/0/WorkBuddy`，bash / 文件 / 检索工具可直接读写手机文件。
- **DSH 控制台交互修复**（3.1.0 起）：修复部分机型 WebView `vh/dvh` 解析为 0 导致的整页塌陷、
  按钮点击无响应；token 只加载一次，避免 `dsh web authentication required`。
- **助手引擎切换**移入设置并持久化（原生助手 / DSH 内核）。
- 补齐 WebView 附件选择、控制台日志与错误诊断。

## 安装

```bash
adb install -r WorkBuddy-3.2.0.apk
```

- 首次启动自动拉起内置网关（`127.0.0.1:7863`），并在约 3 秒内解压 DSH 内核（占用约 420 MB）。
- 「账号」页添加账号；「助手」页对话；「设置 → 助手引擎」切换到 DSH 控制台。
- 建议在「设置 → 文件访问」授予「所有文件访问」，否则 DSH 只能访问 App 私有目录。

## 产物

| 附件 | 大小 | 说明 |
|---|---|---|
| `WorkBuddy-3.2.0.apk` | 106.7 MB | release，debug 签名，可直接安装 |
| `WorkBuddy-3.2.0-debug.apk` | 117.4 MB | 支持 `run-as`、WebView 远程调试 |
| `SHA256SUMS` | — | 校验值 |

## 校验值

见附件 `SHA256SUMS`（`WorkBuddy-3.2.0.apk` = `b86957e364444e2f…`）。

## 兼容性

Android 7.0+（API 24）；DSH 仅 arm64-v8a。验证设备：Xiaomi M332BF（Android 15）。

## 已知限制

- 体积较大（内核树 + Node 运行时）；首启解压占用约 420 MB。
- `MANAGE_EXTERNAL_STORAGE` 与内置 Node 运行时不适合直接上架 Google Play。