# WorkBuddy 3.10.1

## 修改说明

### 问题：对话页「添加文件」点了没反应

App 内 DSH 控制台对话页，点输入框左下角「+」→ 菜单里的「文件」后，系统文件选择器完全没有出现：
页面不报错、不弹 toast、也没有任何网络请求。

### 链路上先厘清一件事

DSH 网页**没有独立的「添加附件」按钮**。附件入口只有两条：「+」菜单里的「文件」（回形针图标），
以及把文件/图片直接拖进页面。点菜单项时，前端依次执行
`ctx.filePicker.open()` → `input[type=file].click()`，之后必须由宿主的
`WebChromeClient.onShowFileChooser` 拉起一个系统选择器 Activity。前两步在前端，后两步在宿主。

### 根因

宿主侧 `onShowFileChooser` 只有一条路径 `params.createIntent()`，任何一步不成立都走到
`catch { return false }`：

- `params` 为空；
- 该 Intent 在定制 ROM 上不可启动；
- ROM 裁掉了对应的 DocumentsUI；
- launcher 在 Activity 未 RESUMED 时抛异常。

而 WebView 收到 `false` 是**静默放弃**——不弹窗，也不打任何日志。于是前端看起来就是
「按钮坏了」，排查时也没有线索。同链路还有两个隐藏缺陷：选择结果只读 `data.data`
（多选时系统只填 `clipData`，等于「选多个 = 什么都没选」）；上一次没归还的 `ValueCallback`
会让后续选择器永远不弹。

### 真机验证

Xiaomi M332BF（Android 15，WebView Chromium 149）实测通过：

1. 「+」→「文件」弹出系统文件选择器；
2. 选中 `wb-attach-test.txt` 后，输入框上方出现附件卡片（名称 + `TXT 20B` + 移除按钮）；
3. logcat 全链路打通：`[wb] file input click` → `onShowFileChooser mode=...` →
   `launch ... -> true` → `picker result code=-1 uris=1`；
4. 另用 debug 包的 WebView 远程调试（CDP `DOM.setFileInputFiles`）绕过原生选择器直接给
   input 塞文件，卡片同样出现 —— 证明前端入队/上传没问题，此前「选完没反应」确实只卡在原生
   把 URI 交回 WebView 这一段。

### 修复（`ui/dsh/DshScreen.kt`）

- **选择 Intent 兜底链**：`params.createIntent()` → `ACTION_OPEN_DOCUMENT` →
  `ACTION_GET_CONTENT`，带 `EXTRA_ALLOW_MULTIPLE` / `EXTRA_MIME_TYPES` /
  `FLAG_GRANT_READ_URI_PERMISSION`，逐条 try，直到某一条真正启动成功。
- **结果收集**：`collectPickedUris()` 先读 `clipData` 全部项、再读 `data.data` 去重，多选可用。
- **不再挂起 callback**：新请求先释放上一个，页面销毁时用 `DisposableEffect` 归还。
- **`FILE_PROBE_JS`**：把 React 用 `hidden` 属性渲染的 file input 从 `display:none` 改成
  1px 不可见块（个别 ROM 的 Blink 会忽略对 `display:none` 输入框的程序化 `click()`），
  并在每次点击时输出 `[wb] file input click ...`。
- **全链日志**：新标签 `WbDshFile`（宿主回调入参、每条 Intent 的启动结果、选择结果条数）。

## 安装

```bash
adb install -r WorkBuddy-3.10.1.apk
```

## 验收清单

1. 对话页点「+」→「文件」→ 弹出系统选择器；
2. 选 1 个文件 → 输入框上方出现文件卡片，上传完成后可直接发送；
3. 选多个文件 → 卡片按选择顺序全部出现（这条在旧版会全部丢失）；
4. 取消选择 → 不报错，可再次点开选择器；
5. 抓 `adb logcat -s WbDshFile WbDshWeb` 应能看到
   `[wb] file input click ...` → `onShowFileChooser mode=...` → `launch ... -> true` →
   `picker result code=-1 uris=N`。

## 排查

若仍不弹窗，按 logcat 组合定位（详见 `docs/troubleshooting.md` 第 10 节）：

| logcat | 结论 |
|---|---|
| 无 `[wb] file input click` | 前端没点到 input（DSH UI 层，非宿主问题） |
| 有 click，无 `onShowFileChooser` | WebView 没回调 |
| 有 `onShowFileChooser` + `launch ... -> false` | 设备没有可启动的选择器 Activity |
| 有 `picker result ... uris=N` | 宿主侧已通，问题回到前端或上传准入 |

## 校验

见附件 `SHA256SUMS`。
