# WorkBuddy APK · 一体化网关 + Agent 客户端

把 [workbuddy2api-panel](https://github.com/linguo2625469/workbuddy2api-panel) 的 Go 网关、原生 Android 助手，以及
[DeepSeek Harness（DSH）](https://github.com/deepseek-ai/deepseek-harness) 内核，打包成**一个可以在手机上独立运行的 APK**：
不需要电脑、不需要 Docker、不需要外接服务器，装上即可管理账号池，并用 DSH Agent 检索/操作手机文件与界面。

```
┌────────────────────────────── 一台手机（com.workbuddy2api.panel） ──────────────────────────────┐
│                                                                                                │
│   ┌─────────────┐   HTTP/127.0.0.1:7863    ┌──────────────────┐   OpenAI 兼容    ┌───────────┐  │
│   │ 管理面板 UI  │ ───────────────────────▶ │  Go 网关（内置）   │ ───────────────▶ │ 上游账号池 │  │
│   │ (Compose)   │   /panel/api/* /v1/*     │ libwb2api.so      │                  └───────────┘  │
│   └─────────────┘                          └──────────────────┘                                 │
│          │                                          ▲                                           │
│          │ 原生助手（SSE 直连）                       │ /v1/chat/completions                       │
│          ▼                                          │                                           │
│   ┌─────────────┐                                   │                                           │
│   │ Agent 对话   │ ──────────────────────────────────┘                                           │
│   └─────────────┘                                                                               │
│          │ 无障碍服务（可选）                                                                     │
│          ▼                                                                                      │
│   屏幕读取 / 点击 / 输入 / 滚动                                                                    │
│                                                                                                │
│   ┌──────────────────────────────────────────────────────────────┐                              │
│   │ DSH 内核（独立 Node 进程，127.0.0.1:8300，前端由 WebView 承载）   │                              │
│   │  node(jniLibs) + 28k 文件内核树 + 工具/终端/会话 + WebSocket 通道 │                              │
│   └──────────────────────────────────────────────────────────────┘                              │
└────────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 1. 特性

### 网关（内置 Go 服务）
- 完整移植 workbuddy2api 的 Go 网关，交叉编译为 Android 可执行文件，由 App 以子进程方式托管。
- 监听 `127.0.0.1:7863`，随机生成并持久化 `sk-` API Key，只对本机可见。
- 兼容 OpenAI 协议：`/v1/models`、`/v1/chat/completions`（流式 SSE）。
- 前台服务保活 + 崩溃自愈 + 端口占用改写 + 日志轮转（8 MB，保留尾 512 KB）。
- 支持「接管已有网关」：检测到旧实例 `/healthz` 返回 `workbuddy2api` 时直接复用，避免重复启动。

### 管理面板（Jetpack Compose）
- 6 个一级 Tab：**总览 / 账号 / 助手 / 任务 / 日志 / 设置**，全部按手机端手势与密度重排。
- 总览：账号池可用率、并发/在途、积分汇总、快捷操作（签到/余额/旅行/活动/保活）、账号健康排序。
- 账号、任务队列、请求日志、设置（连接、外观、刷新、助手引擎、文件访问、服务器二级页）。

### 原生助手
- 走网关的 OpenAI 兼容接口，SSE 流式输出，支持多会话、模型选择。
- 内置无障碍工具调用（`screen / tap / type / scroll / back / home`），最多 4 轮，用于真实的手机自动化。
- 会话持久化：JSON 存储，重启不丢。

### DSH 内核（独立进程）
- 内置 Node 运行时（47 MB）与 10 个依赖库，打成 `jniLibs`（Android 10+ 唯一可执行位置）。
- 内核树（28,138 个文件）压缩为 62.7 MB 资产包，首启解压约 3 秒。
- 以独立进程运行 `dsh web`，解析一次性 token URL，用 WebView 承载完整控制台（会话、工具、终端、浏览器）。
- 「设置 → 助手引擎」可在**原生助手 / DSH 内核**之间切换，选择持久化。
- 「设置 → 文件访问」申请 `MANAGE_EXTERNAL_STORAGE`，授权后 DSH 工作区切到 `/storage/emulated/0/WorkBuddy`，
  Agent 可直接检索与操作手机文件。

---

## 2. 快速开始

### 2.1 直接安装

```bash
# 从 Release 页面下载对应 APK 后安装
adb install -r WorkBuddy-3.2.0.apk
```

首次启动后：自动拉起内置网关（`127.0.0.1:7863`），在「账号」页扫码/登录添加账号，即可使用；
「助手」页默认为原生对话，可在「设置 → 助手引擎」切换为 DSH 内核。

### 2.2 从源码构建

#### 前置条件

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | 17+ | Gradle/AGP 要求 |
| Android SDK | Platform 35 + Build-Tools 35 | `ANDROID_HOME` 指向 SDK |
| Android NDK | r27（27.3.x） | 交叉编译 Go 二进制（cgo） |
| Go | 1.22+ | 构建内置网关 |
| PowerShell | 7+ | 构建脚本（Windows 已验证） |

> 仓库不包含大体积二进制（`libwb2api.so`、`libdshnode.so`、`payload-dsh.zip`），需按下面步骤生成。

#### 构建步骤

```powershell
# 1) 交叉编译 Go 网关 → app/src/main/jniLibs/{arm64-v8a,x86_64}/libwb2api.so
#    需要 workbuddy2api-panel 的 Go 源码（默认在上一级目录，可用 -Root 指定）
pwsh android/build_gateway.ps1

# 2) 打包 DSH 运行时：改写 ELF 依赖名 → jniLibs；内核树 → assets/payload-dsh.zip
#    首次会自动下载官方 payload（154 MB）
pwsh android/dsh/pack_dsh.ps1

# 3) 构建 APK（release 用 debug 签名，可直接安装）
cd android
./gradlew testDebugUnitTest lintDebug assembleRelease
```

产物：`android/app/build/outputs/apk/release/app-release.apk`（约 107 MB）。

#### 体积说明

| 组成 | 体积 |
|---|---|
| Node 运行时 + 依赖库（jniLibs） | 约 90 MB 未压缩（APK 内约 40 MB） |
| DSH 内核树（assets/payload-dsh.zip） | 62.7 MB |
| 其余（Kotlin/Compose/资源） | 约 5 MB |

首次启动会把内核树解压到 `files/dsh`（约 420 MB 内部存储），后续启动直接复用。

---

## 3. 技术栈与版本

| 项 | 版本 |
|---|---|
| 应用包名 | `com.workbuddy2api.panel` |
| 应用版本 | **3.2.0**（versionCode 6） |
| minSdk / targetSdk / compileSdk | 24 / 35 / 35 |
| Kotlin | 2.0.21 |
| AGP / Gradle | 8.7.3 / 8.10.2 |
| Compose | BOM 2024.10.01（Material3 1.3.1） |
| 协程 / 序列化 | 1.9.0 / 1.7.3 |
| OkHttp | 4.12 |
| DataStore | 1.1.1 |
| DSH 内核 | 0.2.1-rc.2（Node 运行时来自官方 Android payload） |
| 内置网关 | workbuddy2api（Go，cgo，`-tags timetzdata`） |

---

## 4. 目录结构

```
workbuddy-agent-apk/
├── README.md                     ← 本文档
├── CHANGELOG.md                  ← 版本开发记录
├── docs/
│   ├── architecture.md           ← 架构与关键实现
│   └── troubleshooting.md        ← 真机问题排查与修复记录
├── THIRD_PARTY.md                ← 第三方组件与许可
└── android/
    ├── build_gateway.ps1         ← Go 网关交叉编译脚本
    ├── README.md                 ← 应用级详细说明（含变更记录）
    ├── AGENT_SPEC.md             ← 助手/Agent 设计稿
    ├── DESIGN_SPEC.md            ← UI 设计稿
    ├── dsh/pack_dsh.ps1          ← DSH 打包脚本（含 ELF 补丁器）
    └── app/src/
        ├── main/java/com/workbuddy2api/panel/
        │   ├── MainActivity.kt           ← 入口（edge-to-edge、通知权限、调试用 tab/mode extra）
        │   ├── WbApplication.kt
        │   ├── gateway/                  ← 内置网关管理 + 前台服务
        │   │   ├── GatewayStatus.kt
        │   │   ├── LocalGatewayManager.kt
        │   │   └── GatewayService.kt
        │   ├── data/                     ← 数据层
        │   │   ├── SettingsStore.kt      ← DataStore（Session）
        │   │   ├── AppViewModel.kt
        │   │   ├── WbRepository.kt       ← 远端/演示仓库
        │   │   └── agent/                ← 原生助手：会话、模型、SSE 客户端
        │   ├── agent/                    ← 无障碍服务与手机工具
        │   ├── dsh/                      ← DSH 进程管理、文件访问权限
        │   ├── util/                     ← 格式化、URL 等工具
        │   └── ui/
        │       ├── theme/ components/ state/
        │       ├── shell/                ← 底部导航 + 路由
        │       ├── overview/ accounts/ tasks/ logs/ connect/
        │       ├── settings/             ← 设置页与 5 个二级页
        │       ├── assistant/            ← 原生助手界面
        │       └── dsh/                  ← DSH 控制台（WebView）
        ├── main/res/                     ← 主题、图标、无障碍配置
        └── test/                         ← 41 个单元测试 + 11 个真机 live fixture
```

---

## 5. 运行时组成

| 组件 | 进程/载体 | 地址 | 说明 |
|---|---|---|---|
| 管理面板 | App 主进程（Compose） | — | 直连本机网关 |
| 内置网关 | `libwb2api.so` 子进程 | `127.0.0.1:7863` | 由 `LocalGatewayManager` 拉起，`GatewayService` 保活 |
| DSH 内核 | `libdshnode.so` 子进程 | `127.0.0.1:8300+` | 由 `DshManager` 拉起，端口冲突自动顺延 |
| DSH 控制台 | WebView | 一次性 token URL | token 只加载一次，之后走 cookie |

网关工作目录：`files/gateway/`（`config.json`、认证缓存、`data/`、`gateway.log`、`cacert.pem`）。
DSH 目录：`files/dsh/`（内核树、`dshhome`、`dsh-web.log`、`dsh-node.pid`）。

---

## 6. 权限说明

| 权限 | 用途 |
|---|---|
| `INTERNET` / `ACCESS_NETWORK_STATE` | 访问网关与上游 |
| `FOREGROUND_SERVICE`(+`DATA_SYNC`) | 网关前台保活 |
| `POST_NOTIFICATIONS` | 网关/内核运行通知 |
| `MANAGE_EXTERNAL_STORAGE` | 让 DSH 检索、读写手机文件（设置页申请） |
| `READ/WRITE_EXTERNAL_STORAGE`（maxSdk 32/29） | Android 10 及以下的等价能力 |
| 无障碍服务（可选） | 原生助手的 `screen/tap/type/scroll...` 工具 |

`MANAGE_EXTERNAL_STORAGE` 在 Google Play 上架需单独申报；侧载安装不受影响。

---

## 7. 使用要点

### 7.1 连接方式
- **本机模式（默认）**：App 自己拉起网关，API Key 保存在 `files/gateway/config.json`。
- **远程模式**：「设置 → 连接」填服务器地址与 Key，可连接局域网/公网上的 workbuddy2api 实例。

### 7.2 让 DSH 用上本地网关
1. 「设置 → 文件访问」授予「所有文件访问」；
2. 「助手 → DSH 内核」启动内核，进入控制台；
3. 控制台右上角 ⓘ 可查看并复制 **Base URL**（`http://127.0.0.1:7863/v1`）与 **API Key**；
4. 在 DSH 控制台 → 设置 → 模型 → 添加提供方（OpenAI 兼容），模型 ID 填 `cn:*` / `global:*`。

### 7.3 调试入口（debug 包）
```bash
# 直达任意 Tab（0..5）
adb shell am start -n com.workbuddy2api.panel/.MainActivity --ei tab 2 --es mode dsh
```

---

## 8. 开发记录

完整变更见 [CHANGELOG.md](CHANGELOG.md)，关键节点：

| 版本 | 内容 |
|---|---|
| 2.0.0 | 从 Web 面板改造为 Android 应用：Compose 移动端 UI、内置网关、原生助手 |
| 2.0.1 | 真机崩溃修复（`in_flight_full` 类型）、16 KB 页对齐、单测补齐 |
| 3.0.0 | 以独立 Node 进程接入 DSH 内核（jniLibs + ELF 补丁 + 资产包 + WebView） |
| 3.1.0 | WebView 交互修复（`vh=0` 视口塌陷）、助手引擎切换移入设置 |
| 3.2.0 | 申请「所有文件访问」，DSH 工作区落到共享存储 |

---

## 9. 测试

```bash
cd android
./gradlew testDebugUnitTest lintDebug
```

- 41 个单元测试：JSON 解码（含 11 个真机 live fixture）、URL 处理、格式化、网关 API、SSE 客户端（MockWebServer）。
- `lintDebug` 0 error（存量 5 个 warning）。

---

## 10. 已知限制

- **体积**：APK 约 107 MB，首启解压后占用约 420 MB 内部存储（DSH 内核树）。
- **架构**：Node 运行时仅 arm64-v8a；x86_64 设备只能使用原生助手与网关。
- **WebView**：部分机型（如本项目的测试机 Chromium 149）`vh/dvh` 解析为 0，已在页面加载后注入修复。
- **DSH 官方 payload**：内置 `restructure` 包缺 ESM 源文件，已在补丁中禁用 `office-to-pdf` 插件。
- **上架**：`MANAGE_EXTERNAL_STORAGE` 与内置 Node 运行时不适合直接上架 Google Play。

---

## 11. 安装包

APK 体积约 107 / 117 MB（超出 GitHub 单文件 100 MB 限制），因此**不入库**，作为
[GitHub Releases](../../releases) 附件发布；仓库里只保留校验值与发布说明。

| 附件 | 大小 | 说明 |
|---|---|---|
| `WorkBuddy-3.2.0.apk` | 106.7 MB | 正式包，`adb install -r` 即可 |
| `WorkBuddy-3.2.0-debug.apk` | 117.4 MB | 调试包（`run-as`、WebView 远程调试） |
| `SHA256SUMS` | — | 校验值 |

```bash
# 下载（二选一）
gh release download v3.2.0 -R Hy-ou/workbuddy-agent-apk -p "*.apk"
# 或浏览器打开 https://github.com/Hy-ou/workbuddy-agent-apk/releases/latest

sha256sum -c SHA256SUMS
adb install -r WorkBuddy-3.2.0.apk
```

发布新版本的流程见 [release/README.md](release/README.md)，上传脚本为
[tools/publish-release.ps1](tools/publish-release.ps1)（校验 SHA-256 → `gh release create/upload`）。
## 12. 致谢与许可

- 网关与面板原型：[Sliverkiss/workbuddy2api](https://github.com/Sliverkiss/workbuddy2api)、
  [linguo2625469/workbuddy2api-panel](https://github.com/linguo2625469/workbuddy2api-panel)（MIT）。
- Agent 内核：[deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness)（MIT）。
- Android 集成参考：[woaiys3/deepseek-harness-android-app](https://github.com/woaiys3/deepseek-harness-android-app)（MIT，
  本项目复用其打包思路与 Android 兼容补丁）。
- Node.js / OpenSSL / ICU 等运行时组件遵循各自许可，详见 [THIRD_PARTY.md](THIRD_PARTY.md)。

本项目代码以 MIT 许可发布，见 [LICENSE](LICENSE)。
