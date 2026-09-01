# AceEconomy v2 服务器运维

[English](operations.md) · 简体中文 · [繁體中文](operations.zh-TW.md)

这份清单用来帮你启动服务器、安全地修改配置、确认存储、建立或还原备份，以及在出问题时先保住数据，而不是先删数据。

## 目录

- [每日开服检查](#每日开服检查)
- [选择与确认存储](#选择与确认存储)
- [安全地修改配置](#安全地修改配置)
- [日常指令](#日常指令)
- [备份与还原](#备份与还原)
- [整合功能](#整合功能)
- [停服、重启与重新开放](#停服重启与重新开放)
- [紧急回退](#紧急回退)
- [把问题交给支持人员](#把问题交给支持人员)

## 每日开服检查

正常启动或重启之后，先在服务器控制台确认出现了 `AceEconomy v2.1.0` 和 `AceLib v1.2.0`，并且只加载了一个 AceLib 版本。接着用一个测试账号执行 `/money balance`，必要时再执行 `/baltop top`。

如果服务器还没准备好，不要对玩家开放。保留最早出现的那条 AceEconomy、AceLib 或存储错误，然后按照[故障排除](troubleshooting.zh-CN.md)处理。

## 选择与确认存储

v2 后端由 `storage.type` 决定：

| 值 | 位置 | 适合情形 |
| --- | --- | --- |
| `json` | `plugins/AceEconomy/data-v2.json` | 单台服务器，使用本地文件 |
| `sqlite` | 插件数据目录下的 `storage.sqlite.path` | 单台服务器，使用 SQLite 数据库 |
| `mysql` | `storage.mysql.*` | 使用受管理的 MySQL 或 MariaDB 服务 |

SQLite 的路径必须留在 `plugins/AceEconomy/` 之内。MySQL 的 `pool-size` 和 `max-lifetime` 必须放在 `storage.mysql` 之下，密码不要写进共享文档。

所有受支持的后端都使用同一套 v2 账户与交易模型。v1 的数据文件不是 v2 备份，不能拿来互相替换。

## 安全地修改配置

只有在你平时的变更流程能保护文件不被写坏时，才在服务器运行中编辑 `plugins/AceEconomy/config.yml`。修改前先复制一份，保留 `version: "2.0"`，并在套用前确认 YAML 结构正确。

普通的配置或语言变更，请从控制台执行：

```text
/aceeco reload
```

成功时会回显 `AceEconomy reloaded`。如果新的配置或语言文件加载不了，插件会保留最后一份有效的内存快照，不会用半成品配置顶替。但如果你改动了 `storage.type`、SQLite 路径、MySQL 连接值、插件 JAR、AceLib 或可选插件，就必须完整停服再启动，光靠 reload 不够。

不要把 Bukkit 的 `/reload` 当成维护或升级的捷径。

## 日常指令

检查运行中的服务器时，可以使用这些格式：

```text
/money balance [player] [currency]
/pay send <player> <amount> [currency]
/withdraw cash <amount> [currency]
/baltop top [currency]
/bank open
/aceeco give <player> <amount> [currency]
/aceeco take <player> <amount> [currency]
/aceeco set <player> <amount> [currency]
```

请用一个测试账号做小额、可回退的检查，不要直接改动真实玩家的余额。管理员调整余额时，请记到服务器平时的管理记录里。

## 备份与还原

受管的逻辑快照和手动的灾难恢复副本是两套不同的流程，不要把其中一种的说明当成另一种来用。

### 手动文件与数据库灾难恢复

直接复制 JSON 或 SQLite 数据文件之前，必须先停服。手动副本要放在正式插件目录之外，文件名带上日期和用途，也不要覆盖唯一一份确认正常的副本。这属于文件级的灾难恢复副本，不是受管的 `/aceeco backup` 指令。

MySQL 或 MariaDB 的原生／物理备份，请按数据库管理员平时的备份流程来做，并和对应的服务器配置备份一起保存。原生或物理的数据库备份不等于受管的 v2 逻辑快照。目前本文还没有验证过真实的 MySQL/Folia 组合与灾难恢复流程；在正式依赖之前，请先在一次受控演练中确认可行。

### 受管指令

控制台和获得授权的管理员可以建立一份受管逻辑快照：

```text
/aceeco backup [label]
```

这条指令在服务器运行中就能执行。它会在插件控制的 `<plugin data folder>/backups/` 目录下，写出一份不含任何凭证的 v2 JSON 逻辑快照。写入时，系统会先以「不存在才建立」的方式生成 `<backup-id>.json`，把完整内容写进去，再以同样方式生成 `<backup-id>.ready`。这个 `.ready` 文件里含有 SHA-256 校验和，是整份快照「正式生效」的标记；还原时必须同时具备这个标记，以及与之完全吻合、通过校验的 JSON 快照。已经存在的快照或标记文件名绝不会被覆盖。标签（label）只能使用字母、数字、`.`、`_` 和 `-`。快照包含账户、余额、交易（含已撤销标记）和已用过的一次性序号，但绝不包含存储密码或 webhook 网址。对 MySQL 来说，它是透过实时连接读取数据；这仍然是一份逻辑快照，不是 `mysqldump`／`mariabackup` 那种原生或物理备份。

要把快照正式发布出来，文件系统需要支持安全的目录写入机制、不跟随符号链接的属性检查、常规文件检查，以及强制写入的档案通道。这个机制是应用层的生效标记协议，并不依赖系统底层的原子重命名或硬链接。如果文件系统不支持，或者标记／快照写到一半失败，指令会安全地失败、拒绝写入，而不会退而求其次用不安全的方式写。搬移快照时，请把配对的 `.json` 和 `.ready` 一起搬；只有 `.json` 不算一份已提交的备份。如果留下一个没有标记的残档，还原时也会被拒绝。

还原是破坏性操作，并且有严格的门槛：

```text
/aceeco restore <backup-id> confirm
```

- 仅限控制台执行；需要 `aceeconomy.admin` 加上 `aceeconomy.admin.restore`，而且要逐字输入 `confirm`。
- 只要还有任何玩家在线就会被拒绝；请先请所有人离开。
- 在动到正式数据之前，它会先校验快照，并为当前状态建立一份安全备份。如果这份安全备份失败，什么都不会还原。
- 成功后会回报旧状态的安全备份 ID，并清空排行榜缓存。让玩家回来之前必须重启；已经打开的会话和界面不会热刷新。

没有独立的 `/backup` 或 `/restore` 根指令；这些操作只作为 `/aceeco` 的管理子指令存在。

受管还原并不要求你在执行指令前另外手动停服或复制文件，但仍建议安排在维护时段进行。在线玩家门槛、执行前检查和还原前的安全备份都在保护这次操作；即使快照损坏或版本不兼容，也绝不能作为删除正式数据的理由。

## 整合功能

Vault 和 PlaceholderAPI 都是可选插件。少了其中任何一个，经济核心依然能跑。Vault 使用配置里设定的默认货币；PlaceholderAPI 使用 `aceeco` 这个命名空间：

```text
%aceeco_balance%
%aceeco_balance_formatted%
%aceeco_balance_<currency>%
%aceeco_balance_<currency>_formatted%
```

Discord 通过 `discord.enabled` 和 `discord.webhook-url` 配置。真正的 webhook 只放在服务器本机。通知是异步、尽力而为的：通知出了问题，要和已经完成的交易分开处理。

## 停服、重启与重新开放

请使用平常的服务管理方式，或服务器控制台指令：

```text
stop
```

请等待世界和插件的保存都完成。重启之后，先重做一遍每日开服检查，再让玩家回来。如果这次重启是因为上一次 reload 失败，请先还原最后一份确认正常的配置，免得服务器反复用同一份错误配置启动。

## 紧急回退

要从 v2 退回 v1，请按照[从 v1 升级](upgrade-from-v1.zh-CN.md)的说明做。在还原升级前的 v1 安装之前，先保留当前 v2 的数据副本。绝对不要让 v1 去读取 `data-v2.json`、`data-v2.sqlite` 或任何 v2 快照。

如果要撤销单笔已经记录的交易，请从控制台执行：

```text
/aceeco rollback <transaction-id>
```

这条指令仅限控制台，需要 `aceeconomy.admin` 加上 `aceeconomy.admin.rollback`，并且会明确回报每一种结果：成功时会列出撤销审计记录的 ID；已经撤销过的交易是安全的无操作；如果标记持久化失败，代表效果可能已经发生，却没有留下持久记录。重试之前，请先检查存储并人工核对。完整的结果对照表见[指令与权限](commands.zh-CN.md)。

回退指令已经接进正式的指令介面，也有自动化契约测试覆盖，但真实的线上验证还没做完：Folia/Bukkit 衔接层的实际执行、真实的 MySQL 存储，以及用真实数据做的故障注入演练都还没跑。在这道发布门槛合上之前，请只在事先备好份的受控演练中使用它。

## 把问题交给支持人员

请使用[故障排除](troubleshooting.zh-CN.md)，并提供：最早出现的相关错误、版本号、当前存储类型、已经去掉敏感值的完整指令，以及事件发生的时间。数据文件、备份、密码、权杖和 webhook 网址都要保密。
