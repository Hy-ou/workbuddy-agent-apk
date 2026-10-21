# @workbuddy/dsh-mobile

WorkBuddy 一体化 APK 内置的 DSH 插件：给 Android 上的 DSH 内核补上**能用的执行通道**，
并把**这台手机的真实环境**写进系统提示词，避免模型反复猜命令、反复报错。

由 App 自动安装，**不需要也不应该手工 `npm install`**：

- 源文件：`android/dsh/assets/dsh-plugins/wb-mobile/`（构建时并入 APK assets）
- 运行时位置：`<app files>/dsh-home/dshhome/profiles/web/node_modules/@workbuddy/dsh-mobile/`
- 登记方式：`cordis.patch.yml` 的 `insert` 行（id `wb-mobile`），每次启动内核由 App 重写

## 为什么需要它

1. **Android 没有 bash。** 官方 `dsh-tool-bash` 执行的是 `spawn("bash", "-c", ...)`，
   而系统里只有 `/system/bin/sh`（mksh）+ toybox，于是每次 bash 调用都失败（ENOENT）。
2. **模型不知道边界。** 它不知道这台机器已经拿到「所有文件访问」、装了哪些工具链、
   Shizuku 是否授权、npm/pnpm 在哪里，于是用 Linux 桌面习惯猜命令，猜错一次就多一次报错。

## 提供的东西

### 工具

| 工具 | 用途 | 说明 |
|---|---|---|
| `wb_sh` | 执行 POSIX shell 命令 | `/system/bin/sh -c`，返回 `exitCode / stdout / stderr / timedOut / signal`；受管输出上限 48 KB、超时默认 60 s |
| `wb_env` | 环境体检 | 一次给出可用/不可用命令、PATH、工具链清单、共享存储可写性、宿主桥与 Shizuku 状态 |
| `wb_shizuku` | 以 uid 2000 执行 | 经宿主回环桥 `POST /shell` 转发到 `ShizukuShell.exec`；用于 `pm`/`input`/`screencap`/`settings`/`dumpsys` |
| `wb_notify` | 系统通知 | 经回环桥 `POST /notify`，长任务结束时提醒用户 |

### 系统提示词段

`workbuddy:android-environment`（order 1050，`interpolate: false`）—— 一段「Android 环境手册」：
文件权限与路径约定、POSIX-only 语法、toybox 与 GNU 的差异、npm/pnpm 位置、工具链安装位置、
Shizuku 提权边界、以及「报错先读 stderr、禁止原样重试」的排错约定。

## 实现约束（改代码前必读）

- **零第三方依赖**：只 import `node:` 内置模块。profile 的 `node_modules` 里解析不到
  `@deepseek-ai/*`，一旦引入就会 `ERR_MODULE_NOT_FOUND`，整个插件加载失败
  （3.6.0 的 notify 插件就是这样退场的）。
- **原始 JSON Schema**：用 `ctx.tools.register({ name, description, parameters, output, execute })`，
  不用 `defineTool`。schema 只允许 `type`（单值）/ `properties` / `required` /
  `additionalProperties` / `items` / `enum` / `const` + 注释键（`description` 等），
  不支持 `minimum`、`pattern`、`type: ["x","null"]` 这类写法。
- **桥 token 只在进程内用**：子进程环境会被 harness 的凭据清洗（名字含 `TOKEN` 的一律剔除），
  所以 `WB_BRIDGE_TOKEN` 不要往 shell 里传，直接在本进程 fetch。

## 自检

```bash
node android/dsh/tests/wb-mobile.selftest.mjs
```

不需要手机：用假 `ctx` 校验 4 个工具的 schema、渲染与失败路径。
真机上额外确认：

```bash
# 1) 插件是否加载（内核日志里不应出现 wb-mobile 相关 error）
adb shell run-as com.workbuddy2api.panel tail -50 files/dsh-web.log
# 2) 让 Agent 说「运行 wb_env」——应返回一份体检报告
# 3) 让 Agent 说「用 wb_sh 执行 ls /sdcard/Download」——应返回目录列表而不是 ENOENT
```
