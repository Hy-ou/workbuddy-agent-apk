# WorkBuddy 3.6.0

社区插件一键安装、DSH 侧通知工具、扩展中心（npm CLI）。

## 新增

- **内置 npm**（+3 MB）：内核自带精简 npm，用于安装插件与命令行工具。
- **社区插件一键安装**：设置 → 扩展能力 → 社区插件，12 个精选插件直接安装/卸载，
  装完自动写入 `cordis.patch.yml`，重启内核即出现在 DSH 插件面板。
- **DSH 通知工具**：内置 `@workbuddy/dsh-tool-notify`，Agent 可调用 `notify` 工具，
  通过宿主回环桥（127.0.0.1:7865，随机 token）发系统通知。
- **扩展中心（CLI 工具）**：typescript / tsx / prettier / eslint / http-server / nodemon
  一键安装到内核 PATH，Agent 可直接调用。

## 安装

```bash
adb install -r WorkBuddy-3.6.0.apk
```

升级后首次启动会补解压 npm 目录（约 3 秒）。社区插件/工具安装需要联网（默认 npmmirror 源）。

## 边界

- 扩展中心目前提供 npm 生态的 CLI 工具；Python/Go/Rust/Clang 等系统工具链
  （参考项目的 19 项 Termux 扩展）需要镜像选路、deb 解包与依赖闭包，属于后续迭代。
- 社区插件按 npm 依赖自动安装；极少数需要原生编译的插件可能失败，面板会显示原因。

## 校验

见附件 `SHA256SUMS`。
