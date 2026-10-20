# 安装包（通过 GitHub Releases 分发）

APK 体积约 107 / 117 MB，超出 git 仓库的合理承载（GitHub 单文件 100 MB 硬限制），
因此**不入库**，作为 [GitHub Releases](../../releases) 附件发布。仓库里只保留校验值与发布说明。

| 附件 | 大小 | 说明 |
|---|---|---|
| `WorkBuddy-3.3.0.apk` | 106.8 MB | 正式包（release，debug 签名，可直接安装） |
| `WorkBuddy-3.3.0-debug.apk` | 117.5 MB | 调试包（可用 `run-as`、WebView 远程调试） |
| `SHA256SUMS` | — | 两个 APK 的 SHA-256 校验值 |

## 下载

**浏览器**：`https://github.com/Hy-ou/workbuddy-agent-apk/releases/latest`

**GitHub CLI**

```bash
gh release download v3.3.0 -R Hy-ou/workbuddy-agent-apk -p "*.apk"
gh release download v3.3.0 -R Hy-ou/workbuddy-agent-apk -p "SHA256SUMS"
```

**curl（公开仓库，无需登录）**

```bash
BASE=https://github.com/Hy-ou/workbuddy-agent-apk/releases/download/v3.3.0
curl -LO $BASE/WorkBuddy-3.3.0.apk
curl -LO $BASE/WorkBuddy-3.3.0-debug.apk
curl -LO $BASE/SHA256SUMS
```

## 校验与安装

```bash
sha256sum -c SHA256SUMS                      # Linux / macOS / Git Bash
# Get-FileHash .\WorkBuddy-3.2.0.apk -Algorithm SHA256   # Windows PowerShell

adb install -r WorkBuddy-3.3.0.apk
```

debug 包与 release 包同签名，可互相覆盖安装，无需卸载。

## 兼容性

- 最低 Android 7.0（API 24）；验证设备：Xiaomi M332BF（Android 15，arm64-v8a）
- DSH 内核仅支持 arm64-v8a；x86_64 设备可用网关与原生助手
- release 包当前使用 debug 签名，正式分发请替换自有 keystore 后重新构建

## 维护者：发布新版本

1. 构建并复制产物到本目录（`release/*.apk` 已被 `.gitignore` 忽略）：

   ```powershell
   gradle -p android assembleRelease
   Copy-Item android/app/build/outputs/apk/release/app-release.apk release/WorkBuddy-3.3.0.apk
   ```

2. 更新本目录的 `SHA256SUMS` 与 `RELEASE_NOTES-<version>.md` 并提交。
3. 打 tag 并上传附件：

   ```powershell
   pwsh tools/publish-release.ps1 -Tag v3.3.0 -Repo Hy-ou/workbuddy-agent-apk
   ```

   脚本会先校验 SHA-256，再调用 `gh release create/upload`；没有 `gh` 时会打印等价的 `curl` 命令。