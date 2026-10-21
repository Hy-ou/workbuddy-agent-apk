# Android 端 Agent 工具手册：为什么是插件而不是预设

> 对应版本：3.10.0 · 相关代码：`android/dsh/assets/dsh-plugins/wb-mobile/`、
> `android/app/src/main/java/com/workbuddy2api/panel/dsh/MobilePlugin.kt`、
> `dsh/LocalBridge.kt`、`dsh/shim.c`

## 1. 现象

在真机上，DSH 内核里的 Agent 只要想跑命令就会失败，且失败模式重复出现：

- `bash` 工具报 `spawn bash ENOENT` / `subprocess failed before reporting an outcome`；
- 换成 `sh -c` 能跑，但模型不知道可以这么写，下一次又回去调 bash；
- 需要 `pm`、`input`、`screencap` 这类 shell 身份的操作时，模型在 `bash`/`sh` 里试，
  必然失败（uid 不是 shell，且没有授权通道）；
- 需要 `git`、`python3`、`sqlite3` 时，模型假设它们存在（桌面习惯），报 `command not found`；
- 即便失败，模型也常原样重试同一串命令，把一个错误放大成十几条错误。

## 2. 根因（三条，都在宿主侧）

| 现象 | 根因 |
|---|---|
| `bash` 工具必失败 | `@deepseek-ai/dsh-bash-local` 的实现是 `executeArgv(spec, ["bash", "-c", command])`；**Android 没有 bash**，只有 `/system/bin/sh`（mksh）+ toybox。`dsh-bash-sandbox`/`dsh-pwsh-sandbox` 在托管补丁里本来就是禁用的，所以「唯一 shell 工具」正好是跑不起来的那个。 |
| 即使有二进制也跑不起来 | Android 10+ SELinux 禁止 `untrusted_app` exec `app_data_file`；内核树又落在 `/storage/emulated/0`（FUSE，**noexec**）。所以「把 bash 塞进内核树」也不可行，必须走 `nativeLibraryDir` 里的启动器（`libwbnode_shim.so`）+ `/system/bin/linker64`。 |
| 模型反复猜 | 内核的 system prompt 里没有任何「这台手机有什么」的事实：文件权限、PATH、工具链前缀、Shizuku、npm。模型只能按 Linux 桌面习惯调用，猜错即报错。 |

结论：**光写提示词治不好**（缺的是能力），**光补能力也治不好**（模型仍然不知道新能力存在、
更不知道边界在哪）。两件事必须一起做，而 DSH 里能同时提供「工具」和「提示词段」的唯一位置就是插件。

## 3. 为什么选插件，而不是 PTC 风格预设

DSH 里有两类可配置物，容易混淆：

| | Agent 预设（`@deepseek-ai/dsh-agent-preset`） | 插件（cordis plugin） |
|---|---|---|
| 是什么 | 在 cordis YAML 里声明「这个 agent 由哪些子插件组成」，会话可选 | 一段 ESM 模块，能注册工具、注册提示词段、注册 shellEnv 事实 |
| 能做什么 | 增删/组合**已有**插件，改 persona 前缀后缀 | 引入**新的**工具与**新的**提示词事实 |
| 能修「没有 bash」吗 | 不能：它只能挑插件，挑不出一个不存在的 shell | 能：注册 `wb_sh`（直接 `/system/bin/sh`） |
| 能告诉模型「已拿到所有文件访问」吗 | 只能靠 persona 后缀写死一段文字（全局、不随环境变化） | 能：动态读环境（`DSH_WORKSPACE`、`WB_TOOLCHAIN_BIN`、桥/Shizuku 状态）后写进提示词 |
| 部署方式 | 用户在内核里选 | 宿主随 APK 落地，用户无感 |

PTC 模式（`run_code`）本身是**一个工具**（`dsh-tools` 的 `run_code` + `dsh-ptc-runtime-node`），
它让模型写 TypeScript 来编排工具调用；它同样解决不了「bash 不存在」。所以本项目采取的组合是：

> **插件提供能力与事实（本文件的主角）+ PTC 模式作为编排方式**（环境手册里明确推荐：
> 批量处理用 `run_code` 里的 `await tools.wb_sh(...)`）。

## 4. 实现

### 4.1 内核侧：`@workbuddy/dsh-mobile`

位置：`android/dsh/assets/dsh-plugins/wb-mobile/`（构建时并入 APK assets），
运行时由 `MobilePlugin.ensure()` 复制到
`dsh-home/dshhome/profiles/web/node_modules/@workbuddy/dsh-mobile/`，
并在 `cordis.patch.yml` 里写入 `insert` 行（id `wb-mobile`）。

> 注意：用户在控制台安装社区插件时 pnpm 可能把不在 package.json 依赖里的目录剪掉；
> 这不影响当前会话（模块已在内存里），下次启动内核 `MobilePlugin.ensure()` 会重新写入。

> 为什么必须放 profile：DSH loader 用 **profile 目录作为 baseUrl** 解析 insert 里的包名；
> 放到内核树的 `@deepseek-ai/dsh/node_modules/` 会 `ERR_MODULE_NOT_FOUND`
> （3.6.0 的 `wb-notify` 就是这么下线的，现在只保留 `node "$WB_NOTIFY"` 脚本形态）。

插件内容：

| 工具 | 实现 |
|---|---|
| `wb_sh` | `ctx.subprocess.spawn({ argv: ["/system/bin/sh", "-c", cmd] })`，受管输出（48 KB，溢出落 spill）、`graceMs`、超时/取消、返回 `exitCode/stdout/stderr/timedOut` |
| `wb_env` | `node:fs` 扫 PATH 探测 28 个常用命令 + 共享存储可写性 + 工具链/CLI 清单 + `POST /health` 拿 Shizuku 状态 |
| `wb_shizuku` | `POST http://127.0.0.1:7865/shell` → 宿主 `ShizukuShell.exec`（uid 2000） |
| `wb_notify` | `POST http://127.0.0.1:7865/notify` |

提示词段 `workbuddy:android-environment`（order 1050）写清楚：工作区、所有文件访问、
POSIX-only 语法、toybox 与 GNU 差异、npm/pnpm 位置、工具链安装位置、Shizuku 边界、
`wb_env` 先行、禁止原样重试。

### 4.2 宿主侧

| 改动 | 作用 |
|---|---|
| `dsh/MobilePlugin.kt` | 从 assets 复制插件到 profile，内容变化才写；成功才登记 |
| `DshManager.doStart()` | 启动内核前 `MobilePlugin.ensure()` + `InstalledPlugins.add()`，再重写 patch |
| `DshManager.ensureBinShims()` | `home/bin` 增加 `bash` / `sh` 链接 → `libwbnode_shim.so` |
| `dsh/shim.c` | `bash`/`sh`（含 `#!/bin/bash`、`#!/bin/sh` shebang）落到 `/system/bin/sh`；顺带修掉 shebang 分支里那条无参兜底 `execv` |
| `dsh/LocalBridge.kt` | 新增 `POST /shell`（Shizuku），`POST /health` 返回 Shizuku available/granted |
| `app/build.gradle.kts` | `sourceSets.main.assets.srcDir("../dsh/assets")`，插件源码单点维护 |

### 4.3 双保险

- 官方 `bash` 工具现在**能跑**（`bash` → 启动器 → `/system/bin/sh`），历史会话里的调用不再必然失败；
- 但环境手册要求模型**优先 `wb_sh`**：它返回结构化 exitCode/stderr、有超时与输出上限，
  且不依赖 PATH 里的链接是否被覆盖。

## 5. 验证

```bash
# 离线自检（不需要手机）：schema、渲染、失败路径
node android/dsh/tests/wb-mobile.selftest.mjs

# 真机
adb install -r WorkBuddy-3.10.0.apk
adb shell run-as com.workbuddy2api.panel tail -50 files/dsh-web.log   # 无 wb-mobile error
```

真机上依次确认：

1. 问 Agent「运行 wb_env」→ 返回体检报告（工作区可写、`/system/bin/sh`、命令清单）；
2. 问 Agent「用 wb_sh 执行 ls /sdcard/Download」→ 目录列表，不是 ENOENT；
3. 问 Agent「用 bash 工具执行 echo hi」→ 也应成功（启动器映射到 mksh）；
4. 未授权 Shizuku 时「用 wb_shizuku 执行 pm list packages」→ 明确提示未授权，且**不重试**；
5. 授权后同一条命令 → 包名列表。

## 5.1 两种调用模式（标准 / PTC）与一个内核硬限制

会话用哪个 `agentPreset` 决定模型怎么调工具：

| 模式 | 模型看到什么 | 我们工具怎么调 |
|---|---|---|
| 标准（native） | 每个工具一份 schema，直接 tool call | 直接调 `wb_sh` / `wb_env` …（实测已通过） |
| PTC（`agentPreset: "ptc"`） | 只有 `run_code`，其余工具以 TypeScript SDK 形式声明 | `await tools.wb_sh({ command, description })` |

两个真机上踩到的坑：

1. **PTC 会话里 `execSync` 永远失败**。内核的 `libdshnode.so` 是 Termux 构建，二进制里把
   `child_process` 的默认 shell 硬编码成 `/data/data/com.termux/files/usr/bin/sh`，
   这台设备上不存在 → `spawnSync /data/data/com.termux/files/usr/bin/sh ENOENT`。
   换 `shell: '/system/bin/sh'` 也不行（`spawnSync` 的错误是同一行）。
   结论：执行命令只能走 `tools.wb_sh` / `tools.bash`（它们 spawn 的是显式 argv，不经过默认 shell）。
   环境手册第一节就是这件事，并给了可复制的调用片段。
2. **`shim.c` 改动必须重新编译 `libwbnode_shim.so`**。这个 .so 是唯一允许 exec 的入口
   （`nativeLibraryDir`），`bash`/`sh` 的回退逻辑就在里面。只改 `home/bin` 的符号链接而不重编，
   `bash` 依旧 `wbexec: target not found for bash`（exit 127）。

## 6. 扩展方式

加一个新工具：在 `index.js` 里写一个 `ctx.tools.register({...})` 并确保 schema 落在
允许的子集内（`node android/dsh/tests/wb-mobile.selftest.mjs` 会替你检查），
然后 `adb push` 到设备上改 `index.js` 重启内核即可热试；确认后再回写仓库源文件。

需要新事实（例如「当前前台应用」「电池是否在白名单」）：优先扩 `wb_env`，
而不是让模型去 `dumpsys` 里翻。
