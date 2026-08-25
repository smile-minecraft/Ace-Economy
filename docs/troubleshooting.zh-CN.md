# AceEconomy 故障排除

[English](troubleshooting.md) · 简体中文 · [繁體中文](troubleshooting.zh-TW.md)

请从服务器控制台或游戏里能看到的症状入手。在改动存储文件之前，先复制相关的配置和数据。所有占位符只在你本机替换；不要把密码或 Discord 的 webhook 贴进工单或公开消息。

## 目录

- [插件无法启用](#插件无法启用)
- [Java、Paper 或 Folia 版本不符](#java-paper-或-folia-版本不符)
- [存储文件缺失或打开了错误的后端](#存储文件缺失或打开了错误的后端)
- [SQLite 路径被拒绝](#sqlite-路径被拒绝)
- [MySQL 或 Hikari 连接失败](#mysql-或-hikari-连接失败)
- [Discord 没有收到通知](#discord-没有收到通知)
- [Vault 或 PlaceholderAPI 整合不可用](#vault-或-placeholderapi-整合不可用)
- [配置重新加载失败](#配置重新加载失败)
- [重新加载、重启或停服行为异常](#重新加载重启或停服行为异常)
- [余额或交易结果不对](#余额或交易结果不对)
- [仍然失败时要提供什么](#仍然失败时要提供什么)

## 插件无法启用

**可能原因：** 缺少 `AceLib`、装了错误版本的 AceLib，或者服务器没有使用要求的 Java/Paper/Folia 组合。

**先检查：**

- 确认 `plugins/AceLib-1.0.0.jar` 和 `plugins/AceEconomy-2.1.0.jar` 都存在。
- 从正式插件目录里移除 `AceLib-0.5.0-SNAPSHOT.jar` 以及其他重复的 AceLib JAR。
- 看控制台里最早出现的 AceLib 或 Java 错误，不要只盯着最后那条停用的消息。

**修正：** 使用 Java 25 配 Paper/Folia 26.1.2，安装一个 `AceLib-1.0.0.jar`，然后完整重启服务器。缺少硬依赖时，`/aceeco reload` 救不了你。

## Java、Paper 或 Folia 版本不符

**可能原因：** Java 主版本不对、服务器构建不受支持，或者 Paper/Folia 没有提供所需的 API。

**先检查：** 确认进程用的是 Java 25，服务器是 Paper/Folia 26.1.2。把能识别出 Java、服务器、AceLib 和 AceEconomy 的启动控制台内容留好。

**修正：** 修正服务所用的 Java 或服务器安装，然后重启。不要为了绕过错误而换回旧的插件 JAR。

## 存储文件缺失或打开了错误的后端

**可能原因：** `storage.type` 和正在找的文件对不上、服务器没有成功启动过，或者文件被放到了插件数据目录之外。

**先检查：** 打开 `plugins/AceEconomy/config.yml`，确认：

```yaml
storage:
  type: json       # json, sqlite, or mysql
```

JSON 用的是 `plugins/AceEconomy/data-v2.json`。SQLite 用的是 `storage.sqlite.path` 指定的文件，而且必须留在 `plugins/AceEconomy/` 之下。MySQL 不会在本地创建数据库文件。

**修正：** 修正 YAML 里的嵌套结构，重新启动服务器，再看第一条存储相关的消息。不要把 v1 的文件改名成 `data-v2.json`。

## SQLite 路径被拒绝

**可能原因：** `storage.sqlite` 被写成了单个值而不是映射，或者路径用 `../` 或绝对路径跑出了插件数据目录。

配置结构必须是：

```yaml
storage:
  type: sqlite
  sqlite:
    path: data-v2.sqlite
```

**修正：** 使用 `plugins/AceEconomy/` 之下的相对文件名或子目录，然后完整重启。改动路径之前，先把现有的 SQLite 文件留好。

## MySQL 或 Hikari 连接失败

**可能原因：** 主机、端口、数据库、用户、密码或数据库权限有误；数据库连不上；或者连接池参数放错了 YAML 区块。

```yaml
storage:
  type: mysql
  mysql:
    host: "<database-host>"
    port: 3306
    database: "<database-name>"
    username: "<database-user>"
    password: "<set-locally>"
    pool-size: 10
    max-lifetime: 1800000
```

请按数据库管理员平时的流程确认数据库服务和凭证。`pool-size` 和 `max-lifetime` 必须放在 `storage.mysql` 之下；JDBC 驱动已经在插件 JAR 里了。

**修正：** 把值改对，密码只在本机填写，然后重启。如果还是失败，不要删 v2 数据；提供去掉敏感值后的连接结构，以及第一条数据库错误。

## Discord 没有收到通知

**可能原因：** `discord.enabled` 是 false、webhook 网址为空或无效，或者 Discord 拒绝了请求。

```yaml
discord:
  enabled: true
  webhook-url: "<discord-webhook-url>"
```

请在本机确认网址，并查看服务器附近有没有 Discord 的消息。支持请求里绝对不要包含真实的网址。

**修正：** 把这两个键改对，然后执行 `/aceeco reload`；如果插件或整合组合变了，再重启。Discord 的通知是异步、尽力而为的。通知失败不会撤销已经完成的交易，所以要分别去确认玩家余额和交易结果。

## Vault 或 PlaceholderAPI 整合不可用

**可能原因：** 可选插件没装、被禁用，或者 AceEconomy 启动时它还没准备好。

**先检查：** 确认可选插件本身已经启用，然后重启服务器让 AceEconomy 跟着重启。Vault 使用配置里设定的默认货币。PlaceholderAPI 使用 `aceeco` 这个命名空间，包括 `%aceeco_balance%`、`%aceeco_balance_formatted%`、`%aceeco_balance_<currency>%` 和 `%aceeco_balance_<currency>_formatted%`。

**修正：** 安装或启用对应的可选插件，然后重启。如果经济核心指令正常、只是整合失效，就保持核心服务开着，单独去排查那个可选插件。

## 配置重新加载失败

**可能原因：** YAML 无效、v2 的键结构错误、值不合法，或者语言文件加载不了。

**先检查：** 查看 `plugins/AceEconomy/config.yml` 最近一次改了什么，以及 `plugins/AceEconomy/lang/` 里选中的文件。确认配置仍然含有 `version: "2.0"`，并且用到的 `storage.sqlite` 和 `storage.mysql` 都是映射。

**修正：** 还原到最后一份确认正常的版本，再执行 `/aceeco reload`。重新加载失败时会保留最后一份有效的内存配置，不要以为那份改到一半的文件已经生效。只有文件有效之后，或者改动的是启动期的存储／依赖设置时，才需要完整重启。

## 重新加载、重启或停服行为异常

先分清楚这三种操作：

- `/aceeco reload` 重新加载配置和语言文件。
- 完整重启会重新打开存储后端，并重新加载插件依赖。
- `stop` 执行正常的停服；请等保存完成。

**修正：** 改动了 JAR、AceLib、`storage.type`、数据库连接值或可选插件的可用状态之后，请完整重启。不要用 Bukkit 的 `/reload` 来做正式升级或恢复。

## 余额或交易结果不对

**可能原因：** 指令用了不同的货币、目标玩家选错，或者服务器打开的 v2 后端和预期不同。

**先检查：** 记下不含密码的完整指令、货币 ID、玩家 UUID 或名称、当前的 `storage.type`，以及操作时间。再用 `/money balance <player> <currency>` 查一次，并翻看交易前后的服务器日志。

**修正：** 在确认后端和货币之前，先停掉其他余额变动。只有在停服时，才能从确认正常的 v2 备份还原。不要把 v1 文件载入 v2，也不要在正式存储上反复尝试没把握的修复。

## 仍然失败时要提供什么

请提供一份已经去掉敏感值的简短报告：

1. AceEconomy、AceLib、Java 和 Paper/Folia 的版本。
2. 症状，以及开始发生的确切时间。
3. 相关指令；按需替换掉玩家名称和敏感值。
4. 当前的 `storage.type` 和相关键名，但不要给密码、权杖或 webhook 网址。
5. 第一条 AceEconomy/AceLib/存储错误，以及它前面紧邻的控制台内容。

请保留原始的数据和配置备份。在有人审过报告之前，不要为了「清理」服务器而删掉数据文件。
