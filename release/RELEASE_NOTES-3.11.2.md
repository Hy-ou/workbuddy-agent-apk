# WorkBuddy 3.11.2

## 修改说明

### 问题：面板导出的账号 JSON 在手机端导入失败

在 Web 面板用「导出」或「批量导出」拿到 JSON 后，到 App「添加账号 → 导入 JSON」选择该文件：

- **单账号导出**（一个对象）直接报错：

  ```text
  导入失败：invalid json: json: cannot unmarshal object into Go value of type []panel.cockpitAccount
  ```

- **批量导出**（对象数组）虽然返回成功，但结果是「成功 0 个，跳过 N 个」，
  错误条目为 `missing required fields (id=)`。

### 根因：导出格式与导入解析器不一致

面板导出的 JSON 与 `auths/workbuddy-*.json` 同构，是嵌套形：

```json
{
  "auth": {
    "accessToken": "…",
    "refreshToken": "…",
    "expiresAt": 1753600000,
    "domain": "www.codebuddy.cn",
    "realm": "cn"
  },
  "account": {
    "uid": "…",
    "enterpriseId": "",
    "nickname": "…"
  },
  "device_token": "…"
}
```

而网关的 `POST /panel/api/import/cockpit` 之前只接受 cockpit tools 的扁平数组：

```json
[{"uid":"…","access_token":"…","refresh_token":"…","expires_at":1753600000000}]
```

嵌套对象被 `json.Unmarshal` 直接拒绝（400）；嵌套数组则被扁平结构忽略成空字段，
于是全部条目因缺少 `uid/access_token/refresh_token` 被跳过。

### 修复

- 网关 `internal/panel/import.go` 新增 `parseImportAccounts`，同时兼容：
  1. cockpit tools 扁平数组；
  2. 面板单账号导出对象；
  3. 面板批量导出对象数组。
  嵌套形统一折算成导入用的扁平结构：`expiresAt` 秒→毫秒，`realm` / `enterpriseId` /
  `device_token` 保留。
- 导入循环改为显式 `realm` 优先（面板导出文件带 realm），并把 `enterprise_id` /
  `device_token` 一并写入 auth 文件。
- 重新交叉编译内置 `libwb2api.so`（arm64-v8a / x86_64）；App 版本 3.11.2 / versionCode 26。
- App 导入页提示更新为「支持 cockpit tools 账号数组，也支持本面板导出的凭证 JSON（单个或批量）」。

### 验证

- 面板单账号导出文件 → 网关导入 `imported=1, skipped=0`。
- 面板批量导出文件（2 条）→ 网关导入 `imported=2, skipped=0`。
- 原 cockpit tools 扁平数组仍可导入（回归）。
- 真机内置网关（arm64-v8a）实测：单账号/批量嵌套文件均导入成功，测试账号已清理。
- `go test ./...` 全绿；Android `testDebugUnitTest` 通过。

## 安装

覆盖安装即可（与 3.11.1 同签名，保留数据）。

> 如果手机 App 连接的是**远程服务器**而非内置网关，需要同时把该服务器上的
> `workbuddy2api` 升级到包含本次导入兼容修复的版本。
