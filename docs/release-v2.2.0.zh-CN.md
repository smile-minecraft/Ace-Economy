# AceEconomy v2.2.0 发布准备稿

[English](release-v2.2.0.md) · 简体中文 · [繁體中文](release-v2.2.0.zh-TW.md)

> **发布准备稿，还不是正式版本。** 这一页写的是进行中的工作。目前 repo 的版本仍然是 `2.1.0`，也还没有 `v2.2.0` 的 artifact、checksum 或 release tag。请不要照这一页安装，也不要把它当成下载来源。

现在就要上正式环境的话，请安装已发布的 [AceEconomy v2.1.0](release-v2.1.0.zh-CN.md)。这份草稿写给服务器管理员和维护者：v2.2.0 目前的范围包含什么、背后有哪些证据、正式发布前还差哪些关卡。

## 目录

- [现在能不能用](#现在能不能用)
- [基线与发布状态](#基线与发布状态)
- [v2.2.0 新增了什么](#v220-新增了什么)
- [做到哪里、有什么证据、还卡在哪里](#做到哪里有什么证据还卡在哪里)
- [安装或升级前](#安装或升级前)
- [发布前必须完成的关卡](#发布前必须完成的关卡)
- [明确不宣称的事](#明确不宣称的事)

## 现在能不能用

还不行。这一页没有任何可以下载或直接构建的正式版本：

- `build.gradle.kts` 仍声明 `version = "2.1.0"`，当前构建产物是 `AceEconomy-2.1.0.jar`。
- 没有 `v2.2.0` 的 GitHub Release、artifact 或 `SHA256SUMS` 条目，也还没定发布日期。
- 权限、界面、真实 MySQL/MariaDB 与真人基岩版客户端的实机验收关卡都还没过。

在 v2.2.0 的发布说明取代这一页之前，请照 [v2.1.0 管理员安装手册](admin-install-runbook.zh-CN.md) 安装已发布的 v2.1.0。

## 基线与发布状态

| 项目 | 当前状态 |
| --- | --- |
| Java | 25 |
| 服务器 | Paper/Folia 26.1.2（正式支持线）；Folia 26.2 仅在特定 build 上通过验证（VERIFIED-BETA） |
| 必要依赖 | `AceLib v1.2.1` |
| repo 构建版本 | `2.1.0`（未改动） |
| 插件 artifact | 待正式发布前确认 |
| 配置 schema | `version: "2.0"` |
| 发布日期 | 待正式发布前确认 |

基线与已发布的 v2.1.0 一致。v2.2.0 的变动在插件功能面，不在 Java 或服务器基线。

## v2.2.0 新增了什么

以下范围是当前代码与文档实际覆盖的内容。每一项都写出它依赖的设置或指令，方便你对照参考文档。

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

## 做到哪里、有什么证据、还卡在哪里

这张表把三件容易混在一起的事分开：代码已经做了什么、它背后有哪些自动化或局部证据、以及还没跑的实机关卡。一行可以有实现也有自动化测试，但发布关卡仍然开着。

| 范围 | 已实现 | 现有证据 | 发布前仍待完成的关卡 |
| --- | --- | --- | --- |
| 本地化消息 | 三份语言文件、`{placeholder}` 变量、MiniMessage、用户输入转义 | 资源合约与语言覆盖测试 | 在运行中的服务器用 `/aceeco reload` 切换语言 |
| Discord 通知 | 异步尽力而为的 embed；长度上限与机密遮蔽 | notifier、payload 与 transport 测试 | 一次真实 webhook 发送 |
| SQL 连接池与断线恢复 | 插件自有严格连接池；不安全的借用丢弃、过期连接替换 | 连接池、生命周期与 SQL 后端测试 | 真实 MySQL/MariaDB：连接、写入、读取、重启 |
| SQL 排行榜与读取缓存 | `/baltop` 快照；供 Vault 读取的 `AccountBalanceCache` | 排行榜与余额缓存测试 | 真实 MySQL/MariaDB 的排行榜与缓存行为 |
| Vault 名称型 API | 名称方法用在线与离线缓存记录解析 | Vault provider 与名称查询测试 | 运行中服务器上的真实 Vault 消费端 |
| Essentials/CMI 导入 | 仅限控制台、路径闸门、导入前备份、可重复执行 | parser、路径闸门、TOCTOU 与有界读取测试 | 从真实 EssentialsX/CMI 数据实际导入一次 |
| 基岩版降级与银行表单 | 点击动作降级；原生表单共用存提路径 | 基岩降级与表单路由测试 | 真人基岩客户端：存款、取款、取消、重新打开 |
| PlaceholderAPI 占位符 | `aceeco` 命名空间，含 `rank` 与 `top_*` | resolver 与 expansion 测试 | 服务器上的真实 PlaceholderAPI expansion |
| 银行票据兑换 | 右键兑换走原子入账路径 | 兑换与票据 schema 测试 | 真人玩家兑换，含失败路径 |
| 可配置银行 GUI | `bank-gui` 的标题、大小与动作；reload 时关闭旧界面 | 配置 parser 与 GUI session 测试 | 真实 Java 客户端打开并点击界面 |
| 货币 reload | 纯显示变更即时套用；结构变更被拒绝并附原因 | reload 计划与货币交易测试 | 在运行中的经济系统上实际 reload |
| 权限与发送者规则 | 每个指令都声明发送者与权限政策 | 指令合约测试 | 真人玩家的权限与发送者拒绝 |

自动化测试与源码静态检查不等于实机验证，局部的运行时冒烟测试也不等于发布验收。只有下面这些发布关卡能补上这段差距。

## 安装或升级前

1. 现在先安装已发布的 v2.1.0，流程照[管理员安装手册](admin-install-runbook.zh-CN.md)。
2. 维持 Java 25 与 Paper/Folia 26.1.2 基线，`plugins/` 里只留一份 `AceLib-1.2.1.jar`。
3. `config.yml` 维持 `version: "2.0"`。改动货币结构或只在启动时读取的设置，仍需重启而不是 reload。
4. 任何变更前先备份插件数据目录或数据库，先在副本上验证。
5. 把真实 MySQL/MariaDB 演练与真人基岩客户端检查安排在发布前的验证窗口。它们是正式发布声明不可省略的 gate，必须在发布前通过，不是发布后再补。

## 发布前必须完成的关卡

以下每一项都要在草稿取代 v2.1.0 发布说明之前完成。这里没有任何一项回报为已通过。

1. **版本与 artifact** — 把 `build.gradle.kts` 升到 `2.2.0`，产出 artifact，并发布对应的 `SHA256SUMS`。
2. **完整测试与构建** — 在发布 commit 上跑完整测试套件与 release build。
3. **真人玩家权限与发送者拒绝** — 在目标服务器上验证玩家限定／控制台限定的发送者拒绝，以及缺少主权限与子权限。
4. **Java 银行界面** — 用真实 Java 客户端打开 `/bank open`，验证画面与 `DEPOSIT`、`WITHDRAW`、`CLOSE` 点击。
5. **真实 MySQL/MariaDB** — 连到目标服务，验证启动、写入、读取、重启与逻辑快照路径。
6. **真人基岩客户端** — 在基岩客户端完成存款、取款、取消与重新打开，并确认点击动作的降级提示。
7. **发布文档记录** — 把上述执行结果记录下来，让正式发布说明可以引用。

v2.1.0 留下的关卡也还开着，同样适用于这条线：实机 Folia/Bukkit 回滚执行、JSON 跨进程竞争、物理／原生数据库备份，以及真实数据还原与故障注入。见 v2.1.0 的[还没验证什么](release-v2.1.0.zh-CN.md#还没验证什么)。

## 明确不宣称的事

- v2.2.0 尚未发布、尚未可下载，也尚未完成构建。这一页没有写出版本号变更、artifact 文件名、checksum 或日期。
- 这里不提供任何测试数量或覆盖率数字。
- 上面没有任何发布关卡回报为已通过。
- Essentials/CMI 导入不是自动迁移，仍是一道明确的、仅限控制台的指令。
- 逻辑备份指令不能代替 `mysqldump`、`mariadb-dump` 或数据库管理员的物理恢复流程。
- 不支持 Vault 银行方法，Vault 一律使用配置的默认货币。
- 没有分世界余额；world-name 参数会被接受但忽略。

安装、日常运维与恢复请用[管理员安装手册](admin-install-runbook.zh-CN.md)、[服务器维运](operations.zh-CN.md) 与[持久化、备份与恢复](persistence.zh-CN.md)。收集证据或回报问题时，密码、token、webhook URL、数据文件和备份都要保密。
