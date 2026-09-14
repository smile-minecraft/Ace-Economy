# AceEconomy v2 安装操作手册

[English](admin-install-runbook.md) · 简体中文 · [繁體中文](admin-install-runbook.zh-TW.md)

这本手册写给第一次安装 AceEconomy v2 的服务器管理员，从放好 JAR 一路带到第一次上线前的检查。

## 目录

- [需要准备的环境](#需要准备的环境)
- [在维护时段安装](#在维护时段安装)
- [首次启动不正常时](#首次启动不正常时)
- [接下来阅读](#接下来阅读)

## 需要准备的环境

服务器要用 Java 25，跑 Paper 或 Folia 26.1.2。AceEconomy 一定要配 `AceLib-1.2.1.jar` 才能启动。Vault 和 PlaceholderAPI 是可选的，有装才有对应功能，没装也能开服。SQLite 和 MySQL 需要的 JDBC 驱动已经包在 `AceEconomy-2.1.0.jar` 里面，不用自己再找驱动。

Paper/Folia 26.1.2 是正式支持的版本。Folia 26.2 只有特定 build 通过验证（VERIFIED-BETA），其余 26.2 build 还没验证过。

先准备好这两个文件：

```text
plugins/AceLib-1.2.1.jar
plugins/AceEconomy-2.1.0.jar
```

`plugins/` 里面不要留 `AceLib-0.5.0-SNAPSHOT.jar` 或其他 AceLib。两个 AceLib 同时存在，服务器会分不清用哪一个，可能开不干净。

### AceLib v1.2.1 下载与校验和

`AceLib-1.2.1.jar` 请从 AceLib v1.2.1 的 GitHub Release 下载：<https://github.com/smile-minecraft/AceLib/releases/tag/v1.2.1>。

放进 `plugins/` 之前，先对一次官方公布的 SHA-256：

```text
2da9d21e6a81eb3086aac3dcf87ad11f6dbcbfef5d3c80263270b3f074dc1d6d  AceLib-1.2.1.jar
```

在本机算出 digest，一个字一个字比：

```text
shasum -a 256 AceLib-1.2.1.jar   # macOS
sha256sum AceLib-1.2.1.jar       # Linux
```

对不上就不要装这个 JAR。宁可重下，也不要拿来路不明的文件开服。

## 在维护时段安装

下面七步要在玩家不在的时候做。先停服，装完、测完再开门。

### 1. 停服并备份

先用平时停服的方式把 Minecraft 服务器停下来。控制台指令是：

```text
stop
```

等进程完全退出、世界存档写完再动手。复制插件文件之前，先把服务器数据备份起来，至少要含完整的 `plugins/AceEconomy/` 文件夹。备份放在正式服务器目录外面，文件名标上日期。

全新安装还没有这个文件夹很正常。重点是开服前手上有一份能还原的备份。

### 2. 检查依赖插件

把正式 `plugins/` 里旧的或重复的 AceLib 移走。如果那是旧安装留下来的，就让它留在备份里。接着把 `AceLib-1.2.1.jar` 和 `AceEconomy-2.1.0.jar` 放进 `plugins/`。

有用联动功能，才把 Vault 或 PlaceholderAPI 放进同一个 `plugins/` 目录。没装这两个，AceEconomy 照样能启动，不要当成安装失败。

### 3. 首次启动并创建 v2 文件

照平时方式启动服务器。第一次成功启动后，AceEconomy 会在 `plugins/AceEconomy/` 建好 v2 的配置和语言文件。用默认 JSON 存储的话，还会看到：

```text
plugins/AceEconomy/config.yml
plugins/AceEconomy/lang/en_US.yml
plugins/AceEconomy/lang/zh_TW.yml
plugins/AceEconomy/lang/zh_CN.yml
plugins/AceEconomy/data-v2.json
```

想用 SQLite 的话，要在建数据库的那次启动前先把 `storage.type: sqlite` 写好。默认文件是 `plugins/AceEconomy/data-v2.sqlite`。

### 4. 配置存储方式与服务器行为

停服状态下打开 `plugins/AceEconomy/config.yml`。这份必须是含 `version: "2.0"` 的 v2 配置。v2 认得的存储写法只有下面几种。

JSON 是默认值，不用填连接信息：

```yaml
storage:
  type: json
```

SQLite 的文件一定要放在插件文件夹里面：

```yaml
storage:
  type: sqlite
  sqlite:
    path: data-v2.sqlite
```

用 MySQL 或 MariaDB 的话，密码只写在服务器本机，启动前把占位文字换掉：

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

`pool-size` 和 `max-lifetime` 一定要放在 `storage.mysql` 下面。驱动已经在插件里面了，不要再丢 MySQL 或 SQLite 的驱动 JAR 进 `plugins/`。

装机时先顾好存储就好。`settings.locale`、`start-balance`、`currencies.*`、`economy.allow-negative-balance`、`economy.default-debt-limit` 和 `leaderboard.*` 可以晚点再调，意思都在配置指南里。密码和 webhook 网址不要写进会外流的文档。

### 5. 再次启动并查看控制台

存盘后重新启动服务器。在控制台找到含 `AceEconomy v2.1.0` 的启用消息，确认服务器有进到平时能接玩家的状态。同时看一下，启用的 AceLib 只有一个版本。

如果 AceEconomy 自己停用了，先不要放玩家进来。把第一个错误和附近 AceEconomy／AceLib 的控制台内容留下来，再按[故障排除指南](troubleshooting.zh-CN.md)往下查。

### 6. 执行管理员基本检查

能在控制台跑的指令就在控制台跑，只能玩家用的指令就开测试玩家跑。下面就是 v2 正式的指令样子，照着打：

```text
/money balance
/baltop top
/aceeco give <player> <amount> [currency]
/aceeco take <player> <amount> [currency]
/aceeco set <player> <amount> [currency]
/aceeco history [player] [currency] [page]
/aceeco reload
```

`/aceeco rollback <transaction-id>` 故意不放在上面的例行检查里。它会把一笔已记录的交易整个恢复，属于具有破坏性、只能在控制台执行的管理操作：要同时有 `aceeconomy.admin` 和 `aceeconomy.admin.rollback`、手上有有效的交易 UUID，还要有人点头或事先演练过才能打，不能拿来当自动化或随手测试。它会先挡掉玩家和格式错的 UUID；成功会回报 reversal 审计记录 ID，已经回滚过的交易算明确的空操作，标记写入失败就先停下来人工核对。

再用测试玩家跑：

```text
/pay send <player> <amount> [currency]
/withdraw cash <amount> [currency]
/bank open
```

`/aceeco reload` 请从控制台打，会重新加载配置和语言文件。成功会回 `AceEconomy reloaded`。换过插件 JAR、AceLib、存储后端或数据库连接信息，还是要整台重启，只做重新加载不够。

### 7. 开放玩家进入

启用消息、预期的存储文件或数据库连接、基本指令都正常了，才开门。公告之前，先拿测试玩家查一次余额，再做一笔小额转账。

开门之后，把带日期的安装前备份和 v2 配置备份留好。含密码或 webhook 网址的副本不要盖到共享位置。

## 首次启动不正常时

先按症状翻 [`troubleshooting.md`](troubleshooting.zh-CN.md)。动数据之前，先看这四件事：

- `AceLib-1.2.1.jar` 有放，而且没有旧版 AceLib 同时启用。
- `config.yml` 有 `version: "2.0"`，`storage.type` 是有效值。
- SQLite 路径还在 `plugins/AceEconomy/` 底下。
- MySQL 密码和 webhook 网址只写在本机，没贴到工单或公开文章。

第一次启动失败，不要直接删 `data-v2.json`、SQLite 文件或数据库。先复制留底；删数据是救灾时的决定，不是安装步骤。

## 接下来阅读

- [服务器维运](operations.zh-CN.md)：日常备份、重新加载、重启与集成管理。
- [AceEconomy v2.1.0 发布说明](release-v2.1.0.zh-CN.md)：版本要求与 v2 功能总览。
