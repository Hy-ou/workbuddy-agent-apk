# 开发记录（CHANGELOG）

> 版本号对应 `android/app/build.gradle.kts` 的 `versionName`；日期为开发/验证时间（Asia/Shanghai）。
> 详细问题排查过程见 [docs/troubleshooting.md](docs/troubleshooting.md)。

---

## 3.10.1 — 2026-10-25

### 修复：对话页「添加文件」点击后不弹系统选择器

**现象**：App 内 DSH 控制台对话页，点输入框左下角「+」→ 菜单里的「文件」后，系统文件选择器
完全不出现——页面没有报错、没有 toast，也没有任何请求。

**链路**（先厘清，否则容易改错地方）：DSH 网页里**没有独立的附件按钮**，只有两条入口——
「+」菜单的 `file` 动作，以及把文件拖进页面。点菜单项时前端走
`ctx.filePicker.open()` → `input[type=file].click()`（`@deepseek-ai/dsh-client-ui-conversation`
0.2.1-rc.2 的 `bindFilePicker`），之后是宿主的 `WebChromeClient.onShowFileChooser` → 系统选择器
Activity。前两步在前端，后两步在宿主。

**根因**：宿主的 `onShowFileChooser` 只有一条路径 `params.createIntent()`，任何一步不成立
（`params` 为空、Intent 不可启动、定制 ROM 裁掉了对应 DocumentsUI、`launch()` 抛异常）都走
`catch { return false }`。WebView 收到 `false` 就**静默不弹窗**，连日志都没有，于是前端看到的
就是「点了没反应」。附带两个同链路的隐藏缺陷：结果只读 `data.data`（多选时系统只填
`clipData`，等于「选多个 = 什么都没选」）；上一个 callback 没释放时会让后续选择器永久不弹。

**修复**（`ui/dsh/DshScreen.kt`）：

- **兜底链**：`params.createIntent()` → `ACTION_OPEN_DOCUMENT` → `ACTION_GET_CONTENT`，带
  `EXTRA_ALLOW_MULTIPLE` / `EXTRA_MIME_TYPES` / `FLAG_GRANT_READ_URI_PERMISSION`，逐条 try，
  直到某一条真正 launch 成功；全部失败才把 `null` 还给 WebView。
- **结果收集**：新增 `collectPickedUris()`，先读 `clipData` 全部项再读 `data.data` 去重，多选可用。
- **不再挂起 callback**：新请求先释放上一个，并用 `DisposableEffect` 在页面销毁时归还。
- **`FILE_PROBE_JS`**：把 React 用 `hidden` 属性渲染的 file input 从 `display:none` 改成 1px
  不可见块（个别 ROM 的 Blink 会忽略对 `display:none` 输入框的程序化 `click()`），并在每次点击
  时输出 `[wb] file input click ...`。
- **全链日志**：`WbDshFile`（宿主回调入参、每条 Intent 的启动结果、选择结果条数）、
  `WbDshWeb`（页面 console，含上面的探针）。

**验证**（真机，尚未验证）：`adb logcat -s WbDshFile WbDshWeb`，再点一次「+」→「文件」。三种组合
分别对应三种根因：有 `[wb] file input click` 无 `onShowFileChooser` → WebView 没回调；有
`onShowFileChooser` + 全部 `launch ... -> false` → 设备没有可用选择器 Activity；有
`picker result ... uris=N` → 宿主侧已通，问题回到前端。

- 版本 3.10.1 / versionCode 22。

---

## 3.10.0 — 2026-10-21

### 新增：内置 DSH 插件 `@workbuddy/dsh-mobile`（Android 执行通道 + 环境手册）

**背景**：真机上 Agent 频繁调用 bash 等工具报错。根因有三条，都在宿主侧：

1. `@deepseek-ai/dsh-bash-local` 执行的是 `spawn("bash", "-c", …)`，而 **Android 没有 bash**
   （只有 `/system/bin/sh` = mksh + toybox）；`bash-sandbox`/`pwsh-sandbox` 在托管补丁里本来就禁用，
   于是「唯一的 shell 工具」正好是跑不起来的那个。
2. 即使有二进制也 exec 不了：`untrusted_app` 不能 exec `app_data_file`，内核树又落在 /sdcard（noexec）。
3. 内核的 system prompt 里没有任何「这台手机有什么」的事实（文件权限、PATH、工具链、Shizuku、npm），
   模型只能按 Linux 桌面习惯猜，猜错即报错，还会原样重试。

**选择**：写成插件而不是 Agent 预设 —— 预设只能在已有插件里挑组合，既造不出 shell 通道，
也无法把随环境变化的运行时事实写进提示词（对比见 docs/mobile-agent-plugin.md）。

- **插件本体** `android/dsh/assets/dsh-plugins/wb-mobile/`（零依赖 ESM，只 import `node:`）：
  - `wb_sh`：`/system/bin/sh -c`，返回 exitCode/stdout/stderr/timedOut，48 KB 输出上限 + 溢出 spill；
  - `wb_env`：体检（可用/不可用命令、PATH、工具链与 npm CLI 清单、共享存储可写性、桥/Shizuku 状态）；
  - `wb_shizuku`：经宿主回环桥以 uid 2000 执行（pm/input/screencap/settings/dumpsys）；
  - `wb_notify`：系统通知；
  - 系统提示词段 `workbuddy:android-environment`（order 1050）：文件权限与路径约定、POSIX-only 语法、
    toybox 与 GNU 差异、npm/pnpm 位置、工具链安装位置、Shizuku 边界、「报错先读 stderr、禁止原样重试」。
- **宿主安装** `dsh/MobilePlugin.kt`：每次启动内核前从 assets 复制到
  `dshhome/profiles/web/node_modules/@workbuddy/dsh-mobile` 并登记到 `InstalledPlugins`，
  由 `syncManagedPatch` 写进 `cordis.patch.yml` 的 insert 行（id `wb-mobile`）。
  必须放 profile：DSH loader 用 profile 作 baseUrl 解析包名，放内核树会 ERR_MODULE_NOT_FOUND。
- **bash 兜底**：`home/bin` 增加 `bash`/`sh` 链接 → `libwbnode_shim.so`，`shim.c` 把它们落到
  `/system/bin/sh`（含 `#!/bin/bash`、`#!/bin/sh` shebang 解析），官方 bash 工具因此也能跑；
  顺带删掉 shebang 分支里那条会以无参方式 exec 解释器的兜底调用。
- **宿主桥扩展** `dsh/LocalBridge.kt`：新增 `POST /shell`（Shizuku，未授权返回 409 + 原因）；
  `POST /health` 现在返回 `shizuku.available/granted`，供 `wb_env` 体检。
- **修复宿主桥中文正文卡死**（真机复现）：请求体原先按「字符」读，而 `Content-Length` 是字节数，
  UTF-8 中文正文永远读不满 → 连接挂到 5 s 超时、客户端只看到 fetch failed（纯 ASCII 正常）。
  改为按字节读头部与正文（并给 body 加 1 MB 上限），通知/命令正文都可以是中文。
- **构建**：`app/build.gradle.kts` 增加 `sourceSets.main.assets.srcDir("../dsh/assets")`，插件源码单点维护。
- **自检** `android/dsh/tests/wb-mobile.selftest.mjs`：不需要设备，校验 4 个工具的 JSON Schema
  （DSH 允许的子集）、渲染与失败路径；已用内核自带的 `assertSupportedJsonSchema` 交叉验证通过。
- **真机坑 1：启动器必须重新编译**。`bash`/`sh` 回退写在 `shim.c` 里，若沿用旧
  `libwbnode_shim.so`（例如从上一版 APK 里取 .so），`bash` 仍会报 `wbexec: target not found for bash`（exit 127）。
  改动 `shim.c` 后必须跑 `pwsh android/dsh/build-shim.ps1`（NDK clang，两个 ABI）。
  实测：新启动器在应用 uid 下 `bash -c 'echo hi'` → `argv0=/system/bin/sh`、exit 0。
- **真机坑 2：PTC 模式里不能用 `execSync`**。内核的 node 是 Termux 构建，
  二进制里硬编码了默认 shell `/data/data/com.termux/files/usr/bin/sh`（`grep -a -c termux` = 22），
  该路径在 Android 上不存在，`run_code` 里任何 `child_process` 调用都是
  `spawnSync ... ENOENT`。环境手册已按 PTC 重写：先声明「只能直接调 run_code，其它工具用
  `await tools.xxx(...)`」，并给出 `tools.wb_env`/`tools.wb_sh` 的示例。
- 版本 3.10.0 / versionCode 21。

---

## 3.9.3 — 2026-10-21

### 修复

- Web 端 `spawn pnpm EACCES` 真正根因：内核 PATH 缺 `home/bin`，`pnpm` 落到 /sdcard 上
  noexec 的 Termux 版；改为 PATH 首位固定 `home/bin`。
- 验证：`which pnpm` → home/bin/pnpm；`pnpm view dshmarket …` 正常返回插件信息。
- 版本 3.9.3 / versionCode 20。

---
## 3.9.2 — 2026-10-21

### 修复

- 设置页卸载失败：`pnpm remove` 不支持 `--registry`（Unknown option），改为仅安装时传。
- DSH 启动 `Cannot find package`：卸载后 patch 残留 insert；改为内核启动时以磁盘为准
  丢弃失效登记，卸载时无条件清登记并重写 patch。
- 验证：卸载 dsh-whale-widget、安装 dshmarket 均成功；重启内核无 warning。
- 版本 3.9.2 / versionCode 19。

---
## 3.9.1 — 2026-10-21

### 修复

- DSH Web 端「添加插件」`spawn pnpm EACCES`：
  - 启动器 pnpm 分支补上 `pnpm10/bin/pnpm.cjs`（3.9.0 的路径替换未生效）；
  - 启动时删除内核 `bin/` 里 noexec 的 Termux `pnpm/npm/npx`，避免 PATH 回退命中。
- 验证：应用进程内 `spawnSync("pnpm", ["view","dshmarket",…])` → status 0，
  返回 `{"name":"dshmarket","version":"1.66.45"}`；pnpm 10.34.7。
- 版本 3.9.1 / versionCode 18。

---
## 3.9.0 — 2026-10-21

### 修复

- **工具链无法执行**：应用进程不能 exec 私有目录二进制（SELinux）；改为
  `home/bin/*` → jniLibs 启动器 → `/system/bin/linker64 <目标>` 加载；脚本解析 shebang 映射前缀。
- **换设备插件装不上**：pnpm 内置进 payload（pnpm10，纯 JS），不再依赖首启联网安装；
  升级时 `ensureExtraPayload()` 增量补解压，不覆盖用户数据。
- **DSH Web 端安装插件报 UNEXPECTED_STORE**：内核每次启动自动对齐
  `profiles/web/node_modules/.modules.yaml` 的 storeDir。
- 工具链新增 `verifyCmd`，装完立即自检并回显。

### 验证

- 应用进程内：git 2.56.0 / sqlite3 3.54.1 / Python 3.14.8（print(1+1)→2，sys.prefix 正确）。
- 社区插件 dsh-cost-meter、dsh-free-search 安装成功并写入 patch。
- 16 KB 对齐 16/16 通过；release 116.7 MB；versionCode 17。

---
## 3.8.2 — 2026-10-21

### 修复

- 启动警告 `1 entry did not activate`：`cordis.patch.yml` 残留 wb-notify；改为按 id 清理
  （`InstalledPlugins.removeById`）。
- 社区插件安装失败（四处根因）：
  1. 改到 `dshhome/profiles/web` 安装（内核树在 /sdcard 上被 FUSE 拒绝，且 DSH 只认 profile）；
  2. pnpm 固定 10.x + `--omit=optional --ignore-scripts`（避开原生 @pnpm/exe 的 EACCES）；
  3. 安装前对齐 `.modules.yaml` 的 storeDir 到 `store/v10`，并去掉 XDG_DATA_HOME；
  4. `pnpm add/remove` 加 `--workspace-root`。
- 真机验证：应用内安装 dsh-cost-meter 成功，patch 自动登记，重启后日志显示插件已加载、
  无 warning。
- 版本 3.8.2 / versionCode 16。

---
## 3.8.1 — 2026-10-21

### 修复：DSH 启动被 UI 协程取消（The coroutine scope left the composition）

- DshManager 增加自有 CoroutineScope + startAsync/restartAsync/startSafeModeAsync；
- doStart 包 NonCancellable，CancellationException 直接抛出不再记为失败；
- UI 全部改为调用 async 门面。
- 真机验证：启动后 3 秒切走标签页，内核仍正常启动。
- 版本 3.8.1 / versionCode 15。

---
## 3.8.0 — 2026-10-21

### 新增：扩展中心（Termux 工具链 19 项）

- `dsh/TermuxRepo.kt`：apt Packages 索引（4 镜像 + apt UA）、Depends 闭包、deb 下载（重试 + SHA-256）。
- `dsh/DebExtractor.kt`：ar → data.tar.xz → tar 解包，剥离 Termux 前缀，保留符号链接/可执行位。
- `dsh/Toolchain.kt`：19 项目录、patchelf 重写 RUNPATH、文本前缀替换、安装标记。
- patchelf 用 NDK 交叉编译为 `libwbpatchelf.so`（16 KB 对齐），构建脚本入库。
- 内核环境注入 PREFIX/GIT_EXEC_PATH/GIT_TEMPLATE_DIR/GIT_SSL_CAINFO/CURL_CA_BUNDLE/PYTHONHOME。
- 设置 → 扩展能力 → 系统工具链（19 项）逐个安装/卸载，带实时进度。

### 验证

- Git：14 包，212 ELF / 59 文本重定位 → `git version 2.56.0`，init/commit 正常。
- SQLite：5 包，220 ELF / 6 文本 → `sqlite3 3.54.1`，`select 1+1` → 2。
- 版本 3.8.0 / versionCode 14；release 112.4 MB；16 KB 对齐自查通过（16 个库）。

---
## 3.7.1 — 2026-10-20

### 修复：16 KB 页对齐告警

- `libwbnode_shim.so` 改用 `-Wl,-z,max-page-size=16384` 编译（原为 4 KB 对齐）。
- APK 内 15 个 arm64 库全部核查为 16 KB 对齐。
- 新增 `android/dsh/check-16kb.ps1` 校验脚本（CI 可用，失败返回 1）。
- 版本 3.7.1 / versionCode 13。

---
## 3.7.0 — 2026-10-20

### 修复：DSH 内安装社区插件报错（`spawn pnpm EACCES`）

- 根因：DSH 插件安装 `spawn("pnpm")`，Android 10+ 禁止执行应用数据目录文件；且 payload 自带 pnpm
  硬编码 Termux 前缀 `/data/data/com.termux`。
- 修复：新增 NDK 编译的 ELF 启动器 `libwbnode_shim.so` 放入 jniLibs（nativeLibraryDir 允许 exec），
  按 `argv[0]` 分发 pnpm.mjs / npm-cli.js / node；`home/bin` 建 `pnpm`/`npm`/`node` 符号链接并置 PATH 首位，
  每次启动重建；pnpm 运行时首次启动由内置 npm 现装到 `home/pnpmjs`。
- 内核环境新增 `WB_NODE`、`WB_DSH_HOME`。

### 验证（真机）

- `pnpm --version` → 12.14.0，`npm --version` → 11.20.0。
- `dshhome/profiles/web` 内 `pnpm add dsh-cost-meter` 成功（1.4s），验证后已卸载。
- 版本 3.7.0 / versionCode 12；release 111.1 MB。

### 未交付

- 19 项 Termux 系统工具链：需 bundled patchelf + 依赖闭包解析 + 前缀重写，路线写入 README 与发布说明。

---
## 3.6.1 — 2026-10-20

### 修复

- DSH 侧通知从「插件 insert」改为「node 脚本 + `WB_NOTIFY`/`WB_BRIDGE_TOKEN` 环境变量」：
  上游解析器只认自带作用域的插件包，第三方包 insert 会报 `Cannot find package`。
- 真机验证：`POST http://127.0.0.1:7865/notify` 返回 `{"ok":true}`，
  通知栏出现 `wb_agent_notify` 渠道通知。
- 版本 3.6.1 / versionCode 11。

---
## 3.6.0 — 2026-10-20

### 新增

- **内置 npm**：payload 增加 `npm/`（9.1 MB 未压缩，APK +3.1 MB），解压到 `dsh-home/npm`。
- **社区插件一键安装**（`dsh/PluginInstaller.kt`）：`node npm-cli.js install <pkg>
  --prefix dshroot/lib/node_modules/@deepseek-ai/dsh`，装完自动 insert 到 `cordis.patch.yml`；
  支持卸载；UI 显示进度与失败原因。
- **DSH 通知插件**（`dsh/NotifyPlugin.kt`）：`@workbuddy/dsh-tool-notify` 注册 `notify` 工具，
  经宿主回环桥（`dsh/LocalBridge.kt`，127.0.0.1:7865 + `x-wb-token`）发系统通知；
  插件位置与 bash-local 同级（`@deepseek-ai/dsh/node_modules/`）。
- **扩展中心（CLI 工具）**：typescript/tsx/prettier/eslint/http-server/nodemon 一键装到
  `home/tools`，`.bin` 加入内核 PATH。
- 版本 3.6.0 / versionCode 10。

### 验证

- `testDebugUnitTest` + `lintDebug` 通过；真机确认 npm 已解压、notify 插件文件写入、
  `cordis.patch.yml` 含 `wb-notify` insert；release APK 109.9 MB。

---
## 3.5.0 — 2026-10-20

### 新增：通知、Shizuku 特权、扩展能力面板（参考 deepseek-harness-android-app 与 DSH Mobile）

- **AI 通知**：`util/NotificationCenter.kt` + 原生助手 `notify` 工具；只依赖通知权限。
- **Shizuku 免 root 特权**：`util/ShizukuShell.kt`（可用性/授权/命令执行/截屏），
  助手 `shell` 工具可 `pm install`、`input tap`、`screencap`、`settings put`；
  未安装 Shizuku 时优雅降级。Manifest 增加权限与 `ShizukuProvider`。
- **扩展能力面板**：通知/Shizuku 状态、工具链状态（node/npm/git/python）、
  社区插件精选清单（12 个，复制包名到 DSH 插件面板安装）。
- 依赖：`dev.rikka.shizuku:api:13.1.5`、`provider:13.1.5`（`newProcess` 反射调用）。

### 边界说明（未包含）

- 参考项目的「扩展中心」19 项工具链安装（Python/Go/Rust/… + Termux 镜像 + 依赖闭包 + 原子发布）
  与「社区插件一键安装」需要内核内置 npm/pnpm/git（当前精简包已剔除，约 +120 MB），
  以及包管理器级实现，属于后续独立迭代；本期只提供清单与状态，不提供静默安装。
- DSH 侧的 `notify` 工具需要额外插件包（本期只在原生助手提供）。

### 验证

- `testDebugUnitTest` + `lintDebug` 通过；release APK 106.8 MB；真机安装成功。

---
## 3.4.0 — 2026-10-20

### 新增：P2 宿主能力

- **插件开关**（`dsh/DshPlugins.kt`）：扫描 `@deepseek-ai/dsh-*` 包，UI 逐个开关；
  `cordis.patch.yml` 由 App 每次启动重写（`WorkBuddy-managed-patch: v3`），内置策略仍不可关。
- **安全模式**（`DshManager.startSafeMode`）：`dshhome/profiles` 旁置 + 从 payload 恢复出厂 profile，
  保留会话/凭证；记录连续失败次数与最后错误；DSH 失败页与设置页双入口。
- **备份/还原**（`dsh/DshBackup.kt`）：SAF 导出/导入 zip（dshhome、助手会话、网关配置、我的补丁），
  导入前自动备份现有数据。
- **我的补丁**（`dsh/UserPatches.kt`）：内核树 sha256 基线 + 改动捕获 + 启动回放 + 一键回出厂。
- 新增 `DshPluginIdTest`。

### 验证

- 真机：patch v3 生成、`.baseline.tsv`（4.8 MB）生成、内核正常启动。
- `testDebugUnitTest` + `lintDebug` 通过；release APK 106.8 MB。

---
## 3.3.0 — 2026-10-20

### 新增：宿主层能力补齐（参考 deepseek-harness-android-app）

- **系统 CA 合成**（`dsh/SystemCaBundle.kt`）：把 `/system/etc/security/cacerts/*` 合并为
  `runtime/etc/cacert-system.pem`（stamp 缓存），`SSL_CERT_FILE` 优先使用，企业代理/自签 CA 可用。
- **WebView 兼容自检**：读取 Chromium 主版本，<80 时在 DSH 页提示；「运行环境」展示版本与 ABI。
- **状态栏跟随页面底色**：`wbshell.onBg` JS 桥 + `MutationObserver`，控制台切换主题时系统栏同步。
- **电池优化白名单**：新增权限与一键跳转，状态在运行体检中展示。
- **更新检查**（`util/UpdateChecker.kt`）：GitHub Release latest 与本地版本比较，提供下载入口。
- **内核树拆分布局**：`dshroot/`、`bin/` 外置到 `/storage/emulated/0/WorkBuddy/dsh`；
  `dshhome/`、`runtime/`、凭证与日志留在内部 `filesDir/dsh-home`（`/sdcard` 不支持 `chmod`，
  实测外置 home 会让 `dsh-credentials-local` 拒绝启动）。旧合并树首启自动拆分迁移。
- **运行体检卡片**：设置页聚合电池白名单 / WebView / ABI / 内核树位置 / 版本更新。
- 修复：迁移前先结束遗留 node（否则其 cwd 指向旧目录导致删不掉旧树）；带 `tab/mode` extra 启动时强制切页。

### 验证

- `testDebugUnitTest`（新增 `EnvAndUpdateTest`）+ `lintDebug` 通过；release APK 106.8 MB。

---
## 3.2.0 — 2026-10-20

### 新增：所有文件访问
- `AndroidManifest.xml` 增加 `MANAGE_EXTERNAL_STORAGE`（`tools:ignore="ScopedStorage"`）、
  `READ_EXTERNAL_STORAGE`（maxSdk 32）、`WRITE_EXTERNAL_STORAGE`（maxSdk 29），
  并开启 `android:requestLegacyExternalStorage="true"`。
- 新增 `dsh/StorageAccess.kt`：
  - `hasAllFilesAccess()`：Android 11+ 走 `Environment.isExternalStorageManager()`，更低版本查读写权限；
  - `allFilesAccessIntent()`：`ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` → 全局列表页 → 应用详情页三级兜底；
  - `sharedRoot()` / `sharedWorkspaceName()`：统一共享存储约定（`/storage/emulated/0/WorkBuddy`）。
- 新增 `ui/components/StorageAccessCard.kt`：
  - `AllFilesAccessCard()`：设置页卡片，状态徽章 + 说明 + 授权按钮，回到前台自动刷新；
  - `AllFilesAccessBanner()`：DSH 页未授权时的一行提示 + 去授权。
- `SettingsScreen` 增加「文件访问」分区；`DshScreen` 顶部挂载提示条。
- `DshManager.workspaceDir()`：授权后 `DSH_WORKSPACE` 使用 `/storage/emulated/0/WorkBuddy`，
  未授权回退应用专属目录；卡片内提示「重启内核生效」。

### 验证
- 真机（M332BF / Android 15）：未授权 → 提示条可见；`appops allow` 后 → 卡片「已授予」、
  提示条消失、`/storage/emulated/0/WorkBuddy` 由 App 创建并被 DSH 用作工作区。
- `testDebugUnitTest` + `lintDebug` 通过；release APK 106.7 MB。

---

## 3.1.0 — 2026-10-20

### 修复：DSH WebView 按钮点击无效
- 现象：DSH 控制台能渲染，但所有按钮点击无响应。
- 排查（WebView 远程调试 / CDP）：
  - `document.documentElement.clientHeight` = 652、`Page.getLayoutMetrics` 视口正常；
  - 但 `height:100vh` 实测为 **0**（连 `about:blank` 也为 0）→ 该机内核（Chromium 149）初始包含块高度为 0；
  - `html/body/#root` 高度链塌陷为 0，`elementFromPoint(按钮中心)` 返回 `HTML`，按钮不在命中树里。
- 修复：页面加载后注入 `VH_FIX_JS`
  - 把 `html/body/#root` 高度钉成实际像素（`!important`）；
  - 遍历样式表把 `vh/dvh/svh/lvh` 就地换算为 `px`（含 `@media` 内嵌规则）；
  - `MutationObserver` 兜底处理 SPA 后续插入的样式；`onPageFinished` 后 1.2 s 再跑一次。
- 附带修复：
  - `shouldOverrideUrlLoading` 放行 `about/data/blob/javascript`，避免前端按钮被拦截；
  - 实现 `onShowFileChooser`（附件按钮可弹系统选择器）；
  - 增加 `WbDshWeb` 控制台/HTTP 错误/主框架错误日志；debug 包开启 `setWebContentsDebuggingEnabled`。

### 修复：助手页顶部切换条压到状态栏
- 移除 `AssistantMode` 内的 `FilterChips`（`Box` 无状态栏内边距）；
- 新增「设置 → 助手引擎」（`Session.assistantMode`，DataStore 持久化），助手 Tab 按配置直接渲染
  原生助手或 DSH 控制台。

---

## 3.0.0 — 2026-10-20

### 新增：DSH（DeepSeek Harness）内核以独立进程接入
- **运行时落地**：Node 47.4 MB + 10 个依赖库写入 `jniLibs/arm64-v8a`（Android 10+ 仅 `nativeLibraryDir` 可执行）。
- **ELF 改写器**（`dsh/pack_dsh.ps1`）：解析 ELF64 → `PT_DYNAMIC`，就地改写 `DT_NEEDED`、`DT_VERNEED`、`DT_SONAME`：
  `libz.so.1→libz_1.so`、`libcrypto.so.3→libcrypto_3.so`、`libssl.so.3→libssl_3.so`、
  `libicu*.so.78→libicu*_78.so`（均为等长替换，无需搬移段表）。
- **内核资产**：`assets/payload-dsh.zip` = 28,137 个条目 / 62.7 MB（dshroot + dshhome + bin + runtime/etc），
  剔除 python/npm/git/rish 与官方自研插件，`cordis.patch.yml` 换成托管版本（禁用 sandbox、office-to-pdf 等）。
- **进程管理** `DshManager`：首启解压（约 3 秒）、拉起 `dsh web --host 127.0.0.1 --port 8300 --no-open`、
  解析一次性 token URL、健康检查、日志轮转与 tail、PID 文件清理孤儿进程、端口冲突顺延。
- **UI**：`ui/dsh/DshScreen.kt` 提供状态卡（解压/启动/失败）、WebView 控制台、日志弹层、
  网关接入信息（Base URL / API Key 一键复制）。
- 集成入口：助手 Tab 内「原生助手 / DSH 内核」分段切换（3.1.0 移至设置）。

### 修复（同版本迭代中发现）
- token URL 被加载两次导致 401：改为只在 `factory` 加载一次，并用 `tokenConsumed` 标记后走 cookie 会话；
- 孤儿 node 进程占端口：启动前按 `dsh-node.pid` 清理；
- 旧 `cordis.patch.yml` 不更新：加 `WorkBuddy-managed-patch: v2` 标记强制刷新；
- `office-to-pdf` 插件因官方 payload 缺 `restructure/src/EncodeStream.js` 反复报错：补丁禁用该插件。

---

## 2.0.1 — 2026-10-19

### 修复
- **真机首启动崩溃/报错**：`Overview.inFlightFull` 声明为 `Boolean`，服务端返回整型计数
  → `Unexpected JSON token at offset 91 … $.in_flight_full`。改为 `Int`，并更新 UI 文案与用例。
- **16 KB 页对齐**：`libwb2api.so` 的 `LOAD` 段按 16 KB 对齐重建
  （`-Wl,-z,max-page-size=16384`），`zipalign -c -P 16` 通过。
- live fixture 的 UTF-8 BOM 导致解析失败：读取时 `removePrefix("\uFEFF")`。

### 其它
- 单元测试扩到 41 个（新增 11 个真机响应 fixture、SSE 客户端用例）。

---

## 2.0.0 — 2026-10-19

### 从 Web 面板改造为一体化 Android 应用
- **内置网关**：Go 服务端交叉编译为 `libwb2api.so`（cgo、`GOOS=android`、`-tags timetzdata`），
  由 `LocalGatewayManager` 拉起：随机 `sk-` Key、`127.0.0.1` 监听、健康检查、端口改写、日志轮转、接管已有实例；
  `GatewayService` 前台服务保活。
- **移动端 UI（Compose）**：6 个一级 Tab（总览/账号/助手/任务/日志/设置）+ 5 个二级页
  （服务器配置/用量/套餐/模型探测/券码），全部按手机屏幕与手势重排。
- **原生助手**：OpenAI 兼容 SSE 对话、多会话持久化、模型选择、系统提示词；
  可选无障碍工具调用（`screen/tap/type/scroll/back/home`，最多 4 轮）。
- **远程模式**：填写已有 `workbuddy2api` 实例地址与 Key 直连。
- **演示模式**：不联网预览界面。

### 工程
- Gradle 8.10.2 / AGP 8.7.3 / Kotlin 2.0.21 / Compose BOM 2024.10.01；minSdk 24、targetSdk 35。
- `jniLibs { useLegacyPackaging = true }`、ABI 过滤 `arm64-v8a` + `x86_64`、core library desugaring。
- 单元测试（27 个）+ lint 0 error，产出 `WorkBuddy-2.0.0.apk`。
