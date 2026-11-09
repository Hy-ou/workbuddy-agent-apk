# WorkBuddy 3.13.0

## 修改说明

### 新增：安卓虚拟屏（Agent 后台操作，不影响真机）

Agent 现在可以在一块**不对应物理屏幕**的 Android 显示里运行 App，你继续用真机屏幕，两边互不干扰。

- **原理**：系统级 overlay display（开发者选项「模拟辅助显示」同一机制），
  `settings put global overlay_display_devices "720x1280/320"` 创建；
  通过 Shizuku（uid 2000）执行：
  - `am start --display <id> -n <pkg>/<activity>` 启动 App 到虚拟屏；
  - `input -d <id> tap/swipe/text/keyevent` 注入触摸/文字（不抢真机焦点）；
  - `screencap -d <id> -p` 截图（PNG → 应用缓存 `vdisplay/latest.png`）。
- **关闭即还原**：创建前记录你原本的 `overlay_display_devices` 值，关闭时原样恢复，
  不污染开发者选项；displayId 优先从 `DisplayManager` 读取，个别 ROM 不暴露时用截图反查兜底。
- **控制台**：「设置 → 虚拟屏」
  - 开启/关闭，三种预设分辨率（720×1280 / 1080×1920 / 1280×800）；
  - 实时画面（1.5 秒自动刷新，可关）：**点画面 = 在虚拟屏对应坐标点一下**，
    可以用来扫码登录、点确认；
  - 文字注入（ASCII 直输；中文走剪贴板粘贴）、返回/桌面键；
  - 搜索并一键启动任意桌面 App 到虚拟屏；
  - Shizuku 授权入口与状态提示。
- **DSH 内核**：插件新增 `wb_vdisplay`（status/start/stop/apps/launch）、
  `wb_vscreen`（截图路径）、`wb_vinput`（tap/swipe/key/text）；宿主桥新增 `POST /vdisplay`；
  环境手册新增「虚拟屏」一节：何时用（用户说「别动我手机」）、坐标基准、结束要 stop、
  单实例应用提醒、不碰支付类操作。
- **原生助手**：新增 `vdisplay` / `vlaunch` / `vscreen` / `vtap` / `vswipe` / `vtype` / `vkey`
  工具（不依赖无障碍，只要求 Shizuku）。

### 前置条件与限制

- 需要 **Android 10+** 且 **Shizuku 已安装并授权**；缺一不可，页面和工具都会给出可读提示。
- 单实例应用（微信等）启动到虚拟屏后，真机上的同一 App 窗口会被移走——这是 Android 多显示器的
  任务语义，不是缺陷；需要两边同时用请选支持多窗口的应用。
- 银行/支付/部分视频 App 有 `FLAG_SECURE`，`screencap` 只能拿到黑图；不要用虚拟屏做支付、转账。
- 真机熄屏后 overlay 显示可能停止合成，截图会黑/失败；长任务建议保持充电、不清理后台。
- 虚拟屏与真机共用同一套应用数据（同账号），操作是真实的，不可逆动作必须先确认。
- 卸载 App 时若虚拟屏仍开着，系统设置会保留；可在开发者选项关闭，或
  `settings delete global overlay_display_devices`。

完整说明：[docs/virtual-display.md](../docs/virtual-display.md)

### 版本与验证

- `versionCode 29` / `versionName 3.13.0`，与 3.12.0 同签名，可直接覆盖安装。
- 构建验证：`:app:assembleRelease` / `:app:assembleDebug` 通过；`testDebugUnitTest` 58 项通过；
  `lintDebug` 0 error；插件自检 7 个工具 + 1 段环境手册通过。
- 已在 Redmi 25019PNF3C（Android 17 / arm64）安装并启动验证：App 正常进入总览/设置/虚拟屏控制台；该机已安装 Shizuku，但测试时未启动/授权，虚拟屏的实机创建/截图/注入未实测。建议安装后重点验证：
  1. 授权 Shizuku → 开启 720×1280 虚拟屏 → 画面出现；
  2. 启动一个 App → 真机屏幕不受影响 → 在画面里点按；
  3. 对 DSH 说「用虚拟屏打开 XX」→ 观察 `wb_vdisplay` / `wb_vscreen` / `wb_vinput` 调用；
  4. 点「关闭虚拟屏」→ `settings get global overlay_display_devices` 恢复原值。
