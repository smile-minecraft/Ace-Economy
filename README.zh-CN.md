# AceEconomy

[English](README.md) · 简体中文 · [繁體中文](README.zh-TW.md)

AceEconomy 为 Paper 和 Folia 服务器提供游戏内经济系统。玩家可以查询余额、互相付款、提取银行钞票、打开银行面板，并在排行榜上比较名次。服务器管理员可以选择存储后端、定义货币、接入 Vault 或 PlaceholderAPI，并将交易通知发送到 Discord。

## 文档索引

### 开始使用

| 文档 | 适合在以下情况下阅读 |
| --- | --- |
| [玩家指南](docs/player-guide.zh-CN.md) | 查询余额、向玩家付款、使用银行钞票或打开银行面板 |
| [管理员安装手册](docs/admin-install-runbook.zh-CN.md) | 安装 AceEconomy v2 并完成服务器首次检查 |

### 日常使用

| 文档 | 适合在以下情况下阅读 |
| --- | --- |
| [指令与权限](docs/commands.zh-CN.md) | 查询指令语法、权限、执行者和别名 |
| [配置指南](docs/config.zh-CN.md) | 配置存储、货币、语言、经济规则和 Discord |

### 维运与升级

| 文档 | 适合在以下情况下阅读 |
| --- | --- |
| [服务器维运](docs/operations.zh-CN.md) | 执行日常检查、安全修改设置、备份数据或恢复服务器 |
| [持久化、备份与恢复](docs/persistence.zh-CN.md) | 选择存储方式，或了解备份与恢复行为 |
| [从 AceEconomy v1 升级](docs/upgrade-from-v1.zh-CN.md) | 将 v1 安装替换为 v2，或规划回退 |
| [故障排除](docs/troubleshooting.zh-CN.md) | 排查启动、存储、整合或指令问题 |

### 整合与开发

| 文档 | 适合在以下情况下阅读 |
| --- | --- |
| [整合功能](docs/integrations.zh-CN.md) | 接入 AceLib、Vault、PlaceholderAPI 或 Discord |
| [整合 API](docs/integration-api.zh-CN.md) | 使用 Vault 或 PlaceholderAPI 开发插件整合 |
| [本地化](docs/localization.zh-CN.md) | 修改或维护服务器语言文件 |

### 发布与技术参考

| 文档 | 适合在以下情况下阅读 |
| --- | --- |
| [AceEconomy v2.1.0 发布说明](docs/release-v2.1.0.zh-CN.md) | 查看 v2.1.0 的内容和验证边界 |
| [AceEconomy v2.0.0 发布说明](docs/release-v2.0.0.zh-CN.md) | 查看 v2.0.0 的内容和升级说明 |
| [数据库概念与升级](docs/database.zh-CN.md) | 了解 v2 数据模型和升级路径 |
| [v2 功能基线矩阵](docs/v2-capability-matrix.zh-CN.md) | 查看 v2 保留的 v1 功能基线 |
| [v2.0.0 切换说明](docs/cutover.zh-CN.md) | 了解 v2 runtime、依赖、安装和回退 |

## 目录

- [运行要求](#运行要求)
- [快速开始](#快速开始)
- [主要功能](#主要功能)
- [玩家指令](#玩家指令)
- [获取帮助](#获取帮助)

## 运行要求

| 要求 | 版本或说明 |
| --- | --- |
| Java | `25` |
| 服务器 | Paper/Folia API `26.1.2 build 74` |
| 必需插件 | `AceLib v1.2.0` |
| 可选插件 | Vault、PlaceholderAPI |

Paper/Folia 26.1.2 是正式支持的服务器线。Folia 26.2 仅在特定 build 上通过验证（VERIFIED-BETA），其余 26.2 build 未验证。

## 快速开始

1. 安装插件前先停止服务器。
2. 将 `AceLib-1.2.0.jar` 和 `AceEconomy-2.1.0.jar` 放入服务器的 `plugins` 文件夹。请从 <https://github.com/smile-minecraft/AceLib/releases/tag/v1.2.0> 下载 `AceLib-1.2.0.jar`，并在放入前核对其 SHA-256（`da9f196b47c2b28c6db443d102236b27c1a1bbdf7dd3e7c22470170420935278`）；具体命令见[管理员安装手册](docs/admin-install-runbook.zh-CN.md)。
3. 如果需要这些整合功能，再安装 Vault 或 PlaceholderAPI。
4. 启动服务器。AceEconomy 首次启动时会创建默认配置和存储。
5. 根据需要调整 `config.yml` 中的存储、语言、货币和整合设置。[配置指南](docs/config.zh-CN.md) 解释了各项设置。
6. 使用 `/money balance` 查询余额，再使用 `/bank open` 打开银行面板。

要升级已有安装，请先阅读[升级指南](docs/upgrade-from-v1.zh-CN.md)，再按照[管理员安装手册](docs/admin-install-runbook.zh-CN.md)完成部署。

## 主要功能

- **玩家经济：** 查询余额、向其他玩家付款，或提取银行钞票。
- **银行面板：** 打开供玩家使用的账户和提取操作菜单。
- **排行榜：** 查看指定货币的富豪玩家。
- **多种货币：** 使用配置的默认货币，或在指令中指定其他货币。
- **灵活存储：** 默认使用 JSON，也支持 SQLite 和 MySQL。
- **整合功能：** 可选支持 Vault、PlaceholderAPI 和 Discord。
- **本地化：** 内置 `en_US`、`zh_TW` 和 `zh_CN` 语言。

## 玩家指令

| 指令 | 用途 |
| --- | --- |
| `/money balance [player] [currency]` | 查看余额 |
| `/pay send <player> <amount> [currency]` | 向其他玩家付款 |
| `/withdraw cash <amount> [currency]` | 提取实体银行钞票 |
| `/baltop top [currency]` | 查看余额排行榜 |
| `/bank open` | 打开银行面板 |

参数规则、权限、管理员指令和完整参考请查看[指令与权限](docs/commands.zh-CN.md)。想按情境操作时，可以从[玩家指南](docs/player-guide.zh-CN.md)开始。

## 获取帮助

先阅读与当前任务相符的指南。如果问题仍未解决，请在 [AceEconomy repository](https://github.com/SmileX-AI/AceEconomy/issues) 提交 Issue，并附上插件版本、服务器软件、相关指令或设置，以及你看到的消息。发布前请删除密码、Token 和 Webhook URL。

**AceEconomy** © 2024–2026 Developed by Smile
