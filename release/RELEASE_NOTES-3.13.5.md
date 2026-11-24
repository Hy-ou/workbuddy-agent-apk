# WorkBuddy 3.13.5

修一个 3.13.4 没覆盖到的组合：**沉浸全屏 + 软键盘**（issue #5 / #2 残留）。

## 现象

Android 10 及以下机型，进入全屏 → 点输入框 → 输入区被键盘盖住；同一台机器**非全屏正常**。

## 根因：三条通道只覆盖了两条

| 场景 | `adjustResize` 压窗口 | `imePadding()` | 结果 |
|---|---|---|---|
| API 30+（Android 11+） | 不压 | = 键盘高度 | 正常 |
| API < 30 标准布局 | **压** | 0（pre-30 拿不到 ime insets） | 靠窗口压缩让位，正常 |
| API < 30 沉浸全屏 | **不压** | 0 | 两条兜底同时失效 → 键盘盖住输入区 |

沉浸态的 `ImmersiveSystemBars` 会把系统栏换成
`SYSTEM_UI_FLAG_IMMERSIVE_STICKY | LAYOUT_HIDE_NAVIGATION`，这个组合下 `adjustResize`
不再压缩窗口是 Android 的老问题；而 pre-30 又没有 `WindowInsets.ime` 可用。

## 修复

- `ImeState` 增加 `bottomShiftPx`：用「根视图高度 − 可视帧高度」算出被键盘盖住的高度。
  - 窗口已被压缩时两者相等 → 0（补了会留白，标准布局不受影响）；
  - API 30+ 恒为 0，继续交给 `imePadding()` 消费 ime insets（避免重复）；
  - 只有「没压缩 + 拿不到 insets」的沉浸全屏才非 0。
- `AppShell` 在 `imePadding()` 之后追加这段底部留白 → WebView 真正变矮 →
  页面 `resize` → `VH_FIX_JS` 重算高度 → 输入区上移。
- 阈值仍是可视高度 15%，排除状态栏/手势条的常驻差值。

## 可诊断

留了一条只在下述值变化时才打的日志：

```bash
adb logcat -s WbIme
# shift=712 rootH=2400 frameH=1688 visible=true
```

- `shift=0` 但键盘确实挡住了 → 说明这台 ROM 的 `getWindowVisibleDisplayFrame` 没随键盘变化，
  需要换别的探测方式；
- `shift>0` 但页面没动 → 说明补位生效但页面没重排，问题在 `VH_FIX_JS` 那一侧。

## 版本与验证

- 版本 **3.13.5 / versionCode 34**
- `testDebugUnitTest` 61 项通过，`lintDebug` 0 error，`assembleDebug` / `assembleRelease` 通过
- 本次改动对 API 30+ 是**空操作**（`bottomShiftPx` 恒为 0），Android 11+ 与 Android 17 的
  键盘行为与 3.13.4 完全一致
- **未在 Android 10 真机上验证**（本机只有 Android 17 设备），需要 #5 的反馈者复测

## 安装

```bash
adb install -r WorkBuddy-3.13.5.apk
```

- 正式包：`WorkBuddy-3.13.5.apk`（116.9 MB，debug 签名）
- 调试包：`WorkBuddy-3.13.5-debug.apk`（127.9 MB）
- SHA-256 见 `SHA256SUMS`
