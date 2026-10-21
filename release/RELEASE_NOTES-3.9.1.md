# WorkBuddy 3.9.1

## 修改说明

### 修复：DSH Web 端「添加插件」报 `spawn pnpm EACCES`

截图里的报错：

```
无法获取插件信息：Command failed with EACCES:
pnpm view dshmarket name version description dsh --json --registry=… --config.fetch-retries=0
spawn pnpm EACCES
```

**根因 1（主因）**：3.9.0 把 pnpm 改成内置 `pnpm10/` 后，启动器 `libwbnode_shim.so`
的 pnpm 分支**仍然只找 `pnpmjs/…`**（那次路径替换没生效）。于是 `spawn pnpm` 找不到入口直接失败。
真机用 Node 的 `child_process.spawnSync` 复现：`node`/`git` 都正常，只有 `pnpm` 失败。

**根因 2**：内核 `bin/` 里还留着 payload 自带的 Termux `pnpm`/`npm`/`npx`，
它们位于 `/sdcard`（**noexec**），PATH 回退时命中就会得到 `EACCES`。启动时已删除这几个文件。

### 验证（应用进程内，等价内核环境）

```
spawnSync("pnpm", ["view","dshmarket","name","version","--json", …])
  status= 0
  { "name": "dshmarket", "version": "1.66.45" }
pnpm --version  →  10.34.7
node --version  →  v26.4.0
git --version   →  git version 2.56.0
```

即 Web 端「添加插件」发起的正是这条命令，现已能正常返回插件信息。

## 安装

```bash
adb install -r WorkBuddy-3.9.1.apk
```

## 校验

见附件 `SHA256SUMS`。
