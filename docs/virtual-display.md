# 安卓虚拟屏（Agent 后台操作，不影响真机）

> 版本：3.13.1 / versionCode 30 起。旧的「overlay display / App 自建 VirtualDisplay」实现已在
> 3.13.1 被替换，原因见「为什么必须由 shell/root 创建」。

## 1. 它是什么

虚拟屏是一块**不对应物理屏幕**的 Android 显示。Agent 可以把目标 App 启动到这块显示上，
用截图 + 点按/滑动/文字驱动它，而用户的真实屏幕保持原样，可以继续刷视频、聊天、锁屏。

控制台入口：**设置 → 虚拟屏 → 虚拟屏控制台**。

## 2. 架构（3.13.1）

```
WorkBuddy App (uid 10xxx)
   │  ① 复制 assets/vds.dex 到 files/vds.dex
   │  ② Shizuku 执行：
   │     app_process -Djava.class.path=files/vds.dex /system/bin \
   │       com.wb.vds.Server W H DPI cache/vds
   ▼
shell/root 侧 vds 服务（uid 2000 或 root，display owner）
   │  创建 VirtualDisplay(PUBLIC | OWN_CONTENT_ONLY) → ImageReader
   │  写 cache/vds/id、pid、meta、frame.png（约 3fps）
   │
   ├─ 启动 App：am force-stop <pkg>; am start --display <id> -n '<pkg>/<activity>'（同一 uid，2s 后核对 Display #）
   └─ 输入注入：input -d <id> tap|swipe|keyevent|text（同一 uid）
   ▼
App 读 cache/vds/frame.png → Compose 预览；Agent 截图复制成 cache/vdisplay/latest.png
```

关键点：

- **显示 owner 与服务调用者是同一个 shell/root uid**，`am start --display` 因此既不会被
  `ActivityTaskSupervisor` 的「虚拟屏限制」拒绝，也不会触发 HyperOS 的跨应用
  「应用跳转」确认。
- **截图不再用 `screencap -d`**：服务直接把 ImageReader 的最新帧写成 PNG，App 读自己的缓存。
  旧实现用逻辑 displayId 调 `screencap -d` 在这台 Android 17 上会报
  `Display Id 'x' is not valid`（它要的是 SurfaceFlinger 物理 ID）。
- **不改任何系统设置**：不写 `overlay_display_devices`、不写 `force_desktop_mode`。
- **服务独立于 App 进程**：App 退出/被杀后虚拟屏仍在；点「关闭虚拟屏」会 `pkill` 服务，
  或重启手机自动消失。App 重进控制台时用 `kill -0 <pid>` 校验，服务已死则清掉残留状态。

### 为什么必须由 shell/root 创建

Android 17 的 `ActivityTaskSupervisor` / `SecurityManagerService` 对虚拟屏有额外限制：
普通 App 即使拥有自己创建的 VirtualDisplay，也不能把**别的 App** 启动到这块屏上
（日志：`isVirtualDisplayActive caller=... target=...` → `Permission Denial ... launchDisplayId=`）。
把显示 owner 换成 shell/root，并用同一 uid 的 `am start --display` 启动，才符合系统策略。

## 3. 前置条件

- Android 8+（API 26；`am start --display` / `app_process`）。
- **Shizuku 已安装、已启动并授权给 WorkBuddy**。普通设备是 shell（uid 2000）；
  KernelSU/Magisk 环境下 Shizuku 以 root 运行，服务也是 root。
- 创建、截图、启动、输入**全部**依赖 Shizuku；没有无障碍也能用虚拟屏。

## 4. 控制台

- 开启/关闭、三种预设：720×1280@320、1080×1920@420、1280×800@240。
- 实时画面（1.5s 自动刷新，可关）：点画面 = 在虚拟屏对应坐标点一下（坐标自动换算）。
- 文字注入（ASCII 走 `input text`；中文/emoji 走剪贴板 + `KEYCODE_PASTE` 兜底）、返回/桌面键。
- 搜索并一键启动桌面 App；启动后等 1~2 秒看预览。
- Shizuku 授权入口；关闭时释放服务与显示。

## 5. Agent 工具

DSH 插件 `@workbuddy/dsh-mobile`：

| 工具 | 作用 |
|---|---|
| `wb_vdisplay` | `status / start / stop / apps / launch`，start 返回 displayId 与分辨率 |
| `wb_vscreen` | 复制最新帧到 `cache/vdisplay/latest.png`，返回路径 + 尺寸 |
| `wb_vinput` | `tap / swipe / key / text`，坐标用虚拟屏像素 |

原生助手：`vdisplay / vlaunch / vscreen / vtap / vswipe / vtype / vkey`。
环境手册默认策略：需要点击/输入 App 界面时优先虚拟屏；只有用户明确要求
「操作我现在这个屏幕」才动真机。

## 6. 限制

- 微信等 App 启动后由**自己的进程**拉起登录/主界面，内部 Intent 不带 launchDisplayId，会被系统放回真机（display 0）；这类 App 无法在虚拟屏运行，控制台会明确报错。
- 单实例应用（微信等）同一时间只允许一个窗口：启动到虚拟屏后，真机上的同一窗口会被移走；
  要两边同时用，优先选支持多窗口的应用。
- `FLAG_SECURE` 页面（银行、支付、部分视频）截图是黑图。
- 真机熄屏/深度休眠时虚拟屏可能停止合成；长任务保持充电、不清理后台。
- **不要用虚拟屏做支付、转账、验证码轰炸等敏感操作**；虚拟屏里是真实账号。
- 目标 App 自己再跳转的页面（如某些 License 检查打开 Play 商店）可能落到真机，
  这是目标 App 的 Intent 行为，不是虚拟屏能拦截的。

## 7. 排错

| 现象 | 处理 |
|---|---|
| 点「开启虚拟屏」提示需要 Shizuku | 安装/启动 Shizuku，并在「设置 → 扩展能力」授权；页面回前台会刷新状态 |
| 启动后预览一直「等待首帧」 | 先启动一个 App；仍无帧则关闭再开启，或看 `cache/vds/log` |
| App 启动后提示「系统把 xxx 放到了真机」 | 该 App 拒绝副屏（微信等），只能真机操作；换设置/KernelSU/Chrome 等支持副屏的 App |
| App 启动失败（Permission Denial / launchDisplayId） | 确认 Shizuku 的 uid 是 2000 或 root；本机 KernelSU 下为 root。非 root 的 shell 在部分 ROM 仍可能被限制 |
| 服务被杀后仍显示「运行中」 | 3.13.1 已用 pid + `kill -0` 校验；若仍异常，点「关闭虚拟屏」再开 |
| 关屏后画面停住 | 解锁/点亮屏幕，长任务保持充电 |
| 想彻底清理 | 关闭虚拟屏，或 `adb shell su -c 'pkill -f com.wb.vds.Server'` |

## 8. 服务源码与构建

- 源码：`android/vds-helper/src/com/wb/vds/Server.java`
- 构建（Windows，JDK 17 + Android SDK 35）：

```powershell
cd android\vds-helper
javac -encoding UTF-8 -source 8 -target 8 `
  -cp "$env:ANDROID_HOME\platforms\android-35\android.jar" `
  -d classes src\com\wb\vds\Server.java
jar cf helper.jar -C classes .
& "$env:ANDROID_HOME\build-tools\35.0.0\d8.bat" `
  --lib "$env:ANDROID_HOME\platforms\android-35\android.jar" `
  --min-api 26 --output out helper.jar
Copy-Item out\classes.dex ..\app\src\main\assets\vds.dex -Force
```

服务用反射拿 `ActivityThread.systemMain()/getSystemContext()`（隐藏 API），
因此只依赖公开的 `DisplayManager` / `ImageReader` / `VirtualDisplay`。
