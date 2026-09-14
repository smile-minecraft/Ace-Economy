# AceEconomy v2.2.0 发布说明

[English](release-v2.2.0.md) · 简体中文 · [繁體中文](release-v2.2.0.zh-TW.md)

AceEconomy v2.2.0 是 v2 服务器线的正式发布版本。这个版本新增本地化语言文件、Discord 通知、插件自有的 SQL 连接池与断线恢复、SQL 排行榜快照与账户读取缓存、Vault 名称型 API、Essentials/CMI 导入指令、基岩版消息降级与原生银行表单、PlaceholderAPI 占位符、银行票据兑换、可配置的银行 GUI，以及纯显示的货币 reload。基线是 Java 25 与 Paper/Folia 26.1.2，这是正式支持的服务器线；Folia 26.2 只在特定 build 上通过验证（VERIFIED-BETA）。`AceLib-1.2.1.jar` 是必需依赖，插件 artifact 为 `AceEconomy-2.2.0.jar`。本版本已发布为 GitHub Release `v2.2.0`：<https://github.com/smile-minecraft/Ace-Economy/releases/tag/v2.2.0>。

这份文档写给要决定是否安装或升级的服务器管理员和维护者：说明这个版本包含什么、怎么验证下载的文件，以及发布前验证覆盖了哪些范围。

## 目录

- [现在能不能安装](#现在能不能安装)
- [基线与发布状态](#基线与发布状态)
- [验证发布文件](#验证发布文件)
- [v2.2.0 新增了什么](#v220-新增了什么)
- [实现了什么、背后有什么证据](#实现了什么背后有什么证据)
- [Folia 实机重测](#folia-实机重测)
- [发布验证](#发布验证)
- [安装或升级前](#安装或升级前)
- [明确不宣称的事](#明确不宣称的事)

## 现在能不能安装

可以。v2.2.0 是已发布的正式版本：

- 从 GitHub Release `v2.2.0` 下载 `AceEconomy-2.2.0.jar`：<https://github.com/smile-minecraft/Ace-Economy/releases/tag/v2.2.0>。同一个 Release 也附带 slim、sources、javadoc 三种 JAR 和 `SHA256SUMS`。
- `plugins/` 里只留一份 `AceLib-1.2.1.jar` 和插件放在一起。AceLib 是启动的必要条件。
- 首次安装或升级照[管理员安装手册](admin-install-runbook.zh-CN.md) 进行，并按[验证发布文件](#验证发布文件) 用已发布的 `SHA256SUMS` 核对下载的 JAR。

## 基线与发布状态

| 项目 | v2.2.0 值 |
| --- | --- |
| Java | 25 |
| 服务器 | Paper/Folia 26.1.2（正式支持线）；Folia 26.2 只在特定 build 上通过验证（VERIFIED-BETA） |
| 必需依赖 | `AceLib v1.2.1` |
| repo 构建版本 | `2.2.0` |
| 插件 artifact | `AceEconomy-2.2.0.jar` |
| GitHub Release / tag | `v2.2.0`（已发布） |
| 配置 schema | `version: "2.0"` |
| 发布日期 | 2026-09-14 |

基线与已发布的 v2.1.0 一致。v2.2.0 的变动在插件功能面，不在 Java 或服务器基线。

## 验证发布文件

v2.2.0 已发布为 GitHub Release `v2.2.0`。Publish Release workflow 会附带 full、slim、sources、javadoc 四种 JAR，以及 `SHA256SUMS` asset。请以 Release 上的这份 `SHA256SUMS` 为准：把它放在下载的文件旁边，验证不带路径的文件名条目：

```text
sha256sum -c SHA256SUMS
```

macOS 上可以这样算本地摘要：

```text
shasum -a 256 AceEconomy-2.2.0.jar
```

把第一列与 `SHA256SUMS` 里 `AceEconomy-2.2.0.jar` 的条目比对后，再把插件放到正式服务器。根目录的 `SHA256SUMS` 是本地构建记录，不是发布附件，不要拿它来验证；也不要拿旧版本复制来的值代替这次比对。

## v2.2.0 新增了什么

以下范围是这个版本覆盖的内容。每一项都写出它依赖的设置或指令，方便你对照参考文档。

- **本地化消息** — `lang/<locale>.yml` 提供 `en_US`、`zh_TW`、`zh_CN` 三种语言，变量写成 `{placeholder}`，呈现用 MiniMessage，用户输入会被转义。
- **Discord 通知** — 交易成立后以异步、尽力而为的方式发送 embed，由 `discord.enabled` 与 `discord.webhook-url` 配置。发送失败不会改动已经成立的交易结果。
- **SQL 连接池与断线恢复** — `storage.mysql.pool-size` 与 `storage.mysql.max-lifetime` 交给插件自有的严格连接池。清理步骤失败的借用连接会被丢弃而不是再次借出；空闲连接过期或已关闭时，下一次借用会换成新的。
- **SQL 排行榜查询与账户读取缓存** — `/baltop` 读取快照（`leaderboard.cache-time-seconds`），`AccountBalanceCache` 让 Vault 的同步读取不碰存储；缓存未命中时返回安全默认值 `0.0`，不会阻塞。
- **Vault 名称型 API** — `hasAccount(String)`、`getBalance(String)`、`has`、`depositPlayer`、`withdrawPlayer`、`createPlayerAccount` 会用在线玩家与缓存的离线记录解析名称，匹配不区分大小写。UUID 仍是账户键，玩家改名后账户不变；调用线程上不做任何存储或网络 I/O。
- **Essentials/CMI 导入** — 仅限控制台的 `/aceeco import <essentials|cmi> <path> [currency] [apply confirm]`。缺少精确的 `apply confirm` 组合时只做预演；来源限定在插件受控的 `import/` 目录；执行前会做安全备份；重跑时跳过已套用的记录。
- **基岩版消息降级与原生银行表单** — 装了 Floodgate 时，聊天消息的点击动作会换成 `message.bedrock.fallback.*` 的提示文字，`/bank open` 也改送原生表单而不是箱子菜单。两种界面共用同一条存款／取款路径。
- **PlaceholderAPI 占位符** — `aceeco` 命名空间，包含原始与格式化余额、`rank`、`top_name`、`top_balance`，以及 `currency_name`／`currency_symbol`。排名与排行榜数值读取的是与 `/baltop` 相同的快照，不会触发数据库查询。
- **银行票据兑换** — 拿着有效票据点右键，会走与界面按钮相同的原子入账路径。重放会被拒绝；票据无效或入账失败时物品留在玩家身上。
- **可配置银行 GUI** — `bank-gui` 区段设置标题键、界面大小与各槽位动作。重新加载成功时会先关闭已打开的界面，不会有半旧半新的规则被点到。
- **货币 reload** — `/aceeco reload` 会即时套用纯显示变更（`name`、`symbol`）；新增、移除货币或改动 `scale`／`default` 会被拒绝并附上原因，需要重启。

指令与权限细节见[指令与权限](commands.zh-CN.md)；设置见[配置指南](config.zh-CN.md)；整合行为见[整合功能](integrations.zh-CN.md) 与[整合 API](integration-api.zh-CN.md)。

## 实现了什么、背后有什么证据

这张表把两件容易混在一起的事分开：已经发布的代码，以及它背后的证据。覆盖实机检查的发布前验证记在[发布验证](#发布验证)。

| 范围 | 已实现 | 证据 |
| --- | --- | --- |
| 本地化消息 | 三份语言文件、`{placeholder}` 变量、MiniMessage、用户输入转义 | 资源合约与语言覆盖测试 |
| Discord 通知 | 异步尽力而为的 embed；长度上限与机密遮蔽 | notifier、payload 与 transport 测试 |
| SQL 连接池与断线恢复 | 插件自有严格连接池；不安全的借用丢弃、过期连接替换 | 连接池、生命周期与 SQL 后端测试；本次 Folia 重测在受控 MySQL 8.4 服务上完成连接、写入、读取与重启持久化 |
| SQL 排行榜与读取缓存 | `/baltop` 快照；供 Vault 读取的 `AccountBalanceCache` | 排行榜与余额缓存测试 |
| Vault 名称型 API | 名称方法用在线与离线缓存记录解析 | Vault provider 与名称查询测试 |
| Essentials/CMI 导入 | 仅限控制台、路径闸门、导入前备份、可重复执行 | parser、路径闸门、TOCTOU 与有界读取测试 |
| 基岩版降级与银行表单 | 点击动作降级；原生表单共用存提路径 | 基岩降级与表单路由测试 |
| PlaceholderAPI 占位符 | `aceeco` 命名空间，含 `rank` 与 `top_*` | resolver 与 expansion 测试 |
| 银行票据兑换 | 右键兑换走原子入账路径 | 兑换与票据 schema 测试 |
| 可配置银行 GUI | `bank-gui` 的标题、大小与动作；reload 时关闭旧界面 | 配置 parser 与 GUI session 测试；本次 Folia 重测在真实 Java 客户端完成呈现，以及 `DEPOSIT`、`WITHDRAW`、`CLOSE`、重新打开与无票据的存款路径 |
| 货币 reload | 纯显示变更即时套用；结构变更被拒绝并附原因 | reload 计划与货币交易测试 |
| 权限与发送者规则 | 每个指令都声明发送者与权限政策 | 指令合约测试；本次 Folia 重测观察到控制台限定的发送者拒绝 |

自动化测试与源码静态检查不等于实机验证；单靠这些覆盖不了的实机检查，记在[发布验证](#发布验证)。

## Folia 实机重测

我们在测试服对 v2.2.0 的 artifact 做了一轮有界的 Folia 实机重测。服务器是 Folia `26.2-7`、Java 25，`storage.type` 设为 `mysql`，连到受控的 MySQL `8.4` 服务。一名 Java 玩家在没有其他玩家的服务器上连接，跑完了下列检查；收尾时用控制台 `/aceeco set` 把测试余额校回起始值，并关闭界面。

这轮重测覆盖 Java 银行界面、权限与发送者规则，以及 MySQL 的连接／写入／读取／重启持久化。

**本次重测通过**

- 插件启用 — 控制台启用日志显示 `AceEconomy 2.2.0` 与 `AceLib 1.2.1` 都已加载并启用，PlaceholderAPI 也把 `aceeco` 扩展注册为 `2.2.0`。
- 余额显示 — `/money` 与 `/baltop top` 显示出这名玩家的余额与排名。
- 银行票据往返 — `/withdraw cash 100` 生成一张 v2 票据，右键使用后兑换成功。聊天确认兑换完成，余额回到提款前的金额。
- 银行界面呈现 — `/bank open` 打开 Java 的 `Generic_9x3` 银行菜单，`DEPOSIT`（slot `4`）、`WITHDRAW`（slot `11`、`13`）与 `CLOSE`（slot `15`）按钮都连同 lore 一起显示出来。
- 银行界面取款 — 点 `WITHDRAW` 成功生成一张新的票据。
- 银行界面存款 — 先 `/withdraw cash 100`，再打开 `/bank open`；读到当次 active inventory id 后点 `DEPOSIT`（slot `4`），关闭界面，`/money` 显示临时测试余额已回到提款前的值。
- 银行界面关闭与重新打开 — 以当次 active inventory id 点 `CLOSE`（slot `15`）后界面关闭，inventories list 已不再列出它，之后再 `/bank open` 成功重新打开。
- 手上没有票据时存款 — 手上没有票据时重新打开 `/bank open`，点 `DEPOSIT`（slot `4`）；关闭后 `/money` 仍是同一个临时测试余额，证明无票据路径没有入账。
- 仅限控制台的发送者拒绝 — 玩家执行 `/aceeco reload` 得到 `subcommand is console-only: reload`。
- MySQL 写入／读取／重启持久化 — 服务器以 `storage.type: mysql` 连到本机受控的 MySQL `8.4` 服务，该服务回应 `SELECT 1` 探测。控制台 `/aceeco set` 写入一笔临时测试余额，`/money` 读回；随后做一次非破坏性重启，重连后读回同一个值。收尾时已把测试余额校回起始值。

这轮重测是 v2.2.0 的有界运行时证据；其余发布前检查的结果记在[发布验证](#发布验证)。

## 发布验证

v2.2.0 的发布关卡都已关闭。发布前，维护者完成了自动化证据无法覆盖的发布前验证：

- 真人玩家权限与发送者矩阵。
- 真人基岩客户端：原生表单、降级提示、取消与重新打开。
- MySQL/MariaDB 剩余项目：并行 transfer、逻辑快照／还原、断线恢复，以及 recovery 与故障注入。
- v2.1.0 沿用的检查：JSON 跨进程竞争、物理／原生数据库备份，以及真实数据还原。

v2.2.0 也在 Folia `26.2-7`、Java 25 与 AceLib 1.2.1 上通过有界实机重测；各项检查与观察到的结果记在[Folia 实机重测](#folia-实机重测)。

## 安装或升级前

1. 安装已发布的 v2.2.0，流程照[管理员安装手册](admin-install-runbook.zh-CN.md)。
2. 维持 Java 25 与 Paper/Folia 26.1.2 基线，`plugins/` 里只留一份 `AceLib-1.2.1.jar`。
3. `config.yml` 维持 `version: "2.0"`。改动货币结构或只在启动时读取的设置，仍需重启而不是 reload。
4. 任何变更前先备份插件数据目录或数据库，先在副本上验证。

## 明确不宣称的事

- 这里不提供任何测试数量或覆盖率数字。
- Essentials/CMI 导入不是自动迁移，仍是一道明确的、仅限控制台的指令。
- 逻辑备份指令不能代替 `mysqldump`、`mariadb-dump` 或数据库管理员的物理恢复流程。
- 不支持 Vault 银行方法，Vault 一律使用配置的默认货币。
- 没有分世界余额；world-name 参数会被接受但忽略。

安装、日常运维与恢复请用[管理员安装手册](admin-install-runbook.zh-CN.md)、[服务器维运](operations.zh-CN.md) 与[持久化、备份与恢复](persistence.zh-CN.md)。收集证据或回报问题时，密码、token、webhook URL、数据文件和备份都要保密。
