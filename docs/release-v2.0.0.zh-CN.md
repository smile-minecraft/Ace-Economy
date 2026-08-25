# AceEconomy v2.0.0 发布说明

[English](release-v2.0.0.md) · 简体中文 · [繁體中文](release-v2.0.0.zh-TW.md)

AceEconomy v2.0.0 是面向 Java 25 和 Paper/Folia 26.1.2 的 v2 服务器版本。它需要 `AceLib-1.0.0.jar`，发布插件文件为 `AceEconomy-2.0.0.jar`。

本页供安装或替换服务器插件时使用，说明发布内容、必须放置的文件、v2 数据落在哪里，以及安装前如何核对文件。

## 目录

- [本版本包含什么](#本版本包含什么)
- [文件与依赖](#文件与依赖)
- [配置与数据](#配置与数据)
- [指令](#指令)
- [升级与回退](#升级与回退)
- [验证发布文件](#验证发布文件)

首次安装请先阅读 [`admin-install-runbook.zh-CN.md`](admin-install-runbook.zh-CN.md)。从 v1 更换请使用 [`upgrade-from-v1.zh-CN.md`](upgrade-from-v1.zh-CN.md)，日常维护见 [`operations.zh-CN.md`](operations.zh-CN.md)。

## 本版本包含什么

- 用于 v2 数据的 JSON、SQLite 和 MySQL/MariaDB 存储。
- 多货币、起始余额、债务限制、转账、管理员余额调整、交易记录、银行票据、银行菜单和余额排行榜。
- 可选的 Vault 与 PlaceholderAPI 整合。
- 使用本地 webhook 设置的可选 Discord 交易通知。
- English、繁体中文和简体中文语言文件。

## 文件与依赖

请把以下文件放入 `plugins/`：

```text
AceLib-1.0.0.jar
AceEconomy-2.0.0.jar
```

AceLib 是必要依赖。Vault 和 PlaceholderAPI 是可选插件，启用后才会被检测。SQLite 和 MySQL JDBC drivers 已包含在 AceEconomy JAR 中，不需要额外的 driver 文件。

不要让 `AceLib-0.5.0-SNAPSHOT.jar` 或其他 AceLib 版本与 v2 并存。

## 配置与数据

当前配置为 `plugins/AceEconomy/config.yml`，其中 `version: "2.0"`。JSON 是默认 backend，使用 `plugins/AceEconomy/data-v2.json`。SQLite 使用插件数据目录下由 `storage.sqlite.path` 指定的文件。MySQL/MariaDB 使用 `storage.mysql.*` 区块。

v1 配置和数据不会自动 migration。不得只把 v1 文件改名为 v2 文件。如果可能需要回退，请保留完整的升级前备份。

上面的安装和维运指南包含服务器管理员需要的设置和备份步骤。密码和 webhook URL 只保留在本地；公开示例必须使用 placeholder。

## 指令

v2 使用以下明确格式：

| 指令 | 用途 |
| --- | --- |
| `/money balance [player] [currency]` | 查询余额 |
| `/pay send <player> <amount> [currency]` | 转账 |
| `/withdraw cash <amount> [currency]` | 创建银行票据 |
| `/baltop top [currency]` | 显示排行榜 |
| `/bank open` | 打开银行菜单 |
| `/aceeco give <player> <amount> [currency]` | 增加余额 |
| `/aceeco take <player> <amount> [currency]` | 扣除余额 |
| `/aceeco set <player> <amount> [currency]` | 设置余额 |
| `/aceeco reload` | 从主控台重新载入配置和语言文件 |

修改 plugin JAR、AceLib、storage backend、数据库连接或可选插件组合后，`/aceeco reload` 不能取代重启。

## 升级与回退

停止服务器，备份完整的 v1 安装，放入 v2 JAR 组合并创建 v2 配置。不要让 v2 指向 v1 存储。如果需要回退，停止 v2，另外保留 v2 数据副本，再从有日期的备份恢复升级前的 v1 JAR、配置和数据。

完整流程见 [`upgrade-from-v1.zh-CN.md`](upgrade-from-v1.zh-CN.md)。不要把 `data-v2.json`、`data-v2.sqlite` 或 v2 snapshot 复制到 v1 数据位置。

## 验证发布文件

发布提供 `SHA256SUMS` asset 时，将它放在 `AceEconomy-2.0.0.jar` 旁边，并验证裸文件名项目：

```text
sha256sum -c SHA256SUMS
```

在 macOS 上使用以下命令计算值：

```text
shasum -a 256 AceEconomy-2.0.0.jar
```

将第一列与 `SHA256SUMS` 中 `AceEconomy-2.0.0.jar` 那一行比较，确认后再把文件放到正式服务器。
