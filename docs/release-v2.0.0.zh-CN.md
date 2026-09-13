# AceEconomy v2.0.0 发布说明

[English](release-v2.0.0.md) · 简体中文 · [繁體中文](release-v2.0.0.zh-TW.md)

AceEconomy v2.0.0 是给 Java 25 和 Paper/Folia 26.1.2 用的 v2 服务器版本。它需要 `AceLib-1.0.0.jar`，发布的插件文件是 `AceEconomy-2.0.0.jar`。

> **历史发布说明。** 本页的版本号描述的是 v2.0.0 发布当时的状态。如果要全新安装，请使用当前版本 [AceEconomy v2.1.0](release-v2.1.0.zh-CN.md)，它需要 `AceLib-1.2.0.jar`。

这份文档写给要安装或更换服务器插件的管理员：先看这版带来什么，再照着放文件、确认配置与数据位置，最后在上线前验证文件。

## 目录

- [这版包含什么](#这版包含什么)
- [需要放哪些文件](#需要放哪些文件)
- [配置与数据在哪里](#配置与数据在哪里)
- [指令长什么样](#指令长什么样)
- [升级与回退怎么做](#升级与回退怎么做)
- [上线前先验证文件](#上线前先验证文件)

首次安装请先读 [`admin-install-runbook.zh-CN.md`](admin-install-runbook.zh-CN.md)。从 v1 更换请用 [`upgrade-from-v1.zh-CN.md`](upgrade-from-v1.zh-CN.md)，日常维护见 [`operations.zh-CN.md`](operations.zh-CN.md)。

## 这版包含什么

- 给 v2 数据用的 JSON、SQLite 和 MySQL/MariaDB 存储。
- 多货币、起始余额、债务限制、转账、管理员余额调整、交易记录、银行票据、银行菜单和余额排行榜。
- 可选的 Vault 与 PlaceholderAPI 整合。
- 用本地 webhook 设置做的可选 Discord 交易通知。
- English、繁体中文和简体中文语言文件。

## 需要放哪些文件

请把以下文件放进 `plugins/`：

```text
AceLib-1.0.0.jar
AceEconomy-2.0.0.jar
```

AceLib 是必要的依赖插件。Vault 和 PlaceholderAPI 是可选插件，启用后才会被检测到。SQLite 和 MySQL JDBC drivers 已经包在 AceEconomy JAR 里，不需要另外放 driver 文件。

不要让 `AceLib-0.5.0-SNAPSHOT.jar` 或其他 AceLib 版本跟 v2 放在一起。

## 配置与数据在哪里

启用中的配置是 `plugins/AceEconomy/config.yml`，里面包含 `version: "2.0"`。JSON 是默认后端，数据放在 `plugins/AceEconomy/data-v2.json`。SQLite 用插件数据目录下 `storage.sqlite.path` 指定的文件。MySQL/MariaDB 用 `storage.mysql.*` 区块。

v1 的配置与数据不会自动迁移。不得只把 v1 文件改名成 v2 文件。如果之后可能回退，请先留一份完整的升级前备份。

上面的安装与运维指南有服务器管理员需要的配置与备份步骤。密码与 webhook URL 只留在本地；公开示例一律用占位符。

## 指令长什么样

v2 用以下明确格式：

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
| `/aceeco reload` | 从控制台重新加载配置和语言文件 |

换过插件 JAR、AceLib、存储后端、数据库连接或可选插件组合之后，`/aceeco reload` 不能代替重启。

## 升级与回退怎么做

先停服，把完整的 v1 安装备份下来，再放进 v2 的 JAR 组合并创建 v2 配置。不要让 v2 指向 v1 的存储。真要回退时，先停掉 v2，另外留一份 v2 数据副本，再从带日期的备份还原升级前的 v1 JAR、配置和数据。

完整流程见 [`upgrade-from-v1.zh-CN.md`](upgrade-from-v1.zh-CN.md)。不要把 `data-v2.json`、`data-v2.sqlite` 或 v2 快照复制到 v1 的数据位置。

## 上线前先验证文件

发布有提供 `SHA256SUMS` asset 时，请把它放在 `AceEconomy-2.0.0.jar` 旁边，验证不带路径的文件名条目：

```text
sha256sum -c SHA256SUMS
```

macOS 可用以下命令计算本地的值：

```text
shasum -a 256 AceEconomy-2.0.0.jar
```

把第一列跟 `SHA256SUMS` 里 `AceEconomy-2.0.0.jar` 那一行比对，确认无误后再把文件放到正式服务器。
