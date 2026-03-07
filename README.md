# 短信转发助手 (SmsForwarder)

轻量、开源的 Android 应用，用于将接收到的短信转发到企业微信/企业微信机器人（Webhook）。设计目标是：小、稳、兼容性好，支持在厂商定制系统（如小米澎湃OS/HyperOS）上长期驻留运行并尽量减少被系统杀死的概率。

仓库：https://github.com/lanbing1989/sms-forwarder

一般用户请直接下载构建好的 APK 文件：

短信转发助手1.1版本（多关键词、单通道） [https://github.com/lanbing1989/sms-forwarder/releases/tag/1.1](https://github.com/lanbing1989/sms-forwarder/releases/tag/1.1)

短信转发助手2.0版本（多关键词、多通道） [https://github.com/lanbing1989/sms-forwarder/releases/tag/v2.0.1](https://github.com/lanbing1989/sms-forwarder/releases/tag/v2.0.1)

---

## 主要更新
在保留第一版简单易用特性的同时，本次更新增加/改进了若干点以提升可用性与扩展性：

- 多通道与规则：支持多个转发通道（企业微信/钉钉/通用 Webhook/短信），以及把关键词映射到不同通道的规则（空关键词表示转发全部）。
- UI 重构：Jetpack Compose 实现的配置界面，支持新增/编辑/删除通道与关键词、日志查看、权限入口与服务控制。
- SMS 通道增强：支持 dual‑SIM 指定发送卡（通过 subscriptionId），并使用 multipart 发送以保证长短信完整转发。
- SIM 刷新：当用户授予 READ_PHONE_STATE 权限后，立即刷新可用 SIM 列表以供选择。
- 深色模式与沉浸式状态栏：自动适配系统深色主题，并在沉浸式状态下保证状态栏图标对比可见。
- 日志改进：运行日志写入内部文件（最新在上），UI 支持完整历史查看、刷新与清除。
- Android 14+ 兼容性：Manifest 与运行时都考虑了 foregroundServiceType 的声明与兼容调用（含反射回退处理）。

---

## 主要功能
- 接收短信并按关键字过滤后转发到指定通道（支持多个关键字、多个通道、并行转发）。
- 支持企业微信、钉钉、任意 HTTP(S) Webhook，以及通过 SMS 转发到目标手机号（支持指定发送 SIM）。
- 常驻前台服务展示状态与最新日志，提高在厂商定制 ROM 上的存活率。
- 可选择开机自启。
- 运行日志保存在应用内部文件（files/sms_forwarder_logs.txt），便于无 adb 环境下查看运行状态。
- 兼容 Android 14+（targetSdk=34）并对厂商 ROM（如 HyperOS）做了兼容处理。

---

## 适用场景
- 企业/团队需要将收发到某台 Android 手机的短信内容转发到企业微信以便统一处理通知或归档。
- 需要在用户允许前提下长期驻留并可靠转发短信的场景。

---

## 免责声明
- 应用会处理短信内容，涉及隐私数据。请仅在合规和经用户授权的场景下使用。
- 请在目标国家/地区遵守关于短信、数据隐私与第三方平台（企业微信等）的相关法律法规与服务条款。

---

## 安装与构建（普通用户 & 开发者）
普通用户：
- 建议直接下载 release 中构建好的 APK 并安装（上文 Releases 链接）。

开发者 / 高级用户：
1. 克隆仓库：
   ```bash
   git clone https://github.com/lanbing1989/sms-forwarder.git
   cd sms-forwarder
   ```
2. 本地构建（需要 JDK 17 / Gradle / Android SDK）：
   ```bash
   ./gradlew clean :app:assembleDebug
   ```
   或在 Android Studio 中打开项目并运行 `app` 模块。
3. 生成的 APK 位于：
   - `app/build/outputs/apk/debug/app-debug.apk`
4. 安装到设备（示例）：
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

---

## 权限与系统要求（必须 / 建议）
- Android 版本要求：minSdk 26，targetSdk 34（已针对 Android 14+ 进行适配）。
- Manifest 中声明（示例）：
  - android.permission.RECEIVE_SMS
  - android.permission.INTERNET
  - android.permission.FOREGROUND_SERVICE
  - android.permission.RECEIVE_BOOT_COMPLETED
  - android.permission.POST_NOTIFICATIONS（Android 13+）
  - android.permission.FOREGROUND_SERVICE_REMOTE_MESSAGING（建议，兼容某些厂商）
- 运行时需要用户授权（视功能使用情况）：
  - RECEIVE_SMS（接收短信）
  - POST_NOTIFICATIONS（显示常驻通知，Android 13+）
  - SEND_SMS（如使用 SMS 通道）
  - READ_PHONE_STATE（如需读取 SIM 信息以选择发送卡）
- 注意：
  - Android 14+ 要求在 manifest 与运行时都声明/指定前台服务类型（本项目声明 `android:foregroundServiceType="remoteMessaging"` 并在运行时做兼容调用）。
  - 在定制 ROM 上，通常还需用户在系统设置中允许“自启动 / 后台保活 / 省电豁免”。

---

## 如何使用（用户指南）
1. 打开应用，进入“转发通道管理”添加通道：
   - 填写名称与类型（企微/钉钉/Webhook/SMS）。
   - 若为 webhook：填写完整有效的 HTTPS URL。
   - 若为 SMS：填写目标手机号（建议 E.164 格式，例如 +8613912345678）；如需指定发送卡，选择相应 SIM（需授权 READ_PHONE_STATE 并点击“获取卡信息”刷新）。
2. 添加关键词规则（“关键词配置”）：
   - 输入关键词（逗号分隔或单条）；留空表示转发全部消息（慎用）。
   - 选择目标通道，保存。
3. 启用“转发服务”开关（首次会请求必要权限）。
4. 可选择“开机自启”以在设备启动后自动启动前台服务（同时请在系统设置中允许自启动 / 后台保活）。
5. 在“日志”页查看运行日志（最新在上），支持刷新和清除。

---

## 配置存储 & 向后兼容
- 新结构（SharedPreferences JSON）：
  - `channels`：JSON array，项包含 { id, name, type, target, simId|null }
  - `keyword_configs`：JSON array，项包含 { id, keyword, channelId }
  - 其它：`enabled`（boolean），`start_on_boot`（boolean）
- 旧版兼容：
  - 早期版本仅用单个 `webhook` 和 `keywords`（逗号分隔）键。仓库已提供迁移示例代码（可在 App 启动时运行一次进行迁移），会把旧配置转换为新结构（创建一个默认通道并按关键词生成规则）。

---

## 调试与常见问题排查

1. 构建失败
   - 常见原因：本地依赖未同步、Kotlin/AGP 与 compileSdk/targetSdk 不匹配、缺少 Compose 图标依赖等。
   - 建议：
     ```bash
     ./gradlew clean :app:assembleDebug --stacktrace
     ```
     检查错误并修复（例如：若报 Icons.* 未解析，确认 `material-icons-extended` 依赖已加入）。

2. 前台服务报 MissingForegroundServiceTypeException
   - 原因：targetSdk >= 34 时，启动前台服务必须在 manifest 与运行时都声明前台服务类型。
   - 检查：
     - Manifest 中是否声明 `android:foregroundServiceType="remoteMessaging"`。
     - 运行时是否尝试以带 type 的 startForeground（代码包含兼容处理：优先读取系统常量或回退）。

3. 无法选择 SIM / SIM 列表为空
   - 检查是否授予 READ_PHONE_STATE 权限；授予后请点击“获取卡信息”或重新打开页面以强制刷新 SIM 列表。
   - 某些 ROM/设备对 subscription 信息访问有限制，可能在部分设备上不可用。

4. 转发失败 / Webhook 无响应
   - 检查 webhook URL 是否正确、目标服务是否可达。
   - 查看 App 内日志或使用 `adb logcat` 查看请求异常。
   - 网络调用使用 OkHttp，具备简单重试（最多 2 次，指数退避）。

5. 短信被截断 / 长短信不完整
   - 已改为使用 `sendMultipartTextMessage`（multipart 发送）以保证完整性。
   - 若仍出现截断，可能是运营商或接收端对长短信的处理限制。

6. 在厂商 ROM（如 HyperOS）上服务被系统杀死
   - 请在系统权限/电池管理中允许自启动、后台保活与省电豁免。
   - 若 BOOT 场景异常，抓取完整 logcat 帮助诊断。

日志采集
- 应用将运行日志写入：`files/sms_forwarder_logs.txt`（最新在上）。
- 可通过 adb 拉取：
  ```bash
  adb shell run-as com.lanbing.smsforwarder cat files/sms_forwarder_logs.txt
  ```

---

## 开发者参考（实现要点）
- 文件结构（核心）：
  - MainActivity.kt — Compose UI、权限与系统栏/主题适配、SIM 刷新 hook
  - SmsReceiver.kt — 接收短信、匹配规则并并行转发（webhook / SMS）
  - SmsForegroundService.kt — 前台服务与通知
  - BootReceiver.kt — 开机启动处理
  - LogStore.kt — 日志存储（文件）
  - models.kt — Channel / KeywordConfig / ChannelType
- 并行与可靠性：
  - webhook 请求采用 OkHttp；每次请求最多重试 2 次，采用指数退避。
  - SmsReceiver 使用 goAsync + 线程池并在所有任务完成后调用 pendingResult.finish()。
- UI 注意：
  - 下拉菜单使用 Material3 的 ExposedDropdownMenuBox，配合 menuAnchor() 以避免在 Compose 中展开失效问题。
  - 日志区域使用自适应布局（Card 使用 weight 填满剩余空间，内部 LazyColumn 使用 fillMaxSize）。
- CI / Actions 常见问题：
  - GitHub Actions 缓存服务偶发 400（会回退并下载依赖），可重试 workflow。
  - 编译错误通常与未导入 Compose 扩展、函数/Composable 分散到不可见文件或拼写错误有关。先在本地运行 assembleDebug 做快速验证。

---

## 贡献与开发流程
- 欢迎提交 issue 或 PR。请在 PR 中说明：
  - 变更目的与实现要点；
  - 隐私/合规影响（若处理短信或敏感数据）；
  - 测试设备/Android 版本与复现步骤。
- 若需我代为创建 PR，请提供目标分支名与简短 PR 描述，我会把改动打包成一个清晰的提交。

---

## 变更记录（简要）
- v1.1 — 初始实现：短信接收、转发（单 webhook + 逗号关键词）、前台服务、日志、开机自启。
- v2.0 — 重构与增强：多通道、多关键词、Compose UI、双卡 SMS 支持、multipart SMS 发送、SIM 刷新、深色模式与沉浸式状态栏适配、日志与运行时兼容性优化。

---

## 许可证
- 本仓库采用 MIT（请在发布时确保包含 LICENSE 文件）。

---

## 联系方式
- 仓库 issues：https://github.com/lanbing1989/sms-forwarder/issues
- 提交问题时请尽量附上：
  - 设备型号与系统版本
  - 完整的 adb logcat（包含异常堆栈）
  - 复现步骤

感谢使用 & 贡献！
