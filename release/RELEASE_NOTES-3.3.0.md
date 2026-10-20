# WorkBuddy 3.3.0

宿主层能力增强：让 DSH 内核在真机上更稳、更省内部存储、更好排查。

## 新增

- **系统 CA 合成**：启动前把系统证书库合并为 `cacert-system.pem`，企业代理 / 自签 CA 可用；
  内置 Mozilla bundle 仍作兜底。
- **WebView 兼容自检**：读取 Chromium 主版本，低于 80 时提示；「设置 → 运行环境」展示版本与 ABI。
- **状态栏跟随页面底色**：控制台切换深/浅主题时，状态栏与导航栏同步（JS 桥 + MutationObserver）。
- **电池优化白名单**：一键跳系统页，状态在运行体检中展示。
- **更新检查**：读取 GitHub Release 最新版本并与本地比较，提供下载入口。
- **内核树拆分**：`dshroot/`、`bin/` 外置到 `/storage/emulated/0/WorkBuddy/dsh`，
  内部只保留 `dshhome/`、`runtime/`、凭证与日志（`/sdcard` 不支持 chmod，凭证必须留内部）。
  旧版本首次启动会自动拆分迁移，无需清数据。
- **运行体检卡片**：电池白名单 / WebView / 架构 / 内核树位置 / 版本更新一处查看。

## 修复

- 迁移前先结束遗留 node 进程（否则其 cwd 占用旧目录，旧树删不掉）。
- 带 `tab`/`mode` extra 启动时强制切到目标页（此前 `rememberSaveable` 会恢复旧 Tab）。

## 安装

```bash
adb install -r WorkBuddy-3.3.0.apk
```

升级安装即可，内核树会自动迁移；如遇异常可在「设置 → 运行环境」查看内核树位置与 WebView 版本。

## 校验

见附件 `SHA256SUMS`。
