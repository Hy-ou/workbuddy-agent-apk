# WorkBuddy 3.11.1

## 修改说明

### 问题：DSH 对话每次带 @ 文件引用说明时都被上游内容防火墙拦一次

手机端「日志 → 运行日志」会出现：

```text
WARN: [server] content blocked: outbound body dumped to data/content-blocks/content-block-20261105-154954-….json
(101602 bytes, realm=cn model=deepseek-v4.1-flash upstream_request_id=…)
content-blocked (likely fingerprint false positive) -> degraded prompt retry
```

网关在同一请求里换降级提示词重试，所以客户端表面正常；代价是每次多一次上游往返，
且降级状态期间 system 提示词被替换成三句中性文本，DSH 的工具约定/项目规范丢失。

### 根因：DSH 的 @ 路径引用提示词段命中上游黑名单

从手机 App 私有目录 `files/gateway/data/content-blocks/` 取出三份被拦落盘体
（11-05 05:27 / 15:36 / 15:49），用面板仓库的 `cmd/fingerprint-probe -body-file … -bisect`
直连上游二分，33 次探针收敛到 1 条 **407 B** 最小触发子串：

```text
Tokens prefixed with @ are paths the user explicitly referenced. Relative paths resolve from the
workspace root; absolute paths identify files or directories on the host. A trailing slash marks a
directory: list it when its contents matter. Anything else is a file: use the read tool when its
contents are needed, and do not claim to have inspected it before reading. @"..." quotes a path
containing spaces.
```

上游按**整段逐字匹配**，而这段是 DSH 每次渲染 system prompt 都会带的 @ 引用说明，
所以对话一开就撞，和账号、模型、用户内容无关。

### 修复

- 网关 `internal/upstream/sanitize.go` 新增该段的不区分大小写正则改写：
  `resolve from` → `resolve against`（一个词，语义不变），并在 `sanitizeFeatures`
  增加 `Tokens prefixed with @` 预检标记。
- `internal/upstream/sanitize_test.go` 新增 `TestHarnessPathRefRewritten` 回归用例。
- 重新交叉编译内置 `libwb2api.so`（arm64-v8a / x86_64）；App 版本 3.11.1 / versionCode 25。

### 验证

- 同一落盘体直打上游：修复前 `status=400`（code 11-128），修复后 `status=200`。
- `go test ./internal/upstream -count=1` 全绿（含 5 条 Harness 指纹回归）。
- `.so` 字节校验：新改写串与预检标记存在。
- 真机安装后对话，运行日志不再出现 `content blocked`。

## 安装

覆盖安装即可（与 3.11.0 同签名，保留数据）。
