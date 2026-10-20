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

## 10. 调试技巧备忘

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
