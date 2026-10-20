# WorkBuddy 3.6.1

修复 DSH 侧通知工具的接入方式（3.6.0 的插件方案会被 DSH 解析器判为找不到包）。

## 变更

- **通知改为 node 脚本 + 环境变量**：内核启动时注入 `WB_NOTIFY`（脚本路径）与
  `WB_BRIDGE_TOKEN`（回环桥 token）。Agent 用 bash 工具执行：
  `node "$WB_NOTIFY" "任务完成" "已整理 3 个文件"` → 宿主回环桥 → 系统通知。
- 移除 3.6.0 的 `@workbuddy/dsh-tool-notify` 插件登记（DSH 只解析自带作用域插件，
  第三方包 insert 会报 `Cannot find package`，属上游限制）。
- 保留：内置 npm、社区插件一键安装、扩展中心（npm CLI）。

## 验证

- 回环桥 `POST /notify` 返回 `{"ok":true}`，通知栏出现 `wb_agent_notify` 渠道的通知（真机确认）。

## 安装

```bash
adb install -r WorkBuddy-3.6.1.apk
```

## 校验

见附件 `SHA256SUMS`。
