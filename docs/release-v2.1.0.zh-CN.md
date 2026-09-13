# AceEconomy v2.1.0 发布说明

[English](release-v2.1.0.md) · 简体中文 · [繁體中文](release-v2.1.0.zh-TW.md)

v2.1.0 在 v2 服务器功能的基础上，增加了交易历史查询与回滚、管理式逻辑备份与还原、可自定义货币与指令转发、银行票据与银行界面操作，以及 JSON/SQLite 持久化路径。发布基线是 Java 25 与 Paper/Folia 26.1.2，这是正式支持的服务器线；Folia 26.2 只在特定 build 上通过验证（VERIFIED-BETA），其余 26.2 build 尚未验证。`AceLib-1.2.1.jar` 是必要的运行时依赖，预期的插件文件是 `AceEconomy-2.1.0.jar`。

这份文档写给要决定是否升级、如何安装与维护的服务器管理员：先确认环境合不合适，再看这版多了什么、怎么装，最后看哪些已经验证、哪些还没验证。没通过的验证关卡不会说成已经通过。

## 目录

- [这版跑在什么环境](#这版跑在什么环境)
- [这版多了什么](#这版多了什么)
  - [查历史与回滚交易](#查历史与回滚交易)
  - [备份与还原](#备份与还原)
  - [货币与配置](#货币与配置)
  - [银行票据、银行界面与指令转发](#银行票据银行界面与指令转发)
  - [数据存在哪里](#数据存在哪里)
- [安装、升级与回退](#安装升级与回退)
  - [全新安装](#全新安装)
  - [从 v1 更换](#从-v1-更换)
  - [发布回退](#发布回退)
- [上线前先验证文件](#上线前先验证文件)
- [已经验证到哪里](#已经验证到哪里)
- [还没验证什么](#还没验证什么)
- [明确不做的事](#明确不做的事)

安装和日常运维请用 [`admin-install-runbook.zh-CN.md`](admin-install-runbook.zh-CN.md)、[`operations.zh-CN.md`](operations.zh-CN.md) 和 [`troubleshooting.zh-CN.md`](troubleshooting.zh-CN.md)。从 v1 更换请用 [`upgrade-from-v1.zh-CN.md`](upgrade-from-v1.zh-CN.md)，详细指令与持久化参考见 [`commands.zh-CN.md`](commands.zh-CN.md) 和 [`persistence.zh-CN.md`](persistence.zh-CN.md)。

## 这版跑在什么环境

| 项目 | v2.1.0 值 |
| --- | --- |
| Java | 25 |
| Paper/Folia | 26.1.2 |
| 必要依赖 | `AceLib-1.2.1.jar` |
| 插件 artifact | `AceEconomy-2.1.0.jar`（预期文件名） |
| AceLib config schema | `version: "2.0"` |

配置 schema 维持 `2.0`；这版没有引入 `version: "2.1"`。`plugins/` 里只留一个兼容的 AceLib JAR。请从 <https://github.com/smile-minecraft/AceLib/releases/tag/v1.2.1> 下载 `AceLib-1.2.1.jar`，安装前先核对它的 SHA-256 `2da9d21e6a81eb3086aac3dcf87ad11f6dbcbfef5d3c80263270b3f074dc1d6d`；具体命令见 [`admin-install-runbook.zh-CN.md`](admin-install-runbook.zh-CN.md)。Vault 和 PlaceholderAPI 还是可选整合，文档里存储路径用到的 JDBC drivers 由插件文件提供。

## 这版多了什么

### 查历史与回滚交易

- `/aceeco history [player] [currency] [page]` 查只读的交易历史，按最新到最旧排列。页码从 `0` 开始，文档定义每页 `10` 笔。
- `/aceeco rollback <transaction-id>` 从控制台回滚一笔已记录的交易。它需要 `aceeconomy.admin` 与 `aceeconomy.admin.rollback`，查询前会先验证交易 UUID，并报告成功（附这笔回滚的审计记录 ID）、`already-reverted`（这笔已经回滚过）、`typed failure`（有明确类型的失败报告），以及回滚标记有没有写进存储。
- 已经回滚过的交易再提交一次是安全无效果的（`no-op`）。如果标记没写进存储，效果可能已经发生却没有留下记录；重试前请先检查存储并人工核对。

完整指令与权限表见 [`commands.zh-CN.md`](commands.zh-CN.md)。回滚路径已经实现，也有自动化合约测试覆盖，但实机 Folia/Bukkit 桥接、正式数据库路径和真实数据故障演练都还没验证，见[还没验证什么](#还没验证什么)。

主要指令政策如下：

| 指令 | 执行者 | 权限 |
| --- | --- | --- |
| `/aceeco history [player] [currency] [page]` | 玩家或控制台 | `aceeconomy.admin` + `aceeconomy.admin.history` |
| `/aceeco reload` | 仅控制台 | `aceeconomy.admin` + `aceeconomy.admin.reload` |
| `/aceeco rollback <transaction-id>` | 仅控制台 | `aceeconomy.admin` + `aceeconomy.admin.rollback` |
| `/aceeco backup [label]` | 玩家或控制台 | `aceeconomy.admin` + `aceeconomy.admin.backup` |
| `/aceeco restore <backup-id> confirm` | 仅控制台，且不可有在线玩家 | `aceeconomy.admin` + `aceeconomy.admin.restore` |
| `/withdraw cash <amount> [currency]` | 仅玩家 | `aceeconomy.command.withdraw` |
| `/bank open` | 仅玩家 | `aceeconomy.command.bank` |

这些只是声明出来的指令政策，还不是在实机上验证过的发送者或权限拒绝证据；后者仍是尚未完成的验证关卡。

### 备份与还原

请用标准的管理式指令做逻辑备份与还原：

```text
/aceeco backup [label]
/aceeco restore <backup-id> confirm
```

`backup` 会在 `<plugin data folder>/backups` 下写出 v2 逻辑 JSON 快照，快照与配对的 `.ready` 就绪标记一起保存，而且不会盖掉已有的目标。快照内容是逻辑账户、余额、交易、已回滚标记与已消耗的一次性序号，不含数据库密码或 webhook URL。

`restore` 是破坏性操作，只能由控制台执行，需要 `aceeconomy.admin` 与 `aceeconomy.admin.restore`，有玩家在线会拒绝，而且只接受小写 `confirm`。它会先做事前检查，再建一份安全备份，之后才动到正式状态。还原成功后，玩家回来前必须重启服务器，因为会话与界面不会热刷新。

这些是应用层的逻辑快照，不能代替 `mysqldump`、`mariadb-dump` 或数据库管理员的物理／灾难恢复流程，也没有独立的 `/backup` 或 `/restore` 根指令。

### 货币与配置

货币清单 `currencies.*` 由管理员定义。每种货币要给 ID、显示名称、符号与小数位数，而且必须恰好有一种默认货币。货币 ID 会统一大小写并去掉前后空白；无效、重复、空白或格式错误的货币配置会让插件停在启动阶段，不会以不完整状态上线。

`/aceeco reload` 会重新加载配置和语言文件；重新加载失败时保留最后一份有效的内存配置。它不会重新注册指令，也不会重建只在启动时建立的货币与别名注册表。换过插件 JAR、AceLib、存储后端或连接配置、货币，或管理员主指令别名之后请重启。

### 银行票据、银行界面与指令转发

`/withdraw cash <amount> [currency]` 会创建一张 v2 银行票据。`/bank open` 会打开银行界面。文档定义的银行界面操作合约（GUI，约定各槽位点击行为）包含 slot `4` 的 `DEPOSIT`、slots `11` 和 `13` 的 `WITHDRAW`，以及 slot `15` 的 `CLOSE`。有效的银行票据会先入账并防止重播，之后才移除物品或减少堆叠；票据无效、重播或入账失败时，物品会留在玩家物品栏。

指令注册表会把 `plugin.yml` 声明的别名转发到标准主指令：`/balance` 与 `/bal` 转发到 `/money`，`/balancetop` 与 `/top` 转发到 `/baltop`，`/menu` 与 `/bankmenu` 转发到 `/bank`。`settings.main-command-alias` 配置额外的管理员主指令别名，默认为 `aceeco`。别名只在启动时生效，跟其他已声明指令标签冲突时会拒绝启动。不包含右键兑换银行票据。

### 数据存在哪里

文档里的 v2 后端包含 JSON、SQLite，以及给 MySQL/MariaDB 用的 MySQL 兼容配置。JSON 用 `data-v2.json`；SQLite 用插件数据目录内配置好的路径；MySQL/MariaDB 用 `storage.type: mysql` 与 `storage.mysql.*`。JSON 与 SQLite 的持久化路径已有自动化测试覆盖 schema、重启、快照与事务边界；目前的发布证据不等于正式 MySQL/MariaDB 或 JSON 跨进程已经获批。

## 安装、升级与回退

### 全新安装

1. 停服，在正式服务器目录外建一份带日期、可还原的副本；已有 `plugins/AceEconomy/` 的话请完整纳入。
2. 把 `AceLib-1.2.1.jar` 与预期的 `AceEconomy-2.1.0.jar` 放进 `plugins/`，不要让其他 AceLib 版本留在旁边。
3. 先启动一次创建 v2 文件，再确认启用中的 `plugins/AceEconomy/config.yml` 包含 `version: "2.0"`。
4. 选 JSON、SQLite 或配置好的 MySQL 兼容后端。数据库密码和 webhook URL 只留在本地。
5. 再次启动，检查启用消息并执行适用的管理员检查。完整流程见 [`admin-install-runbook.zh-CN.md`](admin-install-runbook.zh-CN.md)。

### 从 v1 更换

v2 是全新干净安装（不沿用旧数据），不会自动迁移 v1 配置或数据。不得把 v1 文件改名成 `data-v2.json`，也不能把它载入 v2 后端。请保留完整的切换前 v1 安装作为回退来源；照着 [`upgrade-from-v1.zh-CN.md`](upgrade-from-v1.zh-CN.md) 做，不要把 v1 文件复制到 v2。

### 发布回退

要从 v2.1.0 回到 v1 时，先停掉 v2，另外留一份当前 v2 数据的副本，把 v2 JAR 移出 `plugins/`，再从带日期的备份还原 v1 JAR、配置和数据。启动 v1 并确认数据可读之后，才能让玩家回来。绝对不要让 v1 去读 `data-v2.json`、`data-v2.sqlite` 或 v2 快照。

## 上线前先验证文件

v2.1.0 已经作为 GitHub Release `v2.1.0`（发布 commit `2bb86c4`）发布。Publish Release workflow 会附带 full、slim、sources、javadoc 四种 JAR，以及 `SHA256SUMS` asset。请以这份已发布的 `SHA256SUMS` 为准：把它放在下载的文件旁边，验证不带路径的文件名条目：

```text
sha256sum -c SHA256SUMS
```

macOS 可用以下命令计算本地的值：

```text
shasum -a 256 AceEconomy-2.1.0.jar
```

把第一列跟 `SHA256SUMS` 里 `AceEconomy-2.1.0.jar` 的条目比对后，再把插件放到正式服务器。不要拿旧版本复制来的值代替这次比对。

## 已经验证到哪里

目前的有界运行时证据同时覆盖 Folia `26.1.2-8` 与 Folia `26.2-4`。两次都用同一份 v2.0.0 artifact，范围包括启动与插件启用、AceLib capability、状态与健康检查、RCON 路由与说明及有明确类型的错误、已声明的别名、备份与还原的确认流程及安全备份路径，以及重新加载与重启行为。

这是有界的运行时证据，不是正式上线认证。它不代表真实玩家经济操作、正式 MySQL/MariaDB 行为、界面渲染或点击、JSON 多进程安全、物理数据库备份还原或故障注入还原已经成功。

## 还没验证什么

以下项目明确属于未执行或仍开放。未来发布或管理员验收记录必须补上对应的实机证据，才能把受影响的路径视为可上正式环境。

- **玩家发送者与权限拒绝**——尚未执行。验证玩家限定／控制台限定的发送者拒绝、缺少主权限与缺少子权限。
- **界面渲染与点击**——尚未执行。用真实客户端打开银行界面，验证画面与 `DEPOSIT`、`WITHDRAW`、`CLOSE` 点击。
- **正式 MySQL/MariaDB**——尚未执行。连到目标服务，验证启动、写入、读取、重启与逻辑快照路径。
- **JSON 跨进程竞争**——尚未执行。执行多进程竞争测试；同进程的原子写文件行为不代表跨进程保证。
- **物理／原生备份**——插件尚未执行。SQL 正式运维要另外验证数据库管理员的原生备份与还原流程；逻辑指令不能代替它。
- **真实数据还原与故障注入**——尚未执行。用代表性数据与受控故障，验证还原、标记处理与必要的人工核对路径。
- **真实玩家历史与回滚路径**——部分路径尚未执行。用真实玩家账户演练历史查询与受控回滚，包含转账对应方（`transfer-counterpart`）与持久化失败（`persistence-failure`）场景。

## 明确不做的事

- 不包含自动 v1 迁移。
- 不包含 Essentials/CMI import。
- 不包含原生数据库转储替代方案。
- 不包含右键兑换银行票据。
- 不包含独立的 `/backup` 与 `/restore` 根指令；请用 `/aceeco` 子指令。

发布范围与运维边界也记在 [`operations.zh-CN.md`](operations.zh-CN.md)、[`persistence.zh-CN.md`](persistence.zh-CN.md) 和 [`cutover.zh-CN.md`](cutover.zh-CN.md)。收集证据或回报问题时，密码、token、webhook URL、数据文件和备份都要保密。
