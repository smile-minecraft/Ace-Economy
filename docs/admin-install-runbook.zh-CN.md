# AceEconomy v2 安装操作手册

[English](admin-install-runbook.md) · 简体中文 · [繁體中文](admin-install-runbook.zh-TW.md)

本手册给负责 Paper 或 Folia 服务器的管理员使用，目的是把 AceEconomy v2 安全地上线，不用猜文件放在哪里。全新安装请按本页操作；从 v1 更换时，请改看 [`upgrade-from-v1.md`](upgrade-from-v1.zh-CN.md)。

## 目录

- [需要准备的环境](#需要准备的环境)
- [在维护时段安装](#在维护时段安装)
- [首次启动不正常时](#首次启动不正常时)
- [接下来阅读](#接下来阅读)

## 需要准备的环境

服务器需要使用 Java 25，并运行 Paper 或 Folia 26.1.2。AceEconomy 必须搭配 `AceLib-1.0.0.jar`；Vault 和 PlaceholderAPI 都是可选集成。SQLite 与 MySQL 的 JDBC 驱动程式已经包含在 `AceEconomy-2.1.0.jar` 中，不需要另外下载驱动程式 JAR。

请先准备以下两个插件文件：

```text
plugins/AceLib-1.0.0.jar
plugins/AceEconomy-2.1.0.jar
```

`plugins/` 中不要留下 `AceLib-0.5.0-SNAPSHOT.jar` 或其他 AceLib 版本。两个 AceLib 版本同时存在，可能造成依赖判断不明确，使服务器无法正常启动。

## 在维护时段安装

### 1. 停服并备份

请使用平常的服务器控制台或服务管理方式停止 Minecraft 服务器。控制台指令是：

```text
stop
```

请等待进程退出、世界保存完成后再操作。复制插件文件前，先备份整个服务器数据，至少要包含完整的 `plugins/AceEconomy/` 文件夹。备份请放在正式服务器目录之外，并标上日期。

全新安装时这个文件夹可能还不存在，这没有问题。重点是正式启动前要有一份可以还原的服务器备份。

### 2. 检查依赖插件

请从正式 `plugins/` 目录移走旧版或重复的 AceLib；如果它们属于旧安装，仍要保留在备份中。然后在 `plugins/` 放入 `AceLib-1.0.0.jar` 与 `AceEconomy-2.1.0.jar`。

如果要使用集成功能，再把 Vault 和／或 PlaceholderAPI 放到同一个 `plugins/` 目录。缺少这些可选插件时 AceEconomy 仍然可以启动，不要把它们不存在当成安装失败。

### 3. 首次启动并创建 v2 文件

请按平常方式启动服务器。AceEconomy 首次成功启动后，会在 `plugins/AceEconomy/` 创建 v2 配置与语言文件。使用默认 JSON 存储时，还会创建：

```text
plugins/AceEconomy/config.yml
plugins/AceEconomy/lang/en_US.yml
plugins/AceEconomy/lang/zh_TW.yml
plugins/AceEconomy/lang/zh_CN.yml
plugins/AceEconomy/data-v2.json
```

使用 SQLite 时，请在需要创建数据库的那次启动前设置 `storage.type: sqlite`。默认文件是 `plugins/AceEconomy/data-v2.sqlite`。

### 4. 配置存储方式与服务器行为

请在停服时打开 `plugins/AceEconomy/config.yml`。文件必须是包含 `version: "2.0"` 的 v2 配置，不要把 v1 的 `config-version` 区块粘贴进去。以下是 v2 支持的存储配置格式。

JSON 是默认值，不需要连接信息：

```yaml
storage:
  type: json
```

SQLite 的数据库文件必须放在插件文件夹内：

```yaml
storage:
  type: sqlite
  sqlite:
    path: data-v2.sqlite
```

使用 MySQL 或 MariaDB 时，密码只放在服务器本机，启动前再替换預留位置：

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

`pool-size` 与 `max-lifetime` 必须放在 `storage.mysql` 下。插件本身已经提供 JDBC 驱动程式，不要再把 MySQL 或 SQLite 驱动程式放进 `plugins/`。

还可以设置 `settings.locale`、`start-balance`、`currencies.*`、`economy.allow-negative-balance`、`economy.default-debt-limit` 与 `leaderboard.*`。上面的示例只列出安装时需要的存储配置；密码与 webhook 网址不要放进共享文档。

### 5. 再次启动并查看控制台

保存配置后重新启动服务器。请在控制台找到包含 `AceEconomy v2.1.0` 的启用消息，并确认服务器能继续进入平常的可服务状态。同时确认启用的 AceLib 只有一个版本。

如果 AceEconomy 自行停用，先不要开放玩家进入。保留第一个错误以及附近的 AceEconomy／AceLib 控制台内容，再按照[故障排除指南](troubleshooting.zh-CN.md)继续检查。

### 6. 执行管理员基本检查

以下指令中，可以在控制台执行的请从控制台执行；限定玩家的指令请使用测试玩家执行。下面列出的完整子指令格式就是 v2 的正式指令集。

```text
/money balance
/baltop top
/aceeco give <player> <amount> [currency]
/aceeco take <player> <amount> [currency]
/aceeco set <player> <amount> [currency]
/aceeco history [player] [currency] [page]
/aceeco reload
```

`/aceeco rollback` 刻意不列入上面的例行检查清单。它是具有破坏性、仅限控制台的管理操作：必须同时拥有 `aceeconomy.admin` 与 `aceeconomy.admin.rollback`、持有有效的交易 UUID，并经过人工批准或专门演练才能执行；不得当成自动化或随手的初步检查。

`/aceeco rollback <transaction-id>` 也可以从控制台执行。它是具有破坏性的管理操作，会恢复一笔已记录的交易，因此不要拿来做例行安装检查，留给事故处理使用。它需要同时拥有 `aceeconomy.admin` 与 `aceeconomy.admin.rollback`，会预先拒绝玩家与无效 UUID；成功时回报 reversal 审计记录 ID，已回滚的交易视为明确的空操作，标记写入失败则要求先人工核对。

再使用测试玩家执行：

```text
/pay send <player> <amount> [currency]
/withdraw cash <amount> [currency]
/bank open
```

`/aceeco reload` 应由控制台执行，会重新加载配置与语言文件。成功时会回报 `AceEconomy reloaded`。修改插件 JAR、AceLib、存储后端或数据库连接信息后，仍然必须完整重启服务器。

### 7. 开放玩家进入

请在启用消息、预期的存储文件或数据库连接，以及基本指令都正常后，再开放服务器给玩家。正式公告前，先用一个测试玩家查询余额并完成一笔小额转账。

开放服务器后，请保留有日期的安装前备份与 v2 配置备份。不要把含有密码或 webhook 网址的副本覆盖到共享位置。

## 首次启动不正常时

请按症状查阅 [`troubleshooting.md`](troubleshooting.zh-CN.md)。修改数据前，先检查以下几点：

- `AceLib-1.0.0.jar` 已存在，而且没有旧版 AceLib JAR 同时启用。
- `config.yml` 含有 `version: "2.0"`，且 `storage.type` 是有效值。
- SQLite 路径仍在 `plugins/AceEconomy/` 下。
- MySQL 密码与 webhook 网址只在本机设置，没有贴到工单或公开文章。

首次启动失败时，不要直接删除 `data-v2.json`、SQLite 文件或数据库。先复制保留；删除数据是恢复决策，不是一般安装步骤。

## 接下来阅读

- [`upgrade-from-v1.md`](upgrade-from-v1.zh-CN.md)：更换 v1 安装并保留安全的回退路径。
- [`operations.md`](operations.zh-CN.md)：日常备份、重新加载、重启与集成管理。
- [`release-v2.1.0.md`](release-v2.1.0.zh-CN.md)：版本要求与 v2 功能总览。
