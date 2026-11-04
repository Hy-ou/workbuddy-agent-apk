# WorkBuddy 3.11.0

## 修改说明

### 新增：账号页支持从 JSON 文件批量添加账号（与 Web 面板一致）

Web 面板的「添加账号」弹窗一直有两个页签：「浏览器登录」和「导入 JSON」，可上传 cockpit tools
导出的账号数组一次补号；手机端此前只有 OAuth 授权一条路径，换机或批量补号时只能逐个登录。

本版把同一能力搬进 App：

- **入口**：账号页右下角「添加账号」→ 顶部页签「导入 JSON」→「选择 JSON 文件」。
- 通过系统文件选择器读取 `.json`，选中后立即上传（与 Web 端一致），不需要先把文件放进 App 目录。
- 导入完成就地显示「成功 N 个，跳过 N 个（共 N 个）」；被跳过的条目会列出前 3 条原因
  （缺少 `uid` / `access_token` / `refresh_token`、`uid` 非法等），失败原因与 Web 端一致。
- 成功后自动刷新账号列表，无需手动下拉。
- 与 Web 端共用网关接口 `POST /panel/api/import/cockpit`（multipart 字段 `file`）：
  按 `domain` 自动区分国内版 / 国际版，导入后顺带签到/注册激活/查额度；同一 `uid`
  重复导入为覆盖凭证，保留原有额度与冷却状态。

### 文件格式

```json
[
  {
    "uid": "123456",
    "nickname": "账号备注",
    "access_token": "...",
    "refresh_token": "...",
    "domain": "www.workbuddy.cn",
    "expires_at": 1767225600000
  }
]
```

- 必填：`uid`、`access_token`、`refresh_token`；`uid` 仅允许字母、数字、`-`、`_`，最长 64。
- `expires_at` 为**毫秒**时间戳（缺省或 ≤0 时按一年后处理）；`domain` 属于 `workbuddy.ai`
  家族时按国际版导入，否则按国内版。
- 文件必须是账号数组（`[...]`），空数组会被网关拒绝。

### 边界与提示

- 单文件上限 32 MiB（与网关 `ParseMultipartForm` 一致），超出时客户端直接提示。
- 空文件、读取失败、JSON 格式错误均有中文提示，不会卡在“正在导入”。
- 连接旧版远程网关（没有该接口）时提示「当前服务器不支持 JSON 导入，请升级网关或改用内置网关」。
- 演示模式下返回模拟导入结果，便于查看界面。

### 实现与验证

- 客户端新增 `WbApi.postFile()`（OkHttp multipart，默认 5 分钟超时）与
  `WbRepository.importCockpit(fileName, bytes)`；结果模型解析 `{ok,total,imported,skipped,errors}`。
- 新增单测覆盖 multipart 上传（路径、Bearer、文件名、文件内容）与 400 `invalid json` 错误透传；
  `testDebugUnitTest` 全绿，`compileDebugKotlin`、`lintDebug`、`assembleRelease` 通过。
- 内置 `libwb2api.so` 已含 `POST /panel/api/import/cockpit` 与 Web 端「导入 JSON」页面资源，
  无需重新编译 Go 网关。

### 升级说明

覆盖安装即可（release 与 debug 包同签名）。历史账号、网关配置与会话均保留。
