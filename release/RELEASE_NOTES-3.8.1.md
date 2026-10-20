# WorkBuddy 3.8.1

## 修改说明

### 修复：DSH 启动失败「The coroutine scope left the composition」

**根因**：DSH 启动是 `suspend fun start()`，由界面通过 `rememberCoroutineScope()` / `LaunchedEffect` 调用。
切走「助手」标签页时 Composable 离开组合，作用域被取消，启动协程抛 `CancellationException`；
这个异常被 `catch (e: Exception)` 当成普通错误处理，于是界面显示「启动失败：The coroutine scope left the composition」。

**修复**：内核生命周期与 UI 解耦

- `DshManager` 自带 `CoroutineScope(SupervisorJob() + Dispatchers.IO)`；
- 新增 `startAsync()` / `restartAsync()` / `startSafeModeAsync()`，界面只调它们（fire-and-forget）；
- `doStart()` 包进 `NonCancellable`，并显式 `catch (CancellationException) { throw e }`，
  确保取消永远不会被记成「启动失败」；
- 启动失败计数（安全模式触发条件）只在真正失败时递增。

**真机验证**：启动后 3 秒立即切到其它标签页 —— 内核仍正常启动（日志输出 token，回环桥 127.0.0.1:7865 正常监听）。

## 安装

```bash
adb install -r WorkBuddy-3.8.1.apk
```

## 校验

见附件 `SHA256SUMS`。
