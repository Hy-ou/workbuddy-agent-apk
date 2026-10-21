# WorkBuddy 3.9.3

## 修改说明

### 修复：Web 端「添加插件」`spawn pnpm EACCES`（真正根因）

前两版修的是 pnpm 入口本身，但这次 dump 内核进程的环境变量才看到真凶：

```
PATH=…/dsh-home/tools/node_modules/.bin : …/dsh-home/toolchain/usr/bin :
     /storage/emulated/0/WorkBuddy/dsh/bin : …/lib/arm64 : /system/bin : /system/xbin
```

**`home/bin` 不在 PATH 里**（早前插 `toolchain/usr/bin` 时把这一项覆盖掉了）。
于是 `pnpm` 一路落到 `/storage/emulated/0/WorkBuddy/dsh/bin/pnpm` —— 那个文件在 `/sdcard` 上，
挂载带 **noexec**，exec 直接 `EACCES`。

**修复**：`PATH` 第一位固定为 `home/bin`
（里面的 `pnpm` / `npm` / `node` / 工具链命令都是指向 jniLibs 启动器 `libwbnode_shim.so` 的符号链接，
exec 的是 `apk_data_file`，允许执行）。

### 验证：用内核进程的真实 PATH 复现同一条命令

```
which pnpm
→ /data/user/0/com.workbuddy2api.panel/files/dsh-home/bin/pnpm

pnpm view dshmarket name version description dsh --json \
     --registry=https://registry.npmmirror.com/ --config.fetch-retries=0
→ {
    "name": "dshmarket",
    "version": "1.66.45",
    "description": "Visual plugin market inside DeepSeek Harness …"
  }
```

注意：`PATH` 是在**内核启动时**注入的，升级后请重启内核（设置 → 助手引擎，或 KILL 掉 DSH 后重进「助手」页）再试。

## 安装

```bash
adb install -r WorkBuddy-3.9.3.apk
```

## 校验

见附件 `SHA256SUMS`。
