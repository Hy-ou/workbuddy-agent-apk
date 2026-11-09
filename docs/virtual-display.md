# 安卓虚拟屏（Virtual Display）

> 从 **3.13.0** 开始，WorkBuddy 可以让 Agent 在一块**不对应物理屏幕**的 Android 显示里运行 App，
> 用户继续使用真机屏幕，两边互不干扰。

## 1. 它是什么

系统里多出一块 off-screen 显示（开发者选项里的「模拟辅助显示」用的同一机制），
由 App 创建、由 Agent 操作：

```
┌──────────── 真机主屏（用户在用的） ────────────┐      ┌──── 虚拟屏（Agent 在用） ────┐
│ 微信 / 抖音 / 浏览器 …                          │      │ 目标 App 的另一个窗口          │
│ 用户触摸、输入，完全不受影响                     │      │ input -d <id> 注入触摸/文字    │
└─────────────────────────────────────────────────┘      └───────────────────────────────┘
                    ▲                                                  ▲
                    │                                                  │
              DisplayManager                                     Shizuku (uid 2000)
```

- 创建/销毁：`settings put global overlay_display_devices "720x1280/320"`（关闭时恢复原值）；
- 启动 App：`am start --display <id> -n <pkg>/<activity>`；
- 触摸/文字：`input -d <id> tap|swipe|text|keyevent ...`；
- 截图：`screencap -d <id> -p`（PNG 写到 `cache/vdisplay/latest.png`）。

虚拟屏和真机**共用同一套应用数据**（同一个 Android 用户、同一个 App、同一个账号），
不是第二台手机，也不是应用分身。

## 2. 前置条件

| 条件 | 说明 |
|---|---|
| Android 10+（API 29） | `input -d` / `screencap -d` / `am --display` 的可用基线 |
| Shizuku 已安装并授权 | 创建显示、注入事件、截图都要求 shell（uid 2000）；App 不能直接做 |
| 目标 App 有桌面入口 | 「启动应用」列表来自 `MAIN/LAUNCHER`，没有桌面图标的 App 需要自己提供组件名 |

未满足时：页面会给出「需要 Shizuku / 当前设备不支持」的提示，Agent 工具返回可读错误，不会静默失败。

## 3. 在 App 里使用

**设置 → 虚拟屏 → 虚拟屏控制台**

1. 选择分辨率（720×1280 / 1080×1920 / 1280×800），点「开启虚拟屏」；
2. 「画面」区每 1.5 秒自动截一帧，点画面 = 在虚拟屏对应坐标点一下（可用来扫码登录、点确认）；
3. 「输入」区可以发送文字/返回/桌面；中文走剪贴板粘贴；
4. 「在虚拟屏启动应用」里搜索并启动任意桌面 App；
5. 用完点「关闭虚拟屏」——会把 `overlay_display_devices` 恢复成创建前的值。

> 画面右上角有「自动」开关：关掉后只在你点「刷新」时截屏，省电。

## 4. Agent 怎么用

### 4.1 DSH 内核（推荐）

插件 `@workbuddy/dsh-mobile` 新增三个工具：

| 工具 | 作用 |
|---|---|
| `wb_vdisplay` | `action: status / start / stop / apps / launch`；start 返回 `display.id/width/height` |
| `wb_vscreen` | 截取虚拟屏 → `path`（PNG）+ 分辨率 + displayId |
| `wb_vinput` | `action: tap / swipe / key / text`，坐标用虚拟屏像素 |

宿主桥新增 `POST /vdisplay`（127.0.0.1:7865，token 校验），插件只是薄封装。
环境手册里新增「虚拟屏」一节，明确：

- 用户说「别动我手机 / 后台操作」→ 先 `start`，把工作搬到虚拟屏；
- 坐标以 `display.width/height` 为准；先 `wb_vscreen` 再点；
- 任务结束先问用户是否 `stop`；
- 单实例 App 可能把窗口从真机移走，动手前提醒用户；
- 不做支付/转账类操作。

### 4.2 原生助手

`MobileTools` 新增（不需要无障碍，但需要 Shizuku）：

```
{"tool":"vdisplay","action":"start"}          // start / stop / status
{"tool":"vlaunch","package":"com.tencent.mm"}
{"tool":"vscreen"}
{"tool":"vtap","x":360,"y":640}
{"tool":"vswipe","x1":360,"y1":1000,"x2":360,"y2":400}
{"tool":"vtype","text":"你好"}
{"tool":"vkey","code":"back"}                  // back/home/enter/recents/menu
```

> 原生助手的「手机操作」开关现在只要**无障碍已连接或 Shizuku 已授权**任一成立就会启用；
> 没有无障碍时虚拟屏工具可用，`screen/tap/type` 等真机工具会返回「无障碍服务未连接」。

## 5. 限制与注意事项

- **单实例 App**：微信等应用通常只允许一个任务实例；启动到虚拟屏后，真机上的同一 App 窗口会被移过去。
  需要两边同时用，优先选声明了多显示器/多窗口支持的应用。
- **FLAG_SECURE**：银行、支付、部分视频 App 会禁止截屏，`screencap` 得到黑图；这类 App 不要用虚拟屏自动化。
- **关屏/深度休眠**：真机熄屏后 SurfaceFlinger 可能停止合成 overlay 显示，截图会黑/失败；
  长任务保持充电、不要清理后台（App 持有网关 wakelock，但不保证所有 ROM 都不冻结）。
- **音频**：虚拟屏里播放的声音可能从扬声器出来，无法按显示隔离。
- **安全**：虚拟屏里的操作是真实操作（真实账号、真实数据）。Agent 不应在虚拟屏里做支付、转账、删除等不可逆动作，
  除非用户明确确认。
- **系统设置副作用**：`overlay_display_devices` 是全局设置；App 记录了创建前的值并在关闭时恢复。
  如果卸载 App 时虚拟屏仍开着，系统设置会保留，可在开发者选项里关掉，或执行
  `settings delete global overlay_display_devices`。

## 6. 排错

| 现象 | 排查 |
|---|---|
| 点「开启虚拟屏」报 Shizuku 未授权 | Shizuku 是否在运行；App 内「设置 → 扩展能力 → 申请授权」；部分 ROM 需要给 Shizuku 自启动 |
| 开启成功但画面一直「等待首帧」 | `adb shell dumpsys display \| grep -i overlay` 看显示是否存在；确认 `screencap -d <id> -p` 能出图 |
| 点按没反应 | 确认点击的是虚拟屏画面（坐标会按 display 分辨率换算）；部分页面需要先请求焦点 |
| 启动 App 报 `Error: ...` | 该 App 没有可启动 Activity，或组件被禁用/未安装 |
| 中文输入不生效 | 剪贴板粘贴被 App 拦截；换用页面上的键盘/候选词，或让真机用户代输 |
| 关闭后虚拟屏还在 | 可能不是本 App 创建的（没有恢复记录）；执行 `settings delete global overlay_display_devices` |

## 7. 相关代码

- `android/app/src/main/java/com/workbuddy2api/panel/vdisplay/VirtualScreen.kt` — 能力层（创建/截图/输入/启动）
- `android/app/src/main/java/com/workbuddy2api/panel/ui/vdisplay/VirtualScreenScreen.kt` — 控制台 UI
- `android/app/src/main/java/com/workbuddy2api/panel/dsh/LocalBridge.kt` — `POST /vdisplay`
- `android/dsh/assets/dsh-plugins/wb-mobile/index.js` — `wb_vdisplay` / `wb_vscreen` / `wb_vinput`
- `android/app/src/main/java/com/workbuddy2api/panel/agent/MobileTools.kt` — 原生助手工具
