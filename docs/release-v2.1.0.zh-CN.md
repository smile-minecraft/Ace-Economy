# AceEconomy v2.1.0 发布说明

[English](release-v2.1.0.md) · 简体中文 · [繁體中文](release-v2.1.0.zh-TW.md)

AceEconomy v2.1.0 扩展了 v2 服务器功能，加入维运历史与 rollback 指令、管理式 logical backup 与 restore、可配置货币与指令转送、银行票据与银行 GUI 操作，以及 JSON/SQLite 持久化路径。发布基线是 Java 25 与 Paper/Folia 26.1.2，这是正式支持的服务器线；Folia 26.2 仅在特定 build 上通过验证（VERIFIED-BETA），其余 26.2 build 未验证。`AceLib-1.2.0.jar` 是必要的 runtime dependency，预期插件 artifact 为 `AceEconomy-2.1.0.jar`。

本文供发布操作员和维护者使用，说明已实现的指令与 persistence surface，以及目前可取得的有界 runtime evidence。不宣称尚未完成的真实玩家、正式数据库、客户端 GUI、跨进程或恢复 gate 已通过。

## 目录

- [发布基线](#发布基线)
- [本版本包含什么](#本版本包含什么)
  - [维运、历史与 rollback](#维运历史与-rollback)
  - [备份与恢复](#备份与恢复)
  - [动态货币与配置](#动态货币与配置)
  - [银行票据、GUI 操作与指令转送](#银行票据gui-操作与指令转送)
  - [持久化](#持久化)
- [安装、升级与回退](#安装升级与回退)
  - [全新安装](#全新安装)
  - [从 v1 更换](#从-v1-更换)
  - [发布回退](#发布回退)
- [验证发布文件](#验证发布文件)
- [有界 Folia runtime evidence](#有界-folia-runtime-evidence)
- [尚未完成的验证 gate](#尚未完成的验证-gate)
- [明确非目标](#明确非目标)

安装和日常维运请使用 [`admin-install-runbook.zh-CN.md`](admin-install-runbook.zh-CN.md)、[`operations.zh-CN.md`](operations.zh-CN.md) 和 [`troubleshooting.zh-CN.md`](troubleshooting.zh-CN.md)。从 v1 更换请使用 [`upgrade-from-v1.zh-CN.md`](upgrade-from-v1.zh-CN.md)，详细指令与持久化参考见 [`commands.zh-CN.md`](commands.zh-CN.md) 和 [`persistence.zh-CN.md`](persistence.zh-CN.md)。

## 发布基线

| 项目 | v2.1.0 值 |
| --- | --- |
| Java | 25 |
| Paper/Folia | 26.1.2 |
| 必要依赖 | `AceLib-1.2.0.jar` |
| 插件 artifact | `AceEconomy-2.1.0.jar`（预期文件名） |
| AceLib config schema | `version: "2.0"` |

config schema 仍为 `2.0`；本版本没有引入 `version: "2.1"`。`plugins/` 中只保留一个兼容的 AceLib JAR。请从 <https://github.com/smile-minecraft/AceLib/releases/tag/v1.2.0> 下载 `AceLib-1.2.0.jar`，并在安装前核对其 SHA-256 `da9f196b47c2b28c6db443d102236b27c1a1bbdf7dd3e7c22470170420935278`；具体命令见 [`admin-install-runbook.zh-CN.md`](admin-install-runbook.zh-CN.md)。Vault 和 PlaceholderAPI 仍是可选整合，文件所述存储路径使用的 JDBC drivers 由插件 artifact 提供。

## 本版本包含什么

### 维运、历史与 rollback

- `/aceeco history [player] [currency] [page]` 提供只读、按最新到最旧排列的交易历史。页码从 `0` 开始，文档定义每页 `10` 笔。
- `/aceeco rollback <transaction-id>` 从主控台回滚一笔已记录交易。需要 `aceeconomy.admin` 与 `aceeconomy.admin.rollback`，会在查询前验证交易 UUID，并报告成功、already-reverted、typed failure 和 marker-persistence 结果。
- 已回滚的交易是安全 no-op。如果 marker persistence 失败，效果可能已经发生但没有持久化记录；重试前先检查存储并人工核对。

完整指令与权限表见 [`commands.zh-CN.md`](commands.zh-CN.md)。rollback 路径已实现并有自动化 contract tests 覆盖，但 live Folia/Bukkit bridge、live database 路径和真实数据故障演练仍是开放 gate，见[尚未完成的验证 gate](#尚未完成的验证-gate)。

| 指令 | 执行者 | 权限 |
| --- | --- | --- |
| `/aceeco history [player] [currency] [page]` | 玩家或主控台 | `aceeconomy.admin` + `aceeconomy.admin.history` |
| `/aceeco reload` | 仅主控台 | `aceeconomy.admin` + `aceeconomy.admin.reload` |
| `/aceeco rollback <transaction-id>` | 仅主控台 | `aceeconomy.admin` + `aceeconomy.admin.rollback` |
| `/aceeco backup [label]` | 玩家或主控台 | `aceeconomy.admin` + `aceeconomy.admin.backup` |
| `/aceeco restore <backup-id> confirm` | 仅主控台，且不可有在线玩家 | `aceeconomy.admin` + `aceeconomy.admin.restore` |
| `/withdraw cash <amount> [currency]` | 仅玩家 | `aceeconomy.command.withdraw` |
| `/bank open` | 仅玩家 | `aceeconomy.command.bank` |

这些是声明的指令政策，不是 live sender 或 permission-denial 证据；后者仍是开放验证 gate。

### 备份与恢复

使用 canonical 管理式指令：

```text
/aceeco backup [label]
/aceeco restore <backup-id> confirm
```

`backup` 会在 `<plugin data folder>/backups` 下写出 v2 logical JSON snapshot，保留 snapshot 与配对的 `.ready` marker，不替换既有目标。snapshot 包含逻辑账户、余额、交易、reverted markers 和已消耗 nonces，不包含数据库密码或 webhook URL。

`restore` 是破坏性操作。它只能由主控台执行，需要 `aceeconomy.admin` 与 `aceeconomy.admin.restore`，会拒绝在线玩家，并且只接受小写 `confirm`。它会先执行 preflight 检查，再创建 safety backup，之后才修改 live state。成功恢复后，玩家回来前必须重启服务器，因为 session 和 GUI 不会热刷新。

这些是应用程序层 logical snapshots，不能取代 `mysqldump`、`mariadb-dump` 或数据库管理员的 physical/disaster-recovery 流程，也没有独立的 `/backup` 或 `/restore` 根指令。

### 动态货币与配置

`currencies.*` map 由管理员定义。每种货币要提供 ID、显示名称、符号和 scale，并且必须恰好有一项默认货币。货币 ID 会规范大小写和前后空白；无效、重复、空白或格式错误的货币配置会阻止 partial startup。

`/aceeco reload` 会重新载入配置和语言文件；reload 失败时保留最后一份有效的内存配置。它不会重新注册指令，也不会重建只在启动时建立的 currency 和 alias registries。修改 plugin JAR、AceLib、storage backend 或连接设置、货币，或主指令 alias 后请重启。

### 银行票据、GUI 操作与指令转送

`/withdraw cash <amount> [currency]` 会创建 v2 银行票据。`/bank open` 会打开银行介面。文档定义的 GUI action contract 包含 slot `4` 的 `DEPOSIT`、slots `11` 和 `13` 的 `WITHDRAW`，以及 slot `15` 的 `CLOSE`。有效银行票据会在移除物品或减少 stack 前完成入账并防止 replay；无效、重播或入账失败时物品会留在玩家物品栏。

command registry 会把 `plugin.yml` 声明的 aliases 转送到 canonical roots：`/balance` 与 `/bal` 转送到 `/money`，`/balancetop` 与 `/top` 转送到 `/baltop`，`/menu` 与 `/bankmenu` 转送到 `/bank`。`settings.main-command-alias` 配置额外的管理员 root alias，默认是 `aceeco`。Alias 只在启动时生效，与其他 declared command label 冲突时会拒绝启动。不包含右键兑换银行票据。

### 持久化

文档中的 v2 backend 包含 JSON、SQLite，以及用于 MySQL/MariaDB 的 MySQL-compatible 配置。JSON 使用 `data-v2.json`；SQLite 使用插件数据目录内配置的路径；MySQL/MariaDB 使用 `storage.type: mysql` 与 `storage.mysql.*`。JSON 和 SQLite persistence paths 已自动覆盖 schema、restart、snapshot 和 transaction boundaries；当前 release evidence 不把这些覆盖等同于 live MySQL/MariaDB 或 JSON 跨进程已获批准。

## 安装、升级与回退

### 全新安装

1. 停止服务器，在 live server directory 外建立有日期且可恢复的副本；若已有 `plugins/AceEconomy/`，请完整纳入。
2. 将 `AceLib-1.2.0.jar` 和预期的 `AceEconomy-2.1.0.jar` 放入 `plugins/`，不要让其他 AceLib 版本并存。
3. 先启动一次创建 v2 文件，再确认启用中的 `plugins/AceEconomy/config.yml` 包含 `version: "2.0"`。
4. 选择 JSON、SQLite 或配置好的 MySQL-compatible backend。数据库密码和 webhook URL 只保留在本地。
5. 再次启动，检查 enable messages 并执行适用的 operator checks。完整流程见 [`admin-install-runbook.zh-CN.md`](admin-install-runbook.zh-CN.md)。

### 从 v1 更换

v2 是 clean-slate 安装，不会自动迁移 v1 配置或数据。不得把 v1 文件改名为 `data-v2.json`，也不得将其载入 v2 backend。保留完整的 pre-cutover v1 安装作为回退来源；请遵循 [`upgrade-from-v1.zh-CN.md`](upgrade-from-v1.zh-CN.md)，不要把 v1 文件复制到 v2。

### 发布回退

从 v2.1.0 回到 v1 时，停止 v2，另外保留当前 v2 数据副本，将 v2 JAR 移出 `plugins/`，再恢复有日期的 v1 JAR、配置和数据。启动 v1 并确认数据可读后，才能让玩家回来。绝不要让 v1 读取 `data-v2.json`、`data-v2.sqlite` 或 v2 snapshot。

## 验证发布文件

v2.1.0 已作为 GitHub Release `v2.1.0`（发布 commit `2bb86c4`）发布。Publish Release workflow 会附带 full、slim、sources、javadoc 四种 JAR，以及 `SHA256SUMS` asset。请以这个已发布的 `SHA256SUMS` 为准：把它放在下载的 artifact 旁边，并验证裸文件名项目：

```text
sha256sum -c SHA256SUMS
```

macOS 使用以下命令计算本机 digest：

```text
shasum -a 256 AceEconomy-2.1.0.jar
```

将第一列与 `SHA256SUMS` 中 `AceEconomy-2.1.0.jar` 项目比较后，再把插件放到正式服务器。不要用旧版本复制的值取代此比较。

## 有界 Folia runtime evidence

目前的有界证据同时覆盖了 Folia `26.1.2-8` 与 Folia `26.2-4`。两次都使用同一份 v2.0.0 artifact，范围包括启动与 plugin enable、AceLib capability、status/health、RCON route/help 与 typed errors、声明的 aliases、backup/restore confirmation 与 safety-backup paths，以及 reload/restart 行为。

这是有界 runtime evidence，不是 production certification。不代表真实玩家经济操作、live MySQL/MariaDB 行为、GUI render 或 click、JSON 多进程安全、实体数据库备份恢复或故障注入恢复已成功。

## 尚未完成的验证 gate

以下项目明确属于 not-run 或仍开放。未来发布或 operator acceptance record 必须提供相应 live evidence，才能把受影响路径视为 production-ready。

- **Player sender 与 permission denial**——尚未执行。验证玩家／主控台限定 sender 拒绝、缺少 root permission 和缺少 child permission。
- **GUI render 与 click**——尚未执行。使用真实客户端打开 bank GUI，验证画面及 `DEPOSIT`、`WITHDRAW`、`CLOSE` clicks。
- **Live MySQL/MariaDB**——尚未执行。连接目标服务，验证启动、写入、读取、重启和 logical snapshot 路径。
- **JSON cross-process race**——尚未执行。执行多进程竞争测试；同进程 atomic file 行为不代表跨进程保证。
- **Physical/native backup**——插件尚未执行。SQL 正式维运需另行验证数据库管理员的 native backup/restore 流程；逻辑指令不能取代它。
- **Real-data recovery 与 fault injection**——尚未执行。使用代表性数据和受控故障，验证恢复、marker 处理和必要的人工核对路径。
- **Real-player history 与 rollback paths**——部分路径尚未执行。使用真实玩家账户演练 history 查询和受控 rollback，包括 transfer-counterpart 与 persistence-failure 情境。

## 明确非目标

- 不包含自动 v1 migration。
- 不包含 Essentials/CMI import。
- 不包含 native database dump replacement。
- 不包含右键兑换银行票据。
- 不包含独立的 `/backup` 与 `/restore` 根指令；请使用 `/aceeco` 子指令。

发布范围与维运边界也记录在 [`operations.zh-CN.md`](operations.zh-CN.md)、[`persistence.zh-CN.md`](persistence.zh-CN.md) 和 [`cutover.zh-CN.md`](cutover.zh-CN.md)。收集 evidence 或回报问题时，密码、token、webhook URL、数据文件和备份必须保密。
