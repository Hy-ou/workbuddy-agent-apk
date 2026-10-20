# 第三方组件与许可

本项目自身代码以 MIT 许可发布（见 [LICENSE](LICENSE)）。可执行产物中还包含以下第三方组件，
分发 APK 时请一并遵守它们的许可。

## 随 APK 分发的二进制

| 组件 | 版本 | 许可 | 说明 |
|---|---|---|---|
| Node.js（Android 交叉编译） | 由 DSH 官方 payload 提供 | MIT | `jniLibs/arm64-v8a/libdshnode.so` |
| OpenSSL（libcrypto/libssl） | 3.x | Apache-2.0 | `libcrypto_3.so`、`libssl_3.so` |
| ICU | 78 | Unicode-3.0 | `libicuuc_78.so`、`libicui18n_78.so`、`libicudata_78.so` |
| c-ares | 1.3x | MIT | `libcares.so` |
| SQLite | 3.x | Public Domain | `libsqlite3.so` |
| libffi | 3.4 | MIT | `libffi.so` |
| zlib | 1.3 | Zlib | `libz_1.so` |
| libc++ | LLVM | Apache-2.0 WITH LLVM-exception | `libc++_shared.so` |
| Go 标准库 / workbuddy2api 网关 | — | MIT / BSD-3-Clause | `libwb2api.so` |

## 随 APK 分发的内核树（`assets/payload-dsh.zip` 解压后）

| 组件 | 许可 | 说明 |
|---|---|---|
| `@deepseek-ai/dsh` 及其依赖包 | MIT | DeepSeek Harness Agent 内核 0.2.1-rc.2 |
| 第三方 npm 依赖 | 见各包 `LICENSE` | 随内核树保留在 `dshroot/lib/node_modules/**/LICENSE` |

## 源码与设计参考

| 项目 | 许可 | 用途 |
|---|---|---|
| [Sliverkiss/workbuddy2api](https://github.com/Sliverkiss/workbuddy2api) | MIT | 网关原始实现 |
| [linguo2625469/workbuddy2api-panel](https://github.com/linguo2625469/workbuddy2api-panel) | MIT | 网关与管理面板 |
| [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness) | MIT | Agent 内核 |
| [woaiys3/deepseek-harness-android-app](https://github.com/woaiys3/deepseek-harness-android-app) | MIT | Android 运行时打包思路与兼容补丁 |

> 说明：本仓库不包含上述项目的完整源码副本，仅通过构建脚本生成二进制/资产；
> 内核树不做修改，只替换了 `dshhome/cordis.patch.yml`（应用级配置）。
