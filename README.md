# 短信转发助手 (SmsForwarder)

<div align="center">

![Version](https://img.shields.io/badge/version-2.5.2-blue.svg)
![Android](https://img.shields.io/badge/Android-8.0%2B-brightgreen.svg)
![License](https://img.shields.io/badge/license-MIT-orange.svg)
[![GitHub Stars](https://img.shields.io/github/stars/jinghong-me/sms-forwarder?style=social)](https://github.com/jinghong-me/sms-forwarder/stargazers)

轻量、稳定、开源的 Android 短信转发应用

[快速开始](#快速开始) • [功能特性](#功能特性) • [下载安装](#下载安装) • [使用指南](#使用指南)

</div>

---

## ✨ 功能特性

### 🎯 核心功能
- **多通道支持** - 企业微信、钉钉、飞书、通用 Webhook 四种转发通道
- **关键词过滤** - 灵活的关键词规则配置，空关键词转发全部
- **验证码提取** - 自动识别并突出显示短信验证码，方便复制
- **本机号码识别** - 双卡设备支持识别接收短信号码，转发时显示本机号码
- **自定义 SIM 号码** - 无法自动获取时支持手动输入本机号码
- **可配置消息格式** - 灵活配置是否显示本机号码、发送者号码、验证码
- **SIM 卡信息预览** - 前端显示 SIM 卡号码状态，提前预知能否获取
- **消息去重** - 5秒内相同内容自动去重，避免重复转发
- **智能重试** - 失败消息最多重试3次，指数退避策略（2s/4s/6s）
- **持久化存储** - 失败消息保存本地，应用重启或网络恢复自动重试

### 🛡️ 可靠性设计
- **前台服务常驻** - 提高在厂商定制 ROM（如 HyperOS）上的存活率
- **网络状态监听** - 网络恢复自动触发失败消息重试
- **运行日志记录** - 完整日志保存，方便排查问题
- **电池优化引导** - 引导用户加入白名单，减少系统杀死概率

### 🎨 用户体验
- **Material Design 3** - 现代化 UI 设计
- **深色模式** - 自动适配系统主题
- **规则测试** - 在 App 内测试关键词规则
- **开机自启** - 设备启动后自动运行

---

## 📥 下载安装

### 最新版本
| 版本 | 说明 | 下载链接 |
|------|------|----------|
| **v2.5.2** | 调整页面内容分配，首页包含服务开关和开机启动，设置页包含SIM卡和消息格式 | [Releases](https://github.com/jinghong-me/sms-forwarder/releases) |
| v2.5.1 | 修复自定义 SIM 号码显示逻辑问题，避免重复出现 SIM 卡 | [Releases](https://github.com/jinghong-me/sms-forwarder/releases) |
| v2.5.0 | 新增底部导航栏，将功能分为首页、关键词、通道、设置、日志5个标签页 | [Releases](https://github.com/jinghong-me/sms-forwarder/releases) |
| v2.4.1 | 优化自定义 SIM 号码功能，添加更清晰的使用提示 | [Releases](https://github.com/jinghong-me/sms-forwarder/releases) |
| v2.4.0 | 新增自定义 SIM 号码功能，无法自动获取时支持手动输入 | [Releases](https://github.com/jinghong-me/sms-forwarder/releases) |
| v2.3.0 | 新增消息格式配置选项、SIM 卡信息预览 | [Releases](https://github.com/jinghong-me/sms-forwarder/releases) |
| v2.2.0 | 新增本机号码识别，双卡设备支持显示接收短信号码 | [Releases](https://github.com/jinghong-me/sms-forwarder/releases) |
| v2.1.3 | 修复验证码识别错误，优化提取逻辑 | [Releases](https://github.com/jinghong-me/sms-forwarder/releases) |
| v2.1.2 | 调整验证码显示顺序，优化消息格式 | [Releases](https://github.com/jinghong-me/sms-forwarder/releases) |
| v2.1.0 | 验证码自动提取与突出显示 | [Releases](https://github.com/jinghong-me/sms-forwarder/releases) |
| v2.0.2 | 统一消息前缀格式 | [Releases](https://github.com/jinghong-me/sms-forwarder/releases) |
| v2.0.1 | 多关键词、多通道 | [v2.0.1](https://github.com/jinghong-me/sms-forwarder/releases/tag/v2.0.1) |
| v1.1 | 多关键词、单通道 | [v1.1](https://github.com/jinghong-me/sms-forwarder/releases/tag/1.1) |

---

## 🚀 快速开始

### 环境要求
- Android 8.0 (API 26) 及以上
- 需要短信接收权限、通知权限

### 安装步骤
1. 下载 APK 并安装
2. 打开应用，授予必要权限
3. 添加转发通道（企业微信/钉钉/飞书/Webhook）
4. 配置关键词规则
5. 开启转发服务

---

## 📖 使用指南

### 1. 添加转发通道
进入「转发通道管理」，点击「添加通道」：
- **企业微信**：填写 Webhook 地址
- **钉钉**：填写 Webhook 地址
- **飞书**：填写 Webhook 地址
- **通用 Webhook**：填写 HTTP(S) 地址

### 2. 配置关键词规则
进入「关键词配置」：
- 输入关键词（留空表示转发全部）
- 选择目标转发通道
- 点击「添加配置」

### 3. 开启服务
- 打开「转发服务」开关
- （可选）开启「开机自启动」
- 建议在系统设置中允许自启动和后台保活

---

## 🛠️ 开发者指南

### 项目结构
```
app/src/main/java/com/lanbing/smsforwarder/
├── MainActivity.kt              # Compose UI、规则测试、关于对话框
├── SmsReceiver.kt               # 短信接收、转发、去重、重试
├── SmsForegroundService.kt      # 前台服务与通知
├── BootReceiver.kt              # 开机启动处理
├── NetworkChangeReceiver.kt     # 网络状态监听
├── LogStore.kt                  # 日志存储
├── models.kt                    # 数据模型
└── Constants.kt                 # 常量定义
```

### 本地构建
```bash
# 克隆仓库
git clone https://github.com/jinghong-me/sms-forwarder.git
cd sms-forwarder

# 构建 Debug APK
./gradlew clean :app:assembleDebug

# 构建 Release APK（需要签名配置）
./gradlew clean :app:assembleRelease
```

### 技术栈
- **语言**：Kotlin
- **UI**：Jetpack Compose + Material Design 3
- **网络**：OkHttp
- **最低支持**：Android 8.0 (API 26)
- **目标版本**：Android 14 (API 34)

---

## 🔧 常见问题

### Q: 服务经常被系统杀死怎么办？
A: 请在系统设置中：
1. 允许应用自启动
2. 关闭电池优化
3. 锁定后台进程

### Q: 如何查看运行日志？
A: 在应用内「日志」标签页查看，或通过 adb 拉取：
```bash
adb shell run-as com.lanbing.smsforwarder cat files/sms_forwarder_logs.txt
```

### Q: 转发失败怎么办？
A: 检查：
1. Webhook 地址是否正确
2. 网络连接是否正常
3. 应用内日志查看详细错误

---

## 📄 许可证

本项目采用 [MIT 许可证](LICENSE.txt)。

---

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

提交 PR 时请说明：
- 变更目的与实现要点
- 隐私/合规影响（若处理敏感数据）
- 测试设备/Android 版本

---

## 📝 变更记录

| 版本 | 发布日期 | 说明 |
|------|----------|------|
| **v2.5.2** | 2026-04-04 | 调整页面内容分配，首页包含服务开关和开机启动，设置页包含SIM卡和消息格式 |
| v2.5.1 | 2026-04-04 | 修复自定义 SIM 号码显示逻辑问题，避免重复出现 SIM 卡 |
| v2.5.0 | 2026-04-04 | 新增底部导航栏，将功能分为首页、关键词、通道、设置、日志5个标签页 |
| v2.4.1 | 2026-04-04 | 优化自定义 SIM 号码功能，添加更清晰的使用提示 |
| v2.4.0 | 2026-04-04 | 新增自定义 SIM 号码功能，无法自动获取时支持手动输入 |
| v2.3.0 | 2026-04-04 | 新增消息格式配置选项、SIM 卡信息预览 |
| v2.2.0 | 2026-04-04 | 新增本机号码识别，双卡设备支持显示接收短信号码 |
| v2.1.3 | 2026-04-04 | 修复验证码识别错误，优化提取逻辑 |
| v2.1.2 | 2026-04-04 | 调整验证码显示顺序，优化消息格式 |
| v2.1.0 | 2026-04-04 | 新增验证码自动提取与突出显示功能 |
| v2.0.2 | - | 统一所有渠道消息前缀格式 |
| v2.0.1 | - | 多关键词、多通道支持 |
| v1.1 | - | 初始版本 |

---

## 📞 联系方式

- **GitHub Issues**：[提交问题](https://github.com/jinghong-me/sms-forwarder/issues)
- **问题反馈请附上**：
  - 设备型号与系统版本
  - 完整的 adb logcat
  - 复现步骤

---

<div align="center">

如果这个项目对你有帮助，欢迎给个 ⭐ Star！

</div>
