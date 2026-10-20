# WorkBuddy 一体化 APK：本地网关 + 原生 Agent

一个 App 同时提供三件事：

1. **本地网关**：把仓库里的 Go 服务端（`cmd/server`）交叉编译进 APK，App 自己拉起/停止，
   监听 `127.0.0.1`，账号、签到、任务、请求日志全部在手机里完成——**不需要另开服务器**。
2. **原生 Agent**：流式对话、多会话、模型选择、可选的「手机操作」工具（无障碍读屏/点击/输入）。
   对话直接走本地网关的 OpenAI 兼容 `/v1/chat/completions`。
3. **管理面板**：原有 Web 面板的手机版（总览 / 账号 / 任务 / 日志 / 设置 / 服务器配置 / 用量 / 套餐 / 探测 / 券码）。

> 也支持「远程服务器」模式：填写已有 `workbuddy2api` 实例地址与 API Key 即可，行为与上一版一致。

## 两种使用方式

### A. 本地网关（推荐，一次搞定）

1. 安装 APK，首次打开点「**一键启动本地网关**」。
2. App 会在私有目录生成 `config.json`（随机 API Key、只监听 127.0.0.1），
   启动内置 Go 网关并等待健康检查；成功后自动进入主界面（前台通知常驻保活）。
3. 到「账号」→「添加账号」完成 OAuth 授权（会打开系统浏览器），账号落盘在本机。
4. 到「助手」直接对话；模型来自网关 `/v1/models`。

### B. 远程服务器

在连接页填写服务器地址（如 `192.168.1.10:7863`）与 `config.json` 里的 `api_key`，点「连接远程服务器」。

> 想先看界面：点「先看看演示模式」（不联网、不起网关）。

## 功能

| Tab | 内容 |
|---|---|
| **总览** | 池状态、剩余/临期积分、快捷批量操作、模型锁池、账号健康、最近日志、服务信息 |
| **账号** | 搜索/筛选、卡片列表、底部弹层详情与单账号动作（签到/余额/暂停/恢复/禁用/解冻/移除）、FAB 添加账号（OAuth） |
| **助手** | Agent 对话：流式输出、多会话本地持久化、模型选择、系统提示词预设、手机操作工具开关、网关状态条与日志入口 |
| **任务** | 队列进度、扫描待办、执行队列、按账号接受/一键完成/领取 |
| **日志** | 运行日志（频道筛选/自动刷新/点击复制）与请求审计（指标 + 明细 + 结果筛选） |
| **设置** | 连接信息、主题、刷新间隔、服务器配置编辑器、用量、套餐、模型探测、校园券码、关于 |

### Agent 与「手机操作」

- Agent 页顶部的网关状态条可启动/重启本地网关、查看网关日志、跳转账号池与运行日志。
- 打开设置里的「手机操作」开关并授权无障碍服务后，模型可以输出工具指令：
  `{"tool":"screen"}` 读屏、`{"tool":"tap","text":"登录"}` 点击、`{"tool":"type","text":"…"}` 输入、
  `{"tool":"scroll","dir":"down"}`、`{"tool":"back"}` / `{"tool":"home"}`。
  App 执行后把结果回灌给模型继续推理（最多 4 轮）。
- 无障碍只读控件树，不截图、不上传；工具结果仅在本机会话里保存。

## 构建

### 依赖

- JDK 17/21、Android SDK 35、Gradle 8.10+
- **Android NDK**（交叉编译 Go 网关所需，r26+，本仓库用 r27.3 验证）
- Go 1.22+（网关本身）

### 1) 交叉编译内置网关（改动 Go 代码后重跑）

```powershell
pwsh android/build_gateway.ps1
# 或手动：GOOS=android GOARCH=arm64 CGO_ENABLED=1 CC=<ndk>/…/aarch64-linux-android24-clang \
#        go build -trimpath -tags timetzdata -ldflags "-s -w" -o android/app/src/main/jniLibs/arm64-v8a/libwb2api.so ./cmd/server
```

说明：
- 用 `GOOS=android + cgo`：DNS 走 Android bionic、系统证书走 `/system/etc/security/cacerts`；
- 产物必须命名为 `lib*.so` 并放在 `jniLibs/`（Android 10+ 只允许从 `nativeLibraryDir` 执行二进制）；
- `-tags timetzdata` 内嵌时区库，保证 9/21 点签到等排程使用手机本地时区；
- 当前打包 `arm64-v8a` 与 `x86_64` 两个 ABI（真机 arm64、模拟器 x86_64）。

### 2) 构建 APK

```bash
cd android
gradle assembleDebug        # 或 ./gradlew assembleDebug / Android Studio 打开 android/
```

产物：`android/app/build/outputs/apk/debug/app-debug.apk`

```bash
gradle assembleRelease      # release（当前用 debug 签名，正式分发请替换 keystore）
adb install -r dist/WorkBuddy-2.0.0.apk
```

## 架构

```
app/src/main/
├── jniLibs/{arm64-v8a,x86_64}/libwb2api.so   # 交叉编译的 Go 网关（cgo / android）
├── assets/cacert.pem                          # Mozilla CA 兜底（SSL_CERT_FILE）
├── res/xml/wb_accessibility_config.xml        # 无障碍服务配置
└── java/com/workbuddy2api/panel/
    ├── gateway/          # LocalGatewayManager（解压/配置/端口/健康检查/日志）+ 前台保活服务
    ├── data/agent/       # OpenAiClient（SSE）、AgentStore（会话持久化）、AgentModels
    ├── agent/            # MobileTools 工具执行 + WbAccessibilityService
    ├── data/             # 面板 API 客户端与模型（WbApi / Repository / Demo / AppViewModel）
    └── ui/               # theme / components / shell / connect / overview / accounts /
                          # assistant / tasks / logs / settings
```

运行路径（本地模式）：

```
App ──start──▶ libwb2api.so（127.0.0.1:7863，filesDir/gateway 为工作目录）
 │                    │
 │◀── panel/api/* ────┤ 管理面板（Kotlin 原生 UI）
 └── /v1/chat/completions（SSE）──▶ Agent 对话
```

## 验证

```bash
cd android && gradle test
```

- 27 个 JVM 单测：URL 规范化、时间/额度格式化、真实接口 JSON 解析、MockWebServer 验证鉴权与错误映射。
- `lintDebug`：0 error。
- APK 元数据：`aapt2 dump badging`（包名 `com.workbuddy2api.panel`、arm64-v8a + x86_64、launchable activity）。

## 已知限制

- 仅提供 `arm64-v8a` / `x86_64` 两种 ABI（32 位老设备装不上）。
- 本地网关首启需要几秒（Go 进程 + 状态初始化）；首次添加账号仍需系统浏览器完成 OAuth。
- Agent 的“手机操作”依赖系统无障碍服务，需用户手动开启；不做截图/视觉理解。
- 未内置 DSH（DeepSeek Harness）内核：其 runtime + 内核解压后约 460MB，且要求 `targetSdk 28`
  才能在可写目录执行 Node 二进制，与本 App 的 `targetSdk 35` 冲突；如需该能力，
  可单独安装 DeepSeekHarness，或用远程模式接入其它 Agent。
- 无后台定时保活之外的常驻逻辑；App 退到后台后 Agent 页停止轮询。

## 变更记录

- **2.0.1（2026-10-20）**：修复真机上「总览」整页报错
  `Unexpected JSON token ... in_flight_full` —— 服务端 `in_flight_full` 是账号计数（整数），
  客户端模型误声明为布尔值；已改为 `Int` 并同步提示文案。同时把内置 Go 网关按
  **16 KB page size** 对齐（`-Wl,-z,max-page-size=16384`），兼容 16 KB 内存页的新设备；
  新增真实响应解码测试（`LiveDecodeTest`）与 Agent 流式客户端测试（`OpenAiClientTest`）。
- **2.0.0**：内置网关 + 原生 Agent 一体化。

- **3.0.0（2026-10-20）**：**DSH（DeepSeek Harness）内核以独立进程接入**。
  「助手」Tab 新增「原生助手 / DSH 内核」双模式；DSH 以独立 Node 进程运行完整
  Agent（会话/工具/终端），WebView 承载控制台。工程上：
  - Node 47MB + 10 个依赖库打进 `jniLibs`（Android 10+ 唯一可执行位置），
    ELF 的 DT_NEEDED / DT_VERNEED / DT_SONAME 做了版本号名改写
    （`libcrypto.so.3` → `libcrypto_3.so` 等，bionic 要求 soname 一致）；
  - 内核树（28k 文件）打包为 `assets/payload-dsh.zip`（62.7MB，剔除 python/npm/git
    与官方自研插件），首启解压约 3 秒；
  - 打包/补丁工具：[dsh/pack_dsh.ps1](dsh/pack_dsh.ps1)。

- **3.1.0（2026-10-20）**：DSH 控制台可用性修复。
  - **WebView 按钮点不动**：该机 WebView（Chromium 149）里 `vh/dvh` 单位恒为 0
    （连 `about:blank` 都如此），DSH 前端依赖 `html{height:100%}` 链，导致整页
    高度塌陷为 0、按钮命中区跑到视口外。现在页面加载后注入修复：把
    `html/body/#root` 高度钉成实际像素，并把样式表里的 `vh` 就地换算为 `px`，
    SPA 后续插入的样式由 `MutationObserver` 兜底。
  - **助手引擎切换挪到「设置 → 助手引擎」**：原来放在助手页顶部，`Box` 没有
    状态栏内边距，会顶到状态栏；现在改为设置项并持久化（`Session.assistantMode`）。
  - WebView 补齐 `onShowFileChooser`（附件按钮）、放宽 `shouldOverrideUrlLoading`
    （不再拦截 `javascript:/data:/blob:`，避免前端按钮失效）、控制台日志与
    主框架错误日志（`WbDshWeb` 标签），debug 包开启 WebView 远程调试。

- **3.2.0（2026-10-20）**：新增「所有文件访问」授权，DSH 可检索/操作手机文件。
  - Manifest 增加 `MANAGE_EXTERNAL_STORAGE`（工具忽略 `ScopedStorage` 提示）与
    低版本的 `READ/WRITE_EXTERNAL_STORAGE`、`requestLegacyExternalStorage`；
  - **设置 → 文件访问** 新增状态卡片：显示「已授予／未授予」，一键跳系统
    「所有文件访问」页（Android 11+），Android 10 及以下走运行时存储权限；
    从系统页返回自动刷新状态；
  - DSH 页在未授权时顶部显示提示条，可直达授权页；
  - DSH 工作区：授权后 `DSH_WORKSPACE` 切换为共享目录
    `/storage/emulated/0/WorkBuddy`（bash、文件、检索工具可直接读写手机文件），
    未授权时退回应用专属目录。已在运行的内核需重启一次生效。

- **3.3.0（2026-10-20）**：宿主层能力补齐（参考 deepseek-harness-android-app）。
  - **系统 CA 合成**：启动前把 `/system/etc/security/cacerts/*` 合并为 `runtime/etc/cacert-system.pem`
    （带 stamp 缓存），`SSL_CERT_FILE` 优先用它，企业代理/自签 CA 可用；失败回退内置 Mozilla bundle。
  - **WebView 兼容自检**：读取 Chromium 主版本，低于 80 时在 DSH 页提示（仍启用视口修复）；
    「设置 → 运行环境」展示版本与 ABI。
  - **状态栏跟随页面底色**：新增 `wbshell.onBg` JS 桥 + `MutationObserver`，DSH 控制台切换深/浅主题时
    状态栏/导航栏图标与底色同步（Android 11+ 用 `setSystemBarsAppearance`）。
  - **电池优化白名单**：`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + 一键跳系统页，状态在运行体检里展示。
  - **更新检查**：读取 GitHub Release latest 与本地版本比较，提供下载入口（不静默安装）。
  - **内核树拆分布局**：`dshroot/`、`bin/` 外置到 `/storage/emulated/0/WorkBuddy/dsh`（省内部存储），
    `dshhome/`、`runtime/`、凭证与日志留在内部 `filesDir/dsh-home`——因为 stores 会 `chmod 600`，
    而 `/sdcard` 的 FUSE 不支持 chmod/所有权（实测外置 home 会导致 credentials 插件拒绝启动）。
    旧合并树首次启动自动拆分迁移。
  - **运行体检卡片**：设置页聚合电池白名单 / WebView 版本 / ABI / 内核树位置 / 版本更新。
  - 单测新增 `EnvAndUpdateTest`（版本比较、UA 解析）。

- **3.4.0（2026-10-20）**：P2 宿主能力（插件开关 / 安全模式 / 备份 / 我的补丁）。
  - **插件开关**（`dsh/DshPlugins.kt`）：扫描内核树里的 `@deepseek-ai/dsh-*` 包，
    在「设置 → DSH 工具」逐个启用/禁用；`cordis.patch.yml` 改为每次启动由 App 重写
    （`WorkBuddy-managed-patch: v3`），内置策略（sandbox/office-to-pdf/遥测）不可关。
  - **安全模式**（`DshManager.startSafeMode`）：连续启动失败时，把 `dshhome/profiles` 旁置并
    从 payload 恢复出厂 profile 再启动，会话/凭证不动；DSH 失败页与设置页都有入口。
    失败次数与最后一次错误记录在 `wb_dsh_state`。
  - **备份 / 还原**（`dsh/DshBackup.kt`）：经 SAF 导出/导入 zip，内容含 `dshhome`（跳过日志）、
    原生助手会话、网关配置、我的补丁；导入前自动把现有 `dshhome` 备份为 `dshhome.bak-<时间>`。
  - **我的补丁**（`dsh/UserPatches.kt`）：首次解压记录 `dshroot` 全量 sha256 基线（约 4.8 MB），
    「保存修改为补丁」把与出厂不同的文本/配置/图片（单个 ≤8 MB）存入 `user-patch/`，
    启动前自动回放；「清空补丁」删除补丁并让内核树重新解压。
  - 新增单测 `DshPluginIdTest`（包名 → 插件 id 映射、内置禁用策略）。

- **3.6.0（2026-10-20）**：社区插件一键安装 + DSH 通知插件 + 扩展中心（npm CLI）。
  - **内置 npm**：payload 增加 npm 目录（9.1 MB 未压缩 / +3.1 MB APK），内核可用它安装插件与 CLI。
  - **社区插件一键安装**（`dsh/PluginInstaller.kt`）：扩展能力面板里对 12 个精选插件
    直接「安装/卸载」，走 `node npm-cli.js install --prefix dshroot/lib/node_modules/@deepseek-ai/dsh`，
    装完自动写入 `cordis.patch.yml` 的 insert 列表，重启内核生效。
  - **DSH 通知插件**（`dsh/NotifyPlugin.kt`）：内置 `@workbuddy/dsh-tool-notify`，
    Agent 可直接调用 `notify` 工具；插件 POST 到宿主回环桥 `127.0.0.1:7865`（随机 token），
    由 App 发系统通知。插件安装位置与 bash-local 同级（`@deepseek-ai/dsh/node_modules/`）。
  - **宿主回环桥**（`dsh/LocalBridge.kt`）：只监听 127.0.0.1、要求 `x-wb-token`，
    目前提供 `/notify`、`/health`，后续可扩展 `/shell`。
  - **扩展中心（CLI 工具）**：typescript / tsx / prettier / eslint / http-server / nodemon
    一键装到 `home/tools`，其 `.bin` 已加入内核 PATH。

- **3.7.0（2026-10-20）**：修复「DSH 内安装社区插件报错」，内置可执行的 npm/pnpm 入口。
  - **根因**：DSH 插件安装走 `spawn("pnpm")`，而 App 数据目录在 Android 10+ **禁止 exec**
    （SELinux `app_data_file`），因此报 `spawn pnpm EACCES`；payload 里自带的 pnpm 又硬编码
    Termux 前缀 `/data/data/com.termux`，即使能执行也会立刻失败。
  - **修复**：新增 ELF 启动器 `jniLibs/*/libwbnode_shim.so`（NDK 编译，位于 nativeLibraryDir，
    允许 exec），按 `argv[0]` 决定运行 `pnpm.mjs` / `npm-cli.js` / node 本体；
    `home/bin` 里创建 `pnpm`/`npm`/`node` 三个符号链接指向它，并把 `home/bin` 放在内核 PATH 首位。
    链接每次启动重建（应用更新后 nativeLibraryDir 路径会变）。
  - **pnpm 运行时**：payload 自带的 Termux pnpm 不可用，改为首次启动用内置 npm 现装
    `home/pnpmjs/node_modules/pnpm/bin/pnpm.mjs`（后台线程，失败不影响内核）。
  - 内核环境新增 `WB_NODE`、`WB_DSH_HOME`，供启动器与通知脚本使用。
  - **真机验证**：`pnpm --version` → 12.14.0；`npm --version` → 11.20.0；
    在 `dshhome/profiles/web` 里 `pnpm add dsh-cost-meter` 成功（1.4s，装好后已卸载）。

### 工具链（参考项目 19 项系统扩展）现状与路线

已支持：npm 生态 CLI（typescript / tsx / prettier / eslint / http-server / nodemon），
一键装到 `home/tools`，`.bin` 进入内核 PATH。

未支持（Python / Go / Rust / Clang / OpenJDK / Ruby / PHP / Perl / Lua / SQLite / FFmpeg /
ImageMagick / OpenSSH / ADB / Vim 等 Termux 包）：**不能直接解包就用**。原因是 Termux 二进制在
编译期写死前缀 `/data/data/com.termux/files/usr`（DT_RUNPATH 与 CPython 等程序的 sys.prefix 均为绝对路径），
搬到应用私有目录后既找不到库也找不到自己的资源，且我们的前缀更长、无法原位改写字符串。

要正确实现需要按顺序补三件事：
1. **bundled patchelf**（NDK 交叉编译）—— 安装时把每个 ELF 的 `DT_RUNPATH`/`DT_NEEDED` 指向新前缀；
2. **依赖闭包解析** —— 解析 `Packages.xz`（需 xz 解码，可用 commons-compress）得到 `Depends` 递归下载，
   逐包 SHA-256 校验后原子发布；
3. **前缀重写** —— 文本/脚本里的 `$PREFIX` 与绝对路径改写，shebang 修复；
   对 CPython 这类把 prefix 编进二进制的包，需要额外补 `PYTHONHOME`/`--prefix` 起始脚本。

- **3.8.0（2026-10-21）**：扩展中心 —— Termux 系统工具链安装（19 项）。
  - **重定位原理（实测）**：Termux 二进制的 `INTERP` 是 `/system/bin/linker64`（Bionic，可直接跑），
    但 `DT_RUNPATH` 写死 `/data/data/com.termux/files/usr/lib`；安装时对每个 ELF 跑
    `patchelf --set-rpath <我们的 lib>`，文本文件做前缀替换，即完成重定位。
  - **安装管线**（`dsh/TermuxRepo.kt` + `dsh/DebExtractor.kt` + `dsh/Toolchain.kt`）：
    拉取 apt `Packages` 索引（TUNA → USTC → BFSU → 官方，UA 伪装成 apt，否则 TUNA 返回 403）→
    递归解析 `Depends` 闭包 → 逐包下载（带重试）→ SHA-256 校验 → ar/deb + tar.xz 解包 →
    剥掉 Termux 前缀落到 `home/toolchain/usr` → patchelf 重写 RUNPATH + 文本前缀替换 → 写安装标记。
  - **patchelf** 用 NDK 交叉编译（`android/dsh/build-patchelf.ps1`，16 KB 对齐），随 APK 打包为
    `libwbpatchelf.so`；启动器 `libwbnode_shim.so` 的构建脚本 `android/dsh/build-shim.ps1` 也入库。
  - **内核环境**：`PREFIX` / `TERMUX_PREFIX`、`GIT_EXEC_PATH`、`GIT_TEMPLATE_DIR`；
    若装了 ca-certificates 则注入 `GIT_SSL_CAINFO`/`CURL_CA_BUNDLE`（libcurl 里 CA 路径是编译期写死的），
    装了 python 则注入 `PYTHONHOME`。`home/toolchain/usr/bin` 已在内核 PATH 中。
  - **真机验证**：`git`（14 个包，212 个 ELF 改 RUNPATH，59 个文本改前缀）→ `git version 2.56.0`，
    `git init` / `commit` 正常；`sqlite`（5 个包）→ `sqlite3 3.54.1`，`select 1+1` 返回 2。
    HTTPS 远程操作受本机网络限制（github/gitee 不可达），TLS 与 CA 链路已正常（不再报 trust anchor）。
  - **界面**：设置 → 扩展能力 → 系统工具链（19 项），逐项安装/卸载并显示实时进度。
  - **已知限制**：个别包把前缀编进二进制（如 CPython 的 `sys.prefix`），需按包补环境变量或启动脚本；
    24 个包已跑通通用管线，其余按需逐个验证。
