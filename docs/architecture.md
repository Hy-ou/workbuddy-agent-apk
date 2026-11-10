# 架构与关键实现

本文说明「为什么这么做」，以及几个非显然的实现点。代码位置以 `android/app/src/main/java/com/workbuddy2api/panel/` 为根。

---

## 1. 进程模型

App 主进程只负责 UI 与调度，两个重型组件各自跑在独立子进程里，互不影响：

| 组件 | 可执行文件 | 启动者 | 端口 | 生命周期 |
|---|---|---|---|---|
| Go 网关 | `nativeLibraryDir/libwb2api.so` | `LocalGatewayManager` | 7863（可顺延） | 前台服务 `GatewayService` 保活 |
| DSH 内核 | `nativeLibraryDir/libdshnode.so` | `DshManager` | 8300+（空闲端口） | 随 App 页面的启动/停止 |

崩溃隔离：网关挂掉不会带走 DSH；DSH 重启不影响面板与网关。

---

## 2. 为什么要交叉编译成 `lib*.so`

Android 10（API 29）起，应用**不能执行**自己数据目录里的文件（`targetSdk>=29`，SELinux 拒绝 `execve`，
报 `error=13, Permission denied`）。唯一被允许的可执行位置是 APK 安装后的原生库目录：

```
/data/app/~~xxxx/com.workbuddy2api.panel-yyyy==/lib/arm64/
```

因此 Go 二进制必须以 `lib*.so` 命名放进 `jniLibs/<abi>/`，由 `applicationInfo.nativeLibraryDir` 取路径后
用 `ProcessBuilder` 启动。同时：

- `jniLibs { useLegacyPackaging = true }`：`targetSdk>=29` 时让其解压到磁盘（否则 `exec` 找不到实体文件）；
- 交叉编译必须带 **cgo**：DNS 走 bionic 的 `getaddrinfo`，系统证书读 `/system/etc/security/cacerts`；
- `-tags timetzdata`：内嵌时区库，保证签到排程使用手机本地时区；
- 16 KB 页对齐：`-Wl,-z,max-page-size=16384`，适配新设备与 Play 兼容要求。

启动时注入的环境变量：`SSL_CERT_FILE`（`assets/cacert.pem` 兜底）、`HOME`、`TMPDIR`、`TZ`、`GODEBUG=netdns=cgo`。

---

## 3. 网关管理器（`gateway/LocalGatewayManager.kt`）

单例，负责：

1. 首次运行在 `filesDir/gateway` 生成 `config.json`（随机 `sk-` Key、只监听 `127.0.0.1`）；
2. 选择端口（7863 起，被占用则顺延），若端口上是**自己的旧实例**（`/healthz` 返回含 `workbuddy2api`）则直接接管；
3. 启动子进程、等待 `/healthz` 就绪（连接成功即认为 ready，503 也代表进程在跑）；
4. 日志线程把 stdout/stderr 写入 `gateway.log`，超过 8 MB 保留尾部 512 KB；
5. 提供 `logTail()` 给 UI 的日志弹层，`stop()` 时结束进程。

API 层（`data/WbApi.kt` + `WbRepository`）用 OkHttp 访问 `/panel/api/*`，鉴权头 `Authorization: Bearer <key>`。

---

## 4. DSH 内核接入（`dsh/DshManager.kt` + `dsh/pack_dsh.ps1`）

### 4.1 二进制与依赖库
官方 payload 里的 Node 可执行文件依赖 10 个带版本号的库（`libcrypto.so.3`、`libicuuc.so.78` …），
而 `jniLibs` 只接受 `lib<name>.so` 形式的文件名，且 bionic 在加载时会校验：

- `DT_NEEDED` 里的库名必须能按名找到；
- `DT_VERNEED` 的 `vn_file` 必须与已加载库的 **`DT_SONAME`** 一致。

因此 `pack_dsh.ps1` 内置一个 ELF64 补丁器：解析 `PT_DYNAMIC` 找到 `DT_STRTAB`，把三处字符串
（`NEEDED` / `VERNEED` / `SONAME`）**等长替换**为 jniLibs 合法名：

```
libz.so.1 → libz_1.so          libcrypto.so.3 → libcrypto_3.so
libssl.so.3 → libssl_3.so      libicui18n.so.78 → libicui18n_78.so
libicuuc.so.78 → libicuuc_78.so  libicudata.so.78 → libicudata_78.so
```

等长替换意味着不需要移动段表，直接改 `dynstr` 字节即可（否则要重排 ELF，风险高得多）。

### 4.2 内核树
`dshroot`（内核 + 依赖包）、`dshhome`（profile 配置）、`bin`、`runtime/etc` 打成 `assets/payload-dsh.zip`。
剔除 python/npm/git/rish 与官方自研插件（shizuku/无障碍/vscreen 等），
`dshhome/cordis.patch.yml` 替换为托管版本：禁用 sandbox、office-to-pdf、遥测，并保留 `bash-local`。

首启由 `DshManager` 流式解压到 `filesDir/dsh`（带路径穿越校验），写入 `.extracted` 标记后不再重复。

### 4.3 启动与 token
```
libdshnode.so --expose-internals dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js \
  web --host 127.0.0.1 --port 8300 --no-open
```
- `LD_LIBRARY_PATH` 指向 `nativeLibraryDir`（依赖库都在那里）；
- `DSH_HOME=files/dsh/dshhome`、`DSH_WORKSPACE`（见第 6 节）、`TMPDIR`、`TERM`、`SHELL=/system/bin/sh`；
- 从日志里正则抓 `http://127.0.0.1:<port>/?token=...`，只匹配**本次启动之后**写入的日志（按偏移过滤），
  避免复用上次已失效的 token；
- 启动前按 `dsh-node.pid` 结束上次遗留进程，避免孤儿进程占端口与内存。

### 4.4 WebView 控制台
- token URL **只能加载一次**（服务端用它换签名 cookie），重复加载会返回
  `dsh web authentication required`；因此 `DshScreen` 在 `factory` 里加载一次，
  页面完成加载后置 `tokenConsumed`，Activity 重建时改用 base URL 走 cookie；
- 等 WebView **有实际尺寸**后再 `loadUrl`，避免 0×0 时初始化渲染视口；
- 放行 `about/data/blob/javascript` 协议，实现 `onShowFileChooser`（附件），
  并注入视口修复脚本（见 [troubleshooting.md](troubleshooting.md#4-dsh-控制台按钮点击无反应)）。
- **全屏沉浸（3.12.0）**：DSH Tab 默认隐藏 App 顶栏 / 底部导航与系统状态栏、手势条
  （`WindowInsetsControllerCompat.hide(systemBars)` + `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`），
  WebView 按 `WindowInsets.displayCutout` 避让挖孔；顶部居中的「退出全屏」浮层恢复 App 界面，
  顶栏「全屏使用」按钮再次进入；返回键先退出全屏。进出全屏只改变量约束，不重建 WebView。
- **帧率约束**：视口修复只在视口尺寸变化时改高度（`ResizeObserver`），vh→px 全量换算仅首帧一次；
  WebView 用不透明底色 + `offscreenPreRaster`；页面底色回调直接改 View / 系统栏，不经 Compose 状态；
  release 包前端 console 日志按 300ms 限流；离开页面 `removeView + destroy`。

### 4.5 内置 DSH 插件 `@workbuddy/dsh-mobile`

Android 上官方 `bash` 工具必然失败（`dsh-bash-local` 执行的是 `spawn("bash", …)`，而系统只有
`/system/bin/sh`），模型也不知道这台手机的权限与工具链。宿主因此随 APK 落地一个零依赖插件：

```
assets/dsh-plugins/wb-mobile/{package.json,index.js,README.md}
        │ DshManager.doStart() → MobilePlugin.ensure()
        ▼
dsh-home/dshhome/profiles/web/node_modules/@workbuddy/dsh-mobile/   ← 必须在 profile（loader 的 baseUrl）
        │ InstalledPlugins.add() → DshPlugins.writePatch()
        ▼
dsh-home/dshhome/cordis.patch.yml 的 insert 行（id: wb-mobile）
        │ 内核启动时加载
        ▼
工具：wb_sh（/system/bin/sh）/ wb_env（体检）/ wb_shizuku（桥 → uid 2000）/ wb_notify
提示词段：workbuddy:android-environment（order 1050）
```

- `wb_sh` 走 `ctx.subprocess`（惰性获取），输出受管（48 KB + spill），带超时与取消；
- `wb_shizuku` / `wb_notify` 走宿主回环桥 `127.0.0.1:7865`（`POST /shell`、`POST /notify`，
  要求 `x-wb-token`），桥只监听回环；
- `home/bin` 里额外有 `bash`/`sh` 链接指向 `libwbnode_shim.so`，启动器把它们落到
  `/system/bin/sh`，官方 bash 工具因此也能跑（POSIX 语法）；`#!/bin/bash`、`#!/bin/sh` 的脚本同理。

设计与取舍见 [mobile-agent-plugin.md](mobile-agent-plugin.md)；离线自检：
`node android/dsh/tests/wb-mobile.selftest.mjs`。

### 4.6 虚拟屏（`vdisplay/VirtualScreen.kt` + `android/vds-helper`）

给 Agent 一块不对应物理屏幕的 Android 显示，用户继续使用真机。3.13.1 起由
**shell/root 侧的 `app_process` 服务**创建并持有显示：

```
App ──Shizuku(uid 2000/root)──> app_process -Djava.class.path=files/vds.dex \
        /system/bin com.wb.vds.Server W H DPI cache/vds
        │ 创建 VirtualDisplay(PUBLIC | OWN_CONTENT_ONLY) → ImageReader
        │ 写 cache/vds/{id,pid,meta} + frame.png（约 3fps）
        ▼
am force-stop <pkg>; am start --display <id> -n '<pkg>/<activity>'   同一 uid，先清旧任务再启动；2s 后核对 Display #
dumpsys activity activities / Display #N   核对目标包实际落在哪块屏，落在 display 0 则报「不支持副屏」
input -d <id> tap|swipe|text|keyevent           同一 uid，注入不影响真机焦点
```

- 能力层 `VirtualScreen` 暴露 `state: StateFlow`、`start/stop/validate`、`readFrame/shot`、
  `tap/swipe/key/text`、`apps/launch`；所有外部命令经 `ShizukuShell.exec` 执行。
- **为什么必须 shell/root**：Android 17 的 `SecurityManagerService.isVirtualDisplayActive`
  拒绝普通 App 把其他 App 启动到自建虚拟屏；服务成为 display owner 后，
  `am start --display` 才被允许，同时不会触发 HyperOS 的跨应用「应用跳转」确认。
- 截图不再 `screencap -d`：服务直接写 `frame.png`，App 读自己的缓存（旧实现的逻辑 id
  在 Android 17 上会报 `Display Id is not valid`）。
- `validate` 读 `pid` 并 `kill -0`，服务已死则清掉 `id/frame`，避免「运行中」假状态；
  服务独立于 App 进程，点「关闭虚拟屏」会 `pkill` 服务。
- UI：`ui/vdisplay/VirtualScreenScreen.kt`（设置 → 虚拟屏）；DSH 插件工具
  `wb_vdisplay/wb_vscreen/wb_vinput` 经宿主桥 `POST /vdisplay` 调用；
  原生助手 `MobileTools` 直接调用同一对象。
- 约束：Android 8+ 且 Shizuku 已授权；`FLAG_SECURE` 页面截图黑屏；单实例 App 会在
  真机/虚拟屏之间搬窗口。详见 [virtual-display.md](virtual-display.md)。

---

## 5. 数据层与状态

- `SettingsStore`（DataStore Preferences）持久化 `Session`：
  `serverUrl / apiKey / demo / local / themeMode / refreshSec / assistantMode`。
- `AppViewModel` 持有 `session`、`repo`、`agentClient`、`gatewayStatus` 等 `StateFlow`；
  本地模式下自动拉起网关并把 `repo`/`agentClient` 指向 `http://127.0.0.1:<port>`。
- `AssistantStore` 把会话以 JSON 存在 `filesDir/agent/sessions.json`。
- `WbRepository` 抽象远端接口，`DemoWbRepository` 提供离线演示数据，`RemoteWbRepository` 走 OkHttp。
- 页面状态用 `Loadable<T>`（`ui/state/Loader.kt`）统一 Loading/Ready/Error 三态。

---

## 6. 文件访问与工作区

```
设置 → 文件访问（MANAGE_EXTERNAL_STORAGE）
        │ 授予
        ▼
DshManager.workspaceDir() → /storage/emulated/0/WorkBuddy
        │
        ▼
DSH 的 bash / 文件 / 检索工具（以本 App UID 运行）可直接读写手机文件
```

未授权时回退到 `getExternalFilesDir()/workspace`，保证内核仍可运行，只是看不到共享存储。

这个目录同时是内核 bash / `wb_sh` 的默认 cwd（`DSH_WORKSPACE`），也是环境手册里告诉模型的工作区。

---

## 7. UI 结构

- `ui/shell/AppShell.kt`：`Scaffold` + 底部 `NavigationBar`（6 Tab）+ 路由（`Route.Main/Config/Usage/Packages/Probes/Vouchers`），
  全局 `SnackbarHost`、网关日志 `ModalBottomSheet`。
- `ui/theme/`：`WbColors`（深/浅两套语义色：`bg/surface/ink/ink2/ink3/accent/ok/warn/bad`）+ `WbDimens`。
- `ui/components/Common.kt`：卡片、分区标题、状态徽章、按钮、输入框、下拉刷新、统计网格等原子组件。
- 「助手」Tab 根据 `session.assistantMode` 渲染 `AssistantScreen`（原生）或 `DshScreen`（DSH 控制台）。

---

## 8. 无障碍工具调用链

```
用户消息 → OpenAiClient.chatStream(messages, systemPrompt)（SSE）
        → 模型输出 {"tool":"tap","text":"登录"}
        → MobileTools 执行（WbAccessibilityService 读控件树 / dispatchGesture）
        → 结果以 assistant 消息回灌 → 最多 4 轮
```

只读取控件树文本，不截图、不上传；工具结果仅保存在本机会话文件里。
