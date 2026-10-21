# WorkBuddy 3.10.0

## 修改说明

### 问题：Android 端 Agent 频繁调用 bash 等工具报错

真机上 DSH 内核的 Agent 一跑命令就失败，且反复重试同一个错误。根因三条，都在宿主侧：

1. **Android 没有 bash。** `@deepseek-ai/dsh-bash-local` 执行的是 `spawn("bash", "-c", …)`，
   系统里只有 `/system/bin/sh`（mksh）+ toybox；而 `bash-sandbox`/`pwsh-sandbox` 在托管补丁里本来就禁用，
   于是「唯一的 shell 工具」正好是跑不起来的那个。
2. **即使有二进制也 exec 不了。** `untrusted_app` 不能 exec `app_data_file`，内核树又落在
   `/storage/emulated/0`（FUSE，noexec），必须走 `nativeLibraryDir` 启动器 + `/system/bin/linker64`。
3. **模型不知道边界。** 内核 system prompt 里没有任何「这台手机有什么」的事实
   （所有文件访问、PATH、工具链、Shizuku、npm），模型只能按 Linux 桌面习惯猜，猜错即报错。

### 方案：内置 DSH 插件 `@workbuddy/dsh-mobile`（不是 Agent 预设）

预设只能在已有插件里挑组合，既造不出 shell 通道，也无法把随环境变化的运行时事实写进提示词；
插件是 DSH 里唯一能同时注册「工具」与「提示词段」的位置。

| 工具 | 作用 |
|---|---|
| `wb_sh` | `/system/bin/sh -c`，返回 exitCode/stdout/stderr/timedOut；48 KB 输出上限 + spill 落盘 |
| `wb_env` | 环境体检：可用/不可用命令、PATH、工具链与 npm CLI 清单、共享存储可写性、桥/Shizuku 状态 |
| `wb_shizuku` | 以 uid 2000 执行（pm / input / screencap / settings / dumpsys），经宿主回环桥转发 |
| `wb_notify` | 系统通知（长任务结束提醒） |

外加系统提示词段 `workbuddy:android-environment`（order 1050）：工作区与绝对路径约定、
「所有文件访问」已授予、POSIX-only 语法与 toybox/GNU 差异、npm/pnpm 位置、工具链安装位置、
Shizuku 提权边界、以及「报错先读 stderr、禁止原样重试」。

### 同时做的兜底

- `home/bin` 增加 `bash`/`sh` 链接 → `libwbnode_shim.so`，`shim.c` 把它们落到 `/system/bin/sh`，
  并解析 `#!/bin/bash`、`#!/bin/sh` shebang —— 官方 bash 工具因此也能跑（POSIX 语法）。
  顺带删掉 shebang 分支里那条会以无参方式 exec 解释器的兜底调用。
- 宿主桥新增 `POST /shell`（Shizuku；未授权返回 409 + 原因），`POST /health` 返回 Shizuku 状态。

### 工程细节

- 插件源码 `android/dsh/assets/dsh-plugins/wb-mobile/`（零依赖 ESM，只 import `node:`），
  构建时经 `sourceSets.main.assets.srcDir("../dsh/assets")` 并入 APK。
- 运行时由 `dsh/MobilePlugin.kt` 复制到
  `dshhome/profiles/web/node_modules/@workbuddy/dsh-mobile` 并登记到 `InstalledPlugins`，
  `cordis.patch.yml` 的 insert 行（id `wb-mobile`）随之写入。
  **必须放 profile**：DSH loader 用 profile 作 baseUrl 解析包名，放内核树会 ERR_MODULE_NOT_FOUND。
- 离线自检：`node android/dsh/tests/wb-mobile.selftest.mjs`
  （校验 4 个工具的 JSON Schema、渲染与失败路径；已用内核自带的 `assertSupportedJsonSchema` 交叉验证）。

## 安装

```bash
adb install -r WorkBuddy-3.10.0.apk
```

升级后请**重启内核**（设置 → 助手引擎，或杀掉 DSH 进程后重进「助手」页）：
插件的加载发生在内核启动时，PATH 与 `home/bin` 链接同理。

## 真机验收清单

1. 让 Agent 运行 `wb_env` → 返回体检报告（工作区可写、`/system/bin/sh`、命令清单）；
2. 让 Agent 用 `wb_sh` 执行 `ls /sdcard/Download` → 目录列表，不再是 ENOENT；
3. 让 Agent 用 bash 工具执行 `echo hi` → 也应成功（启动器映射到 mksh）；
4. 未授权 Shizuku 时用 `wb_shizuku` 执行 `pm list packages` → 明确提示未授权且不重试；
5. 授权后同一条命令 → 包名列表。

## 校验

见附件 `SHA256SUMS`。
