# WorkBuddy 3.11.3

## 修改说明

### 修复：锁屏后内置网关「停止工作」（进程还在但不应答 / 过夜后进程消失）

**现象**

- 锁屏一段时间再回来：通知还在、`libwb2api.so` 也还在，但锁屏期间网关的定时任务
  （签到 / 保活 / 余额刷新）一条都没跑，`/healthz` 直接超时；
- 锁屏过夜更严重：通知消失、进程没了，只能重开 App；
- 偶发「通知写着网关运行中、实际端口无人应答」。

**根因（四层叠加）**

1. **HyperOS 的 GreezeManager 锁屏即冻结整个 UID**（`reason=screen_off`，切后台 `reason=tobg`）。
   它日志里打印了 `HasForegroundService` 仍判 `freeze_able` —— 前台服务在这套 ROM 上不是豁免条件。
   冻结按 UID 生效，Go 网关与宿主同 cgroup 一起停摆，`wb:gateway` wakelock 被置 `DISABLED`。
2. 全程没有 wakelock：前台服务只防回收、不防 CPU 休眠，屏幕一灭 Go 的定时器就停。
3. `dataSync` 前台服务在 Android 15+ 有 6 小时 / 24 小时后台配额，跑满后 `Service.onTimeout()`
   默认实现直接抛异常杀进程（锁屏过夜必现）。
4. 没有恢复路径：`onDestroy` 无条件杀网关，`START_STICKY` 重建后不重拉网关，无看门狗、无回前台自检。

**修复**

- 前台服务类型 `dataSync` → `specialUse`（`android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE=local_api_gateway`），
  新增 `WAKE_LOCK`、`FOREGROUND_SERVICE_SPECIAL_USE` 权限；
- `GatewayService`：服务存活期持 `PARTIAL_WAKE_LOCK`（看门狗 30s 幂等续持）、重写 `onTimeout()`、
  30s 看门狗 `ensureRunning()`、`onTaskRemoved` 不跟着停、`onDestroy` 只在用户主动停止时才杀网关；
- `LocalGatewayManager`：`ensureRunning()` 幂等重拉、`stopByUser()` / `consumeStopRequest()`
  区分用户停止与系统回收、`watchExit()` 进程退出立刻置 Failed（杜绝假活）；
- `AppViewModel` + `MainActivity`：`ON_RESUME` 自检，解锁回前台自动恢复并重挂顺延端口；
- 设置页「运行体检」新增 **锁屏冻结（HyperOS）** 引导：一键跳「省电策略」/「自启动管理」。

**HyperOS / MIUI 额外设置（应用无法自行豁免）**

设置 → 应用设置 → 应用管理 → WorkBuddy → **省电策略 = 无限制**；允许**自启动**；
最近任务里给应用**上锁**。不设的话系统仍会在锁屏 / 切后台后冻结 UID。

**验证**（Redmi M332BF / Android 17 / HyperOS）

- `dumpsys activity services` → `types=0x40000000`（specialUse）、`isForeground=true`；
- `dumpsys power` → `PARTIAL_WAKE_LOCK 'wb:gateway'` 已持有；
- 关屏 8 分钟：未加白名单时 `cgroup.freeze=1`、`/healthz` 超时、wakelock 变 `DISABLED`（复现）；
- 解锁：`cgroup.freeze=0`、`/healthz` 立即 200、wakelock 自动恢复（自愈）。

详细复现与验证命令见 `docs/troubleshooting.md` 第 12 节。

### 版本

- `versionCode 27` / `versionName 3.11.3`
