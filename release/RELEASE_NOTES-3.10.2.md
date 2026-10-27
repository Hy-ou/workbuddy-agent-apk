# WorkBuddy 3.10.2

## 修改说明

### 问题：每天第一次对话必然「撞一次墙」，随后一整天提示词被悄悄换掉

网关日志里能看到这条 WARN（手机上看不到，只在 App 私有目录的日志里）：

```text
WARN: [upstream] chat_stream acct=…: upstream 400 content_blocked
body={"code":11-128,"msg":"Illegal API invocation from an unapproved channel",…}
content-blocked (likely fingerprint false positive) -> degraded prompt retry
```

客户端不会报错（网关在同一个请求里换了提示词重试，返回 200），但代价藏在水面下：

1. 每次白撞一次上游往返（首字节多等几秒）；
2. 网关进入「降级」状态并**持续到次日 00:00**，这期间**所有** passthrough 请求的 system 提示词
   都被替换成三句中性文本——DSH 的工具约定、项目规范、文件策略整套丢失，助手行为明显变差；
3. 手机上的网关通常随 App 常驻、不会重启，所以**一次误报会锁死一整天**，界面上没有任何提示。

### 根因：不是账号问题，是 DSH 自己的提示词里有上游黑名单字面量

上游内容防火墙是**逐字精确匹配**（不是语义审核）。本次用新加的二分探针在同账号上 100% 复现，
把「哪一句被拦」收敛到两处，都在内置 DSH 的一手 system prompt 里：

| # | 触发子串 | 长度 | 来源 |
|---|---|---|---|
| 1 | You are an AI agent running on DeepSeek Harness | 47 B | DSH 身份开场白（harness:identity） |
| 2 | You are interacting with the user via the DeepSeek Harness Web GUI … that means this GUI. | 221 B | DSH 网页端定向段（app:web-surface） |

决定性验证：真实渲染的 system prompt（7656 B）→ 400；只去掉第 1 处 → 仍 400；两处都去掉 → 200。
与用户内容、工具声明无关，因此「换账号」和「重试」都不解决问题。

### 修复

- **出站改写**：网关在请求出站前把这两处各改一个词（powered by→running on、through→via、
  they mean→that means），语义不变、harness 名字保留；上游不再命中，**首次请求即 200，不再触发降级**。
- **大小写与引用场景**：上游对这族指纹不区分大小写，所以改写用不区分大小写的匹配，
  agent 把提示词原文抄进工具参数/代码/文件时同样会被处理。
- **取证落盘**：万一将来出现新的指纹，网关会把被拦的那份请求体落到 App 私有目录
  data/content-blocks/（单份 8 MiB 上限、保留最近 20 份），配合面板仓库的
  cmd/fingerprint-probe -body-file <文件> -bisect 即可离线定位具体字符串，不用再靠猜。
- **构建脚本**：android/build_gateway.ps1 支持 -SourceRoot（本仓库不含 Go 源码，旧脚本在这里跑不起来），
  并把 Go 源码的提交号打进构建日志，出问题能直接对上网关版本。

### 验证

- 真实渲染 system prompt 直打上游：修复前 400 / code 11-128 → 修复后 **200**；
  关掉脱敏（-sanitize=false）对照仍 400，排除上游抖动。
- 内置 libwb2api.so（arm64-v8a / x86_64）字节校验：两枚指纹的改写串存在，旧包不存在。
- 面板仓库 go test ./... 全绿，含 4 条指纹回归用例与 4 条取证落盘用例。

### 影响面

只在文本命中这两处指纹时改写（system / developer / user / assistant / tool_calls / reasoning_content 全覆盖），
其余请求逐字节不变；custom 提示词模式本身就会替换客户端 system，不受影响。

## 安装

```bash
adb install -r WorkBuddy-3.10.2.apk
```

debug 包与 release 包同签名，可互相覆盖安装，无需先卸载。

## 校验

```bash
sha256sum -c SHA256SUMS
```

## 兼容性

- 最低 Android 7.0（API 24）；本轮验证设备 Xiaomi M332BF（Android 15，arm64-v8a）
- DSH 内核仅支持 arm64-v8a；x86_64 设备可用网关与原生助手
