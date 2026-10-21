# WorkBuddy 3.9.2

## 修改说明

### 修复 1：设置页「卸载」失败

`pnpm remove` **不接受 `--registry` 参数**，而卸载复用了安装的参数拼装，于是：

```
ERROR Unknown option: 'registry'
For help, run: pnpm help remove
```

包没被删掉，但界面状态/登记已经变化 —— 看起来就是「卸载有问题」。

**修复**：只有安装（`add`）传 `--registry`，卸载（`remove`）不传。

### 修复 2：DSH 启动报 `Cannot find package 'xxx' imported from profiles/web`

卸载（或安装失败）后，`cordis.patch.yml` 仍留着该插件的 `insert` 条目，包却已从
`node_modules` 消失 → 每次启动内核都会报错并出现「N entry did not activate」。

**修复**：
- 内核启动时**以磁盘为准**：登记表里包已不存在的条目自动丢弃，并立即重写 patch；
- 卸载路径改为「无论 pnpm 是否报错，都先清登记 + 重写 patch」，避免残留。

### 真机验证（debug 包，应用进程内）

```
卸载 dsh-whale-widget  → Done in 831ms using pnpm v10.34.7
                         node_modules 中消失；patch/登记同步移除
安装 dshmarket         → Done in 13.6s using pnpm v10.34.7
重启内核               → 最近一次启动 4 行日志，无 warning、无未激活条目
当前插件：dsh-cost-meter / dsh-free-search / dshmarket（全部可加载）
```

## 安装

```bash
adb install -r WorkBuddy-3.9.2.apk
```

## 校验

见附件 `SHA256SUMS`。
