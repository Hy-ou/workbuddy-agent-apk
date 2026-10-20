# WorkBuddy 3.8.0

## 修改说明

### 新增：扩展中心（Termux 系统工具链，19 项）

Python / Go / Rust / Clang / OpenJDK 17 / Git / Ruby / PHP / Perl / Lua / SQLite / FFmpeg /
ImageMagick / OpenSSH / ADB / Vim / nano / tmux / make —— 一键安装、可卸载、带实时进度。

**实现（A 方案：先打通样板，再批量沿用同一条管线）**

1. **重定位**：实测 Termux 二进制的 `INTERP` 是 `/system/bin/linker64`（Bionic，可直接运行），
   真正绑死的是 `DT_RUNPATH = /data/data/com.termux/files/usr/lib`。因此安装时对每个 ELF
   执行 `patchelf --set-rpath <我们的 lib>`，文本文件做前缀替换即可完成重定位。
2. **patchelf**：NDK 交叉编译（16 KB 页对齐），随 APK 打进 `jniLibs/libwbpatchelf.so`；
   构建脚本 `android/dsh/build-patchelf.ps1` 入库。
3. **安装管线**：apt `Packages` 索引（TUNA → USTC → BFSU → 官方，UA 伪装 apt，否则 TUNA 403）→
   `Depends` 递归闭包 → 逐包下载（4 次重试）→ SHA-256 校验 → ar/deb + `data.tar.xz` 解包 →
   剥掉 Termux 前缀安装到 `home/toolchain/usr` → patchelf 重写 + 文本前缀替换 → 写安装标记。
4. **环境注入**：`PREFIX`/`TERMUX_PREFIX`、`GIT_EXEC_PATH`、`GIT_TEMPLATE_DIR`；
   装了 ca-certificates 则注入 `GIT_SSL_CAINFO`/`CURL_CA_BUNDLE`（libcurl 的 CA 路径是编译期写死的），
   装了 python 则注入 `PYTHONHOME`；`home/toolchain/usr/bin` 已在 PATH 中。

**真机验证**

| 包 | 依赖数 | 重定位 | 结果 |
|---|---|---|---|
| Git | 14 个包 | 212 个 ELF / 59 个文本 | `git version 2.56.0`；`git init` / `commit` 正常 |
| SQLite | 5 个包 | 220 个 ELF / 6 个文本 | `sqlite3 3.54.1`；`select 1+1` → `2` |

HTTPS 远程操作受本机网络限制（github/gitee 不可达），TLS/CA 链路已正常（不再报 trust anchor 错误）。

### 已知限制

- 个别包把前缀编译进二进制（如 CPython 的 `sys.prefix`），需按包补环境变量或启动脚本；
  通用管线已同时跑通 Git 与 SQLite（含共享库依赖），其余 17 项沿用同一路径，需逐个验证。
- 依赖包共享 `home/toolchain/usr`；卸载只删安装标记，共享库保留供其它扩展使用。

## 安装

```bash
adb install -r WorkBuddy-3.8.0.apk
```

首次安装扩展需要网络（默认清华 TUNA 镜像）。装好后 Agent 可直接在内核 bash 里调用
`git`、`sqlite3` 等命令。

## 校验

见附件 `SHA256SUMS`。
