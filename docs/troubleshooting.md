# 真机排查与修复记录

按「现象 → 定位 → 根因 → 修复」记录开发过程中踩到的坑。设备：Xiaomi M332BF（Android 15，arm64-v8a，
页面大小 4 KB，WebView Chromium 149）。

---

## 1. 首启动报错：`Unexpected JSON token … $.in_flight_full`

**现象**：总览页打不开，报
`Unexpected JSON token at offset 91: Expected valid boolean literal prefix, but had '0' at path: $.in_flight_full`。

**定位**：`adb shell uiautomator dump` 拿到屏幕文案 → 对照 `/panel/api/overview` 原始 JSON。

**根因**：服务端 `in_flight_full` 是**整型计数**（在途已满的账号数），客户端模型声明成了 `Boolean`。

**修复**：`data/model/Overview.kt` 改为 `inFlightFull: Int = 0`；UI 改为 `if (inFlightFull > 0)` 并显示
「并发已满：N 个账号在途已满，新请求会排队等待」；同步更新演示数据与 `DecodeTest` 用例。

---

## 2. 16 KB 页对齐告警

**现象**：部分设备提示「应用不兼容 16 KB 页大小」，`libwb2api.so` 的 `LOAD` 段对齐为 4 KB。

**根因**：Go 交叉编译默认按 4 KB 对齐，新设备/Play 要求 16 KB。

**修复**：链接参数加 `-Wl,-z,max-page-size=16384`（`build_gateway.ps1` 已内置），
用 `llvm-readelf -l` 确认 `p_align 0x4000`，`zipalign -c -P 16` 通过。

---

## 3. DSH 启动失败：`cannot find "libcrypto_3.so" from verneed[1] in DT_NEEDED list`

**现象**：node 二进制放进 jniLibs 后仍无法启动。

**定位**：
1. `llvm-readelf -d libdshnode.so` 看 `DT_NEEDED`；
2. 设备报的是 **verneed** 而不是 needed → 说明符号版本依赖里的文件名没改；
3. 补上 `DT_VERNEED` 改名后仍失败 → `readelf -V` 显示 `File: libcrypto_3.so` 已正确，
   但 bionic 用 **`DT_SONAME`** 做匹配，被链接库的 soname 还是 `libcrypto.so.3`。

**根因**：bionic 加载器要求 `NEEDED`、`VERNEED.vn_file`、目标库 `SONAME` 三者一致。

**修复**：`pack_dsh.ps1` 一次性改写三处（`DT_NEEDED` / `DT_VERNEED` / `DT_SONAME`），全部等长替换。

---

## 4. DSH 控制台按钮点击无反应

**现象**：控制台能显示（标题 `DeepSeek Harness`），但点任何按钮都没响应，也没有 401。

**定位**（WebView 远程调试 CDP）：
```
Page.getLayoutMetrics  → css 视口 393×652（正常）
document.documentElement.clientHeight → 652（正常）
新建 div 设 height:100vh           → 实测 0      ← 异常
document.getElementById("root").height → 0      ← 布局塌陷
document.elementFromPoint(按钮中心)   → HTML     ← 按钮不在命中树
about:blank 上 100vh               → 0          ← 与页面无关，是内核行为
```
`html{height:100%}` 链全部算成 0，导致 DSH 前端整页高度为 0，按钮虽被绘制，命中区域却在视口外。

**根因**：该机 WebView（Chromium 149）的初始包含块高度为 0，`vh/dvh` 单位解析为 0。

**修复**：页面加载后注入脚本（`DshScreen.kt` 的 `VH_FIX_JS`）：
- 把 `html/body/#root` 高度钉成实际像素；
- 遍历样式表把 `vh/dvh/svh/lvh` 就地换算为 `px`；
- `MutationObserver` 兜底 SPA 动态插入的样式。

**验证**：`#root` 高度 652；真实触摸命中 `BUTTON`；点击「标准模式」弹出菜单、「选择工作区」打开目录选择器。

---

## 5. DSH 控制台显示 `dsh web authentication required`

**原因**：token URL 是一次性的，服务端用它换签名 cookie。客户端在 `factory` 和 `update` 里各 `loadUrl` 一次，
第二次访问同一 token 被判无效。

**修复**：token 只在 `factory` 加载一次；加载完成后置 `tokenConsumed=true`，Activity 重建/页内跳转改用
base URL（走 cookie）。同时 `DshManager` 只解析「本次启动之后」日志里的 token，避免拿到上一次的失效 token。

---

## 6. 旧内核进程占端口 / 端口冲突

**现象**：重启 App 后 DSH 起不来或占用多份内存。

**根因**：`am force-stop` 只结束 App 进程，node 子进程可能遗留。

**修复**：
- 启动前读 `files/dsh-node.pid`（启动时通过反射取 `Process#pid`，兼容 minSdk 24）并 `killProcess`；
- 端口从 8300 起自动找空闲端口，避免与遗留进程硬碰。

---

## 7. `office-to-pdf` 插件反复报错

**现象**：每次启动日志都有
`office-to-pdf: failed to import: Cannot find module '…/restructure/src/EncodeStream.js'`。

**根因**：官方 payload 里 `restructure` 包只带了 `dist/main.cjs` 与 `index.js`，缺 ESM 源文件；
`office-to-pdf` 依赖它，移动端也用不到 PDF 转换。

**修复**：托管补丁 `dshhome/cordis.patch.yml` 中 `- id: office-to-pdf` 置 `disabled: true`；
并给补丁加版本标记（`WorkBuddy-managed-patch: v2`），升级后自动覆盖旧文件。

---

## 8. `INSTALL_FAILED_USER_RESTRICTED`

**现象**：`adb install` 失败，提示 `Install canceled by user`（MIUI）。

**处理**：开发者选项 → 打开「USB 安装」（或安装时在手机上点允许）。之后 `adb install -r` 正常。

---

## 9. 不能从应用数据目录执行二进制

**现象**：把 node 拷到 `files/` 下执行报 `error=13, Permission denied`。

**根因**：Android 10+ 对 `targetSdk>=29` 的应用禁止执行应用目录文件（SELinux）。

**结论**：可执行文件只能放 `jniLibs`（`nativeLibraryDir`）。这也是网关与 Node 都以 `lib*.so` 形式打包的原因。

---

## 10. 对话页「添加文件」点击后不弹系统选择器

**现象**：App 内 DSH 控制台对话页，点「+」→「文件」没有任何反应；不弹选择器、无 toast、无请求。

**定位**：先确认入口和链路，别急着改前端。
1. DSH 网页**没有独立的附件按钮**：附件入口是输入框左下角「+」→ 菜单里的「文件」（回形针），
   以及把文件拖进页面。
2. 菜单项走前端 `inputHub.pickFiles()` → `fileInputRef.current?.click()`
   （`@deepseek-ai/dsh-client-ui-conversation` 的 `bindFilePicker`/`pickFiles`）。
3. 之后必须由宿主的 `WebChromeClient.onShowFileChooser` 拉起一个选择器 Activity。

**根因**：`onShowFileChooser` 只有 `params.createIntent()` 一条路径，且失败时 `catch { return false }`。
WebView 收到 `false` 是**静默放弃**——不弹窗、不打日志，所以前端看起来就是「按钮坏了」。
会落到这条死路的实际情况至少有四种：`params` 为空、Intent 在定制 ROM 上不可启动、
ROM 裁掉了对应的 DocumentsUI、launcher 在 Activity 未 RESUMED 时抛异常。
同链路另外两个坑：多选结果只在 `clipData` 里（旧代码只读 `data.data`，等于「选多个 = 没选」）；
上一次的 `ValueCallback` 没归还时，后续选择器永远不弹。

**修复**（`ui/dsh/DshScreen.kt`）：
- 选择 Intent 兜底链 `createIntent()` → `ACTION_OPEN_DOCUMENT` → `ACTION_GET_CONTENT`，
  带 `EXTRA_ALLOW_MULTIPLE` / `EXTRA_MIME_TYPES` / `FLAG_GRANT_READ_URI_PERMISSION`，逐条试；
- `collectPickedUris()` 先读 `clipData` 再读 `data.data` 去重；
- 新请求先释放旧 callback，`DisposableEffect` 在页面销毁时归还；
- `FILE_PROBE_JS`：`hidden`（`display:none`）的 file input 改成 1px 不可见块，并输出点击日志。

**已实测（3.10.1）**：Xiaomi M332BF 上「+」→「文件」弹出选择器，选中后卡片正常出现；
logcat 为 `[wb] file input click` → `onShowFileChooser mode=...` → `launch ... -> true` →
`picker result code=-1 uris=1`。另外用 debug 包 CDP 的 `DOM.setFileInputFiles` 绕过原生选择器
直接给 input 塞文件，卡片同样出现——据此把「选完没反应」定位到原生回传 URI 这一段，而不是前端入队。

**验证手法**：`adb logcat -s WbDshFile WbDshWeb`，点一次「+」→「文件」：

| logcat | 结论 |
|---|---|
| 无 `[wb] file input click` | 前端没点到 input（DSH UI 层，非宿主问题） |
| 有 `[wb] file input click`，无 `onShowFileChooser` | WebView 没回调，继续换注入/可见性方案 |
| 有 `onShowFileChooser` + `launch ... -> false` | 设备没有可启动的选择器 Activity（兜底链应已救回） |
| 有 `picker result ... uris=N` | 宿主侧已通，问题回到前端或上传准入 |

> debug 包还能用 WebView 远程调试直接看：`adb forward tcp:9222 localabstract:webview_devtools_remote_$(adb shell pidof com.workbuddy2api.panel)`，
> 在 Console 里看 `[wb] file input click ...`，Network 里看 `/api` 的上传请求。

---

## 11. 调试技巧备忘

```bash
# 直达某个 Tab（debug intent extra）
adb shell am start -S -n com.workbuddy2api.panel/.MainActivity --ei tab 2 --es mode dsh

# 读取网关日志 / DSH 日志
adb shell run-as com.workbuddy2api.panel tail -50 files/gateway/gateway.log
adb shell run-as com.workbuddy2api.panel tail -50 files/dsh-web.log

# 端口转发后直接调网关 API
adb forward tcp:17863 tcp:7863
# 读取 API Key
adb shell run-as com.workbuddy2api.panel cat files/gateway/config.json

# WebView 远程调试（debug 包）
adb forward tcp:9222 localabstract:webview_devtools_remote_$(adb shell pidof com.workbuddy2api.panel)

# 模拟授予/撤销「所有文件访问」
adb shell appops set --uid com.workbuddy2api.panel MANAGE_EXTERNAL_STORAGE allow
adb shell appops set --uid com.workbuddy2api.panel MANAGE_EXTERNAL_STORAGE deny
```

---

## 12. 锁屏后网关"停止工作"（3.11.1 及以前）

**现象**（Xiaomi M332BF / Android 17 / HyperOS）：

- 白天在前台一切正常；锁屏一段时间再回来，通知还在、进程还在，但锁屏期间网关的定时任务（签到 / 保活 / 余额刷新）一条都没跑；
- 从外部打 `/healthz` 直接超时，而 `ps` 里 `libwb2api.so` 活得好好的；
- 锁屏过夜后更严重：通知消失、进程没了，只能重开 App；
- 最迷惑的一点：通知写着「网关运行中」，实际端口无人应答。

**定位（关键四步）**：

```bash
# 1) 前台服务类型：dataSync（Android 15+ 有 6 小时/24 小时后台配额）
adb shell dumpsys activity services com.workbuddy2api.panel | grep types=
#   types=0x00000001

# 2) 进程「活着」但不应答：先看是不是被冻结（cgroup v2 freezer）
adb shell cat /sys/fs/cgroup/apps/uid_<UID>/pid_<PID>/cgroup.freeze
#   1 = 已冻结

# 3) 谁冻的：小米自己的 GreezeManager，不是 AOSP 的 CachedAppOptimizer
adb shell logcat -b system -d | grep -E "PolicyMaker|GreezeManager|ActionExecute"
#   PolicyMaker : HasForegroundService pkgName=com.workbuddy2api.panel uid:10519
#   PolicyMaker : uid=10519 reason=freeze_able
#   GreezeManager: FZ uid = 10519 reason = screen_off success !
#   ActionExecute: freezeUid SUCCESS: uid=10519 pkg=com.workbuddy2api.panel reason=screen_off

# 4) wakelock 被一起废掉
adb shell dumpsys power | grep -i "wb:gateway"
#   PARTIAL_WAKE_LOCK 'wb:gateway' DISABLED (uid=10519 isFrozen=true ...)
```

**根因（四层，按触发顺序）**：

1. **HyperOS 的 GreezeManager 锁屏即冻结整个 UID**（`reason=screen_off`；切后台时是 `reason=tobg`）。
   它日志里明明打印了 `HasForegroundService`，仍然判 `freeze_able`——**前台服务在这套 ROM 上不是豁免条件**。
   冻结按 UID 生效，`libwb2api.so` 和宿主在同一个 `apps/uid_x/pid_y` cgroup 里，一起被冻；
   系统同时把 `wb:gateway` wakelock 置为 `DISABLED`。这就是「进程还在、网关不工作」的直接原因。
2. **没有 wakelock**：即使 ROM 不冻结，前台服务也只防回收、不防 CPU 休眠；屏幕一灭 SoC suspend，
   Go 进程的定时器同样停摆。
3. **`dataSync` 前台服务有 6 小时 / 24 小时配额**（targetSdk 35 + Android 15+）：锁屏过夜跑满配额后
   系统回调 `Service.onTimeout()`，默认实现直接抛异常杀进程——对应「过夜后进程消失」。
4. **没有任何恢复路径**：`onDestroy` 无条件 `stop()` 杀网关；`START_STICKY` 重建服务后
   `onStartCommand` 只刷新通知、不重建网关；没有看门狗、没有 `BOOT_COMPLETED`、没有回前台自检。

**修复（3.11.3）**：

- `AndroidManifest.xml`：加 `WAKE_LOCK`、`FOREGROUND_SERVICE_SPECIAL_USE`；服务类型 `dataSync` → `specialUse`
  （`PROPERTY_SPECIAL_USE_FGS_SUBTYPE=local_api_gateway`，无配额）。
- `GatewayService`：服务存活期持 `PARTIAL_WAKE_LOCK`（看门狗每 30s 幂等续持）；
  `startForegroundCompat()` 显式带 `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`；重写 `onTimeout()` 重新提交前台状态；
  30s 看门狗 `ensureRunning()`；`onTaskRemoved` 不跟着停；`onDestroy` 只在「用户主动停止」时才杀网关。
- `LocalGatewayManager`：新增 `ensureRunning()`（幂等重拉）、`stopByUser()` / `consumeStopRequest()`
  （区分用户停止与系统回收）、`watchExit()`（进程退出立刻置 Failed，杜绝假活）。
- `AppViewModel` + `MainActivity`：`ON_RESUME` 自检 `ensureLocalGateway()`，解锁回前台自动恢复，
  并重挂可能顺延的端口。
- 设置页「运行体检」新增 **锁屏冻结（HyperOS）** 引导：一键跳「省电策略」和「自启动管理」。

**HyperOS 上必须同时做（应用无法自行豁免）**：

设置 → 应用设置 → 应用管理 → WorkBuddy → **省电策略 = 无限制**；并允许**自启动**；
最近任务里给应用**上锁**。否则系统仍会在锁屏 / 切后台后冻结 UID。

**验证**：

```bash
adb shell dumpsys activity services com.workbuddy2api.panel | grep types=   # 0x40000000 = specialUse
adb shell dumpsys power | grep -i "wb:gateway"                            # PARTIAL_WAKE_LOCK 已持有
adb shell input keyevent 26                                               # 关屏
adb shell cat /sys/fs/cgroup/apps/uid_10519/pid_<PID>/cgroup.freeze       # 1=被冻结 0=正常运行
adb shell 'printf "GET /healthz HTTP/1.0\r\n\r\n" | toybox nc -w 5 127.0.0.1 7863'
```

实测结果：未加白名单时，关屏约 12s 后 `cgroup.freeze=1`、`/healthz` 超时、wakelock 变 `DISABLED`；
按电源键解锁后 `cgroup.freeze=0`、`/healthz` 立即 200、wakelock 自动恢复。加「无限制」后可用同样步骤复测，
应保持 `freeze=0`。

**两个坑**：`input keyevent 223`（KEYCODE_SLEEP）在该 ROM 上无效，关屏要用 `input keyevent 26`（POWER）；
`input` 需要开发者选项里的「USB 调试(安全设置)」，否则报 `INJECT_EVENTS`。
