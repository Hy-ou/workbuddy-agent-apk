# WorkBuddy 3.8.2

## 修改说明

### 修复 1：启动日志警告 `dsh: warning: 1 entry did not activate`

`cordis.patch.yml` 里残留了 `wb-notify` 条目，每次启动都报
`ERR_MODULE_NOT_FOUND: Cannot find package '@workbuddy/dsh-tool-notify'`。

- **根因**：清理旧插件时按**包名**删除，而 `NotifyPlugin.PACKAGE` 常量已改为
  `@deepseek-ai/dsh-tool-notify`，与登记表里的旧包名对不上，删不掉。
- **修复**：新增 `InstalledPlugins.removeById()`，按插件 **id** 删除；patch 不再残留。

### 修复 2：社区插件安装失败

真机逐层排查，四个原因全部修掉：

| # | 现象 | 原因 | 修复 |
|---|---|---|---|
| 1 | `The operation was rejected by your operating system` | 装到内核树 `/sdcard/WorkBuddy/dsh/...`，FUSE 拒绝 npm 的原子重命名；且 DSH 只从 profile 解析插件 | 改为在 `dshhome/profiles/web` 里安装（与官方行为一致） |
| 2 | `spawnSync .../@pnpm/exe.android-arm64/pnpm EACCES` | pnpm ≥9 自带**原生** `@pnpm/exe`，应用数据目录不可执行 | 固定 pnpm@10，`--omit=optional --ignore-scripts`，只用纯 JS 的 `bin/pnpm.cjs` |
| 3 | `ERR_PNPM_UNEXPECTED_STORE` | `node_modules/.modules.yaml` 记录了旧 pnpm 的 storeDir | 安装前把 `storeDir` 对齐到 `files/.local/share/pnpm/store/v10` 并建目录；同时去掉会改变默认 store 的 `XDG_DATA_HOME` |
| 4 | `ERR_PNPM_ADDING_TO_ROOT` | profile 是 pnpm workspace 根 | `pnpm add/remove` 加 `--workspace-root` |

**真机验证（应用内路径）**

```
wbPlugin: Done in 1s using pnpm v10.34.7          RESULT ok=true
profiles/web/node_modules/dsh-cost-meter          ← 已安装
cordis.patch.yml: - id: dsh-cost-meter            ← 自动登记
重启内核日志：
  [dsh-cost-meter] 已加载,账本:…/storages/cost-meter/ledger.json
  dsh web: http://127.0.0.1:8300/?token=…
（本次启动无 warning、无未激活条目）
```

## 安装

```bash
adb install -r WorkBuddy-3.8.2.apk
```

社区插件安装需要能访问 npmmirror（默认源）。安装/卸载后需重启内核生效。

## 校验

见附件 `SHA256SUMS`。
