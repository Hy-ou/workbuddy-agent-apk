# WorkBuddy 3.13.1

修复两处问题：

1. **虚拟屏在 Android 17 / HyperOS 上无法真正启动 App**（预览黑屏、`am start --display` 被拒）；
2. **DSH 全屏模式顶部仍有黑边**（挖孔避让内边距留下的深色条）。

## 一、虚拟屏重写

3.13.0 的实现是「App 自己创建 VirtualDisplay / overlay display」。在 Android 17 上：

- `screencap -d <逻辑 id>` 报 `Display Id 'x' is not valid`（它要 SurfaceFlinger 物理 ID）；
- `ActivityTaskSupervisor` / `SecurityManagerService.isVirtualDisplayActive` 明确拒绝
  普通 App 把**其他 App** 启动到自建虚拟屏（即使自己是 display owner）；
- 即使绕过，HyperOS 的跨应用「应用跳转」确认也无法显示在虚拟屏上。

3.13.1 改为 **shell/root 侧 `app_process` 服务**：

- `assets/vds.dex`（源码 `android/vds-helper`）由 Shizuku 以 uid 2000/root 启动；
- 服务创建 `VirtualDisplay(PUBLIC | OWN_CONTENT_ONLY)`，成为 display owner；
- 服务把 `id/pid/meta` 与最新帧 `frame.png` 写到 App 缓存，App 直接读，不再 `screencap`；
- 启动 App 用**同一 uid** 的 `am start --display <id>`，既符合虚拟屏限制，也不触发
  HyperOS 的应用跳转确认；
- 输入仍用 `input -d <id>`；
- 服务独立于 App 进程；关闭按钮 `pkill` 服务；App 重进控制台用 `kill -0 <pid>` 校验，
  服务已死会清掉残留状态（不再出现「服务没了还显示运行中」）。

**前置条件**：Android 8+，Shizuku 已授权（普通设备 shell / KernelSU 下 root）。
创建、截图、启动、输入都依赖 Shizuku。

## 二、DSH 全屏黑边

去掉全屏时 WebView 外层的 `WindowInsets.displayCutout` 内边距，页面顶到 y=0；
顶部浮动「退出全屏」按钮仍按 `statusBars ∪ displayCutout` 避让挖孔。

## 三、版本与验证

- 版本：**3.13.1 / versionCode 30**
- 单元测试：58 项通过；`lintDebug` 0 error
- 真机验证：Redmi K90 Ultra（M332BF）/ Android 17 / KernelSU root + Shizuku root
  - 安装 3.13.1 → 设置 → 虚拟屏 → 720×1280@320 → displayId 9，owner uid 0；
  - 启动 KernelSU：`Display #9` 任务 `visible=true`，`frame.png` 98 KB，控制台预览显示
    KernelSU 界面；真机屏幕保持 WorkBuddy；
  - 杀掉服务后重进控制台：状态回到「未开启」，残留 id/frame 被清理；
  - DSH 全屏截图：内容从 y=0 开始，无顶部黑边。
- 未覆盖：非 root 的 shell-only Shizuku、其他 ROM、支付/银行类页面。

## 四、安装

```bash
adb install -r WorkBuddy-3.13.1.apk
```

- 正式包：`WorkBuddy-3.13.1.apk`（116.8 MB，debug 签名，可覆盖 3.13.0/3.12.x）
- 调试包：`WorkBuddy-3.13.1-debug.apk`（128.2 MB）
- SHA-256 见 `SHA256SUMS`。

## 五、已知限制

单实例应用（微信等）启动到虚拟屏后真机窗口会被移走；`FLAG_SECURE` 页面截图黑屏；
目标 App 自己的跨应用跳转（例如 License 检查打开 Play 商店）可能落到真机；
不要用虚拟屏做支付、转账等敏感操作。详见
[docs/virtual-display.md](../docs/virtual-display.md)。
