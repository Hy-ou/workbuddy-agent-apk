# WorkBuddy 3.9.0

## 修改说明

### 1. 关键发现：应用进程不能执行私有目录里的二进制

Android 10+ 的 SELinux 不允许 `untrusted_app` 执行 `app_data_file`（`exec` 返回 `EACCES`）。
之前用 `adb shell run-as` 测「工具链能跑」是 **shell 域**，与内核进程（应用域）不同 ——
所以 3.8.x 里 `git`/`sqlite3`「装上了但用不了」，内核里实际是 `Permission denied`。

**修复**：`home/bin/<命令>` 全部改为指向 jniLibs 启动器 `libwbnode_shim.so` 的符号链接；
启动器用 `/system/bin/linker64 <目标>` 加载真正的二进制（mmap 加载，不触发对目标文件的 exec 检查），
脚本类（`#!` 开头）会解析解释器并映射到工具链前缀；`node/npm/pnpm` 仍走 JS 分支。

### 2. 换设备就装不上插件（两处根因）

| 现象 | 根因 | 修复 |
|---|---|---|
| 设置里「安装」失败（本机正常、别的设备失败） | pnpm 是**首次启动联网** `npm install pnpm` 装出来的，慢/失败时无 pnpm 可用 | 把 **pnpm 10（纯 JS，4.7 MB）打进 payload**（`pnpm10/`），离线可用；老版本升级只增量补解压该目录，不动用户数据 |
| DSH **Web 端**直接安装插件失败 | profile 的 `node_modules/.modules.yaml` 记录的 `storeDir` 与内置 pnpm 版本不匹配 → `ERR_PNPM_UNEXPECTED_STORE`（本机是我手工对齐过的，所以「看起来正常」） | 内核**每次启动**自动把 `storeDir` 对齐到 `store/v10` |

### 3. 工具链装完自检

每个条目带 `verifyCmd`，安装结束用 linker64 立即跑一次并回显结果（安装对话框里可见）。

### 真机验证（应用进程内，等价于内核环境）

```
git version 2.56.0
3.54.1 2026-10-16 … (sqlite3)
Python 3.14.8
python3 -c 'print(1+1)'      → 2
python3 -c 'import sys;print(sys.prefix)'
  → /data/user/0/com.workbuddy2api.panel/files/dsh-home/toolchain/usr
社区插件：dsh-cost-meter、dsh-free-search 安装成功并写入 cordis.patch.yml
```

## 安装

```bash
adb install -r WorkBuddy-3.9.0.apk
```

换设备/覆盖安装时：payload 会增量补上内置 pnpm；已装工具链继续可用。

## 校验

见附件 `SHA256SUMS`。
