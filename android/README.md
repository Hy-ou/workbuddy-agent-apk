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
