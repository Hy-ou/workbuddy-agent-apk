# WorkBuddy 3.7.0

## 修改说明

### 修复：DSH 内安装社区插件报错（`spawn pnpm EACCES`）

- **根因 1**：DSH 插件安装调用 `spawn("pnpm")`，而 Android 10+ 禁止执行应用数据目录中的文件
  （SELinux `app_data_file` 无 `execute`），于是报 `Command failed with EACCES`。
- **根因 2**：payload 自带的 pnpm 是 Termux 构建，硬编码前缀 `/data/data/com.termux`，
  即便可执行也会因 `lstat /data/data/com.termux` 失败。
- **修复**：
  - 新增 NDK 编译的 ELF 启动器 `libwbnode_shim.so`（arm64-v8a / x86_64），放在 jniLibs
    （nativeLibraryDir 允许 exec）。它按 `argv[0]` 决定运行 `pnpm.mjs`、`npm-cli.js` 或 node 本体。
  - `home/bin` 下创建 `pnpm` / `npm` / `node` 符号链接指向该启动器，并把 `home/bin` 置于内核 PATH 首位；
    链接每次启动重建（应用更新后 nativeLibraryDir 路径会变）。
  - pnpm 运行时改为**首次启动用内置 npm 现装**到 `home/pnpmjs`（与应用前缀无关），后台线程执行。
  - 内核环境新增 `WB_NODE`、`WB_DSH_HOME`。
- **真机验证**：`pnpm --version` → 12.14.0；`npm --version` → 11.20.0；
  在 `dshhome/profiles/web` 执行 `pnpm add dsh-cost-meter` 成功（1.4s），随后已卸载复原。

### 文档

- README 增补 3.7.0 变更、pnpm/npm 启动器架构说明，以及 19 项系统工具链的现状与实现路线。

## 工具链路线（未在本版交付）

Python / Go / Rust / Clang / OpenJDK / Ruby / PHP / Perl / Lua / SQLite / FFmpeg / ImageMagick /
OpenSSH / ADB / Vim 等 Termux 包无法解包即用：Termux 二进制在编译期写死前缀
`/data/data/com.termux/files/usr`（DT_RUNPATH、CPython sys.prefix 等均为绝对路径），
且新前缀更长，无法原位改写。正确实现需依次补：bundled patchelf（重写 RUNPATH/NEEDED）→
依赖闭包解析（`Packages.xz` + SHA-256 校验）→ 前缀重写与 shebang 修复。

## 安装

```bash
adb install -r WorkBuddy-3.7.0.apk
```

## 校验

见附件 `SHA256SUMS`。
