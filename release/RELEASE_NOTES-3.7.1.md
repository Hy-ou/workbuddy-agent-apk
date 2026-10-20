# WorkBuddy 3.7.1

## 修改说明

### 修复：16 KB 页对齐告警（Android 15「Android 应用兼容性」弹窗）

- **原因**：3.7.0 引入的 ELF 启动器 `libwbnode_shim.so` 用默认链接参数编译，`PT_LOAD` 对齐是 4 KB
  （`0x1000`），不满足 Android 15 起的 16 KB 页要求，触发系统兼容性弹窗。
- **修复**：启动器改为 `-Wl,-z,max-page-size=16384` 重新编译（arm64-v8a / x86_64）。
- **全量核查**：APK 内 15 个 arm64 库（node 运行时、ICU/OpenSSL/zlib、Go 网关、AAR 库、启动器）
  现在全部为 `0x4000`（16 KB）对齐，逐库确认。
- **防回归**：新增 `android/dsh/check-16kb.ps1`，一条命令校验 APK 内所有 `.so` 的对齐，
  不合格直接退出码 1，可接入 CI。

### 验证

```bash
pwsh android/dsh/check-16kb.ps1 -Apk app/build/outputs/apk/release/app-release.apk
# all 15 libraries are 16 KB aligned
```
- 真机安装 3.7.1（debug）后启动，logcat 无 16 KB / alignment 告警输出。

## 安装

```bash
adb install -r WorkBuddy-3.7.1.apk
```

## 校验

见附件 `SHA256SUMS`。
