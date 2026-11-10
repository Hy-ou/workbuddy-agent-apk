# WorkBuddy 3.13.2

修复「虚拟屏启动应用不生效」：点「启动」后目标没有出现在虚拟屏，App 却提示成功。

## 根因

1. **真机上已有同 App 的任务**：`am start --display <id>` 对单实例/已有任务的应用只会把
   旧任务带到前台，实际留在 display 0；旧代码只看 `am start` 的退出码，误报成功。
2. **部分 App 会自己回到真机**：微信等应用在启动后由**自己的进程**拉起登录页/主界面，
   这些内部 Intent 不带 launchDisplayId，系统把它们放回 display 0。虚拟屏对此无能为力。

## 修复

- 启动前先 `am force-stop <pkg>`，确保在虚拟屏上创建新任务（不再复用真机旧任务）；
- 启动后等待 2 秒，读 `dumpsys activity activities`，按 `Display #N` 段落核对目标包
  实际落在哪块屏；
- 只有任务真正在虚拟屏上才返回成功；落在真机/找不到任务时返回明确错误，
  并在「在虚拟屏启动应用」卡片下方**常驻显示**，同时弹一次 Toast；
- UI 增加说明：启动会结束该 App 在真机上的实例；微信等拒绝副屏的 App 会提示不支持。

## 验证（Redmi K90 Ultra / M332BF，Android 17，KernelSU + Shizuku root）

通过 DSH 宿主桥 `POST /vdisplay`（与 Agent 工具同一条路径）实测：

```json
// status
{"state":"on","display":{"id":12,"width":720,"height":1280,"density":320},"ok":true}

// 启动设置
{"ok":true,"message":"已在虚拟屏启动 com.android.settings"}

// 启动微信
{"ok":false,"error":"系统把 com.tencent.mm 放到了真机（display 0），没有落在虚拟屏（display 12）。微信等带登录/单实例重定向的 App 会拒绝副屏；换支持副屏的 App，或改用真机工具。"}
```

- 58 项单元测试通过，`lintDebug` 0 error。
- 支持副屏的 App（设置、KernelSU、Chrome 等）可以正常启动到虚拟屏；
  微信这类会把界面拉回真机的 App 现在会明确报错，不再假成功。

## 安装

```bash
adb install -r WorkBuddy-3.13.2.apk
```

- 正式包：`WorkBuddy-3.13.2.apk`（116.8 MB，debug 签名）
- 调试包：`WorkBuddy-3.13.2-debug.apk`（128.0 MB）
- SHA-256 见 `SHA256SUMS`

详见 [docs/virtual-display.md](../docs/virtual-display.md)。
