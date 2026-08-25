# AceEconomy v2——能力基线矩阵

[English](v2-capability-matrix.md) · 简体中文 · [繁體中文](v2-capability-matrix.zh-TW.md)

本文是 clean-slate v2 重写前使用的 v1 能力基线，记录需要保留的产品能力和经济规则，指出 v2 可以破坏哪些 v1 兼容性，并标记需要单独决策的删除项。证据来自工作区中的实际 source、resource 和 test，而不是历史报告。当前 v2.0.0/v2.1 的接线状态和未验证事项见[v2.0.0/v2.1 范围边界](#v200-v21-范围边界)。

## 目录

- [验证环境与证据基线](#验证环境与证据基线)
- [能力保留矩阵](#能力保留矩阵)
- [经济规则](#经济规则)
- [非目标](#非目标)
- [剩余风险与 v2 前置条件](#剩余风险与-v2-前置条件)
- [v2.0.0/v2.1 范围边界](#v200-v21-范围边界)

## 验证环境与证据基线

- 命令（系统 Java 为 25.0.4，而 Gradle 8.12 Kotlin DSL 在该设置下无法解析，因此使用 Java 21）：`JAVA_HOME=<java21> ./gradlew clean test`。
- 结果：**70 tests, 0 failures, 0 errors, 0 skipped**（49 个 v1 基线测试，加上本轮新增的 21 个 capability 测试）。
- 锁定规则的测试位于 `src/test/java/com/smile/aceeconomy/capability/`：`EconomyCapability.java`（不含 v1 class 名称的 v2 契约介面）、`V1CurrencyManagerAdapter.java`（唯一允许引用 v1 class 的测试 adapter）、`EconomyCapabilityContractTest.java`（余额、交易、限制和取消情境）、`ConfigCapabilityTest.java`（config.yml 多货币、债务、语言、Discord 和存储契约）、`CommandSurfaceCapabilityTest.java`（plugin.yml 指令与权限表面契约）。

## 能力保留矩阵

- **RETAIN**——v2 必须保留该产品能力；内部可以重写，但行为契约保持不变。
- **RESET**——保留能力，但 v2 可以破坏 v1 binary、data、config、banknote schema 和 API 兼容性。
- **EXCLUDE**——计划删除该产品能力；需要单独决策，本基线不自行假定删除。

| # | 产品能力 | 状态 | 已确认的 v1 证据 | 锁定方式 |
|---|---|---|---|---|
| 1 | 多货币系统 | RETAIN | `config.yml` `currencies:` dollar/token；`CurrencyManager` | ConfigCapabilityTest + ContractTest.currencyExists |
| 2 | 账户与起始余额 | RETAIN | `Account`、`ConfigManager.getStartBalance()`=1000 | ContractTest.testAccountStartsAtStartBalance |
| 3 | 存款与提款（原子交易） | RETAIN | `CurrencyManager.deposit/withdraw` | ContractTest deposit/withdraw |
| 4 | 转账 | RETAIN | `PayCommand`、`EconomyProvider`（v1 在指令层组合 withdraw+deposit） | 契约已预留；v2 应提供明确的 transfer 入口（见风险 R3） |
| 5 | Vault 经济整合 | RETAIN | `hook/VaultImpl.java` | plugin.yml `softdepend: Vault`（可选；v2.0 已接线，缺少时跳过） |
| 6 | PlaceholderAPI | RETAIN | `hook/AceEcoExpansion.java` | plugin.yml `softdepend: PlaceholderAPI` |
| 7 | SQLite 存储 | RETAIN | `storage/implementation/SQLiteImplementation.java` | ConfigCapabilityTest.storage；v2.0.0 已接线（`storage.type: sqlite` → `SqlBackend + SqliteDialect`，文件位于插件数据目录内；`StorageConfigParser` 拒绝路径越界） |
| 8 | MySQL 存储 | RETAIN | `storage/implementation/MySQLImplementation.java` | ConfigCapabilityTest.storage；v2.0.0 已接线（`storage.type: mysql` → `SqlBackend + MySqlDialect` + HikariCP，JDBC driver 已 shade；连接池来自 `storage.mysql.*`）；live MySQL 连接仍未验证（v2.0.0 release gate） |
| 9 | JSON 存储 | RETAIN | `storage/JsonStorageHandler.java` | 列为保留；v1 config 未默认启用，v2 决定是否默认启用 |
| 10 | 交易记录与审计 | RETAIN | `LogManager`、`listeners/EconomyLogListener`、`AuditListener` | 契约已预留（见风险 R4）；v2.0.0 通过 `PersistentAuditSink` 和 `TransactionRepository.append`/`appendBatch` 写入；只读查询通过 `HistoryService` 与 `ProductionAdapters.History` 接入 `/aceeco history [player] [currency] [page]`，权限为 `aceeconomy.admin.history`；live server 验证仍未完成 |
| 11 | Rollback | RETAIN | `commands/RollbackCommand.java`；权限 `aceeconomy.admin.rollback` | v2 已通过 `RollbackService` 和 `ProductionAdapters.Rollback` 接入 `/aceeco rollback <transaction-id>`（仅主控台、root `aceeconomy.admin` 加 child `aceeconomy.admin.rollback`、atomic `StorageReversalExecutor`）；live server 验证仍未完成 |
| 12 | 银行票据 | RESET | `BanknoteInputListener`、`listeners/BanknoteListener` | 保留权限／指令表面；schema 可以不兼容 |
| 13 | 银行 GUI | RETAIN | `gui/BankMenu.java`、`gui/GUIListener`；`/bank` | CommandSurfaceCapabilityTest |
| 14 | 排行榜 | RETAIN | `LeaderboardManager`、`BaltopCommand`；`/baltop` | CommandSurfaceCapabilityTest |
| 15 | Discord 通知 | RETAIN | `utils/DiscordWebhook`、`service/DiscordWebhook`；`config.discord` | ConfigCapabilityTest.discord |
| 16 | 三种语言（en_US/zh_TW/zh_CN） | RETAIN | `lang/messages_*.yml`；`ConfigManager` locales | ConfigCapabilityTest.locale；v2 使用 `lang/<locale>.yml`，`messages_*.yml` 是 v1 dead keys |
| 17 | Essentials / CMI 导入 | EXCLUDED | `migration/EssentialsMigrator.java`、`migration/CMIMigrator.java`（仅为 v1 历史证据） | 按用户决策从 v2.0.0 和 v2.1 follow-up 移除；不引入 vendor parser、import command/API 或 v1 → v2 migration 兼容层；保留的 `ImportService` 只代表一般 service/unit contract，不代表产品功能 |
| 18 | 债务／负余额 | RETAIN | `config.economy.allow-negative-balance`、`default-debt-limit`；`CurrencyManager.getDebtLimit` | ContractTest DebtEnabled/Disabled |
| 19 | 权限契约（含 rollback/debt bypass） | RETAIN | `plugin.yml` permissions | CommandSurfaceCapabilityTest.permissions |

## 经济规则

以下规则由 `ContractTest` 锁定：

1. 新账户以 `start-balance`（v1=1000）初始化。
2. 存款和提款是单一货币的原子交易；非正数金额会被拒绝，交易取消。
3. 余额不足的提款抛出 `InsufficientFundsException`，余额保持不变，交易取消。
4. 关闭债务时余额不能低于 0；负值 `setBalance` 会被拒绝。
5. 开启债务时余额可以为负，但受 `debt-limit` 限制；超过上限的提款会取消。
6. 货币 ID 比对不区分大小写，并且会安全处理空白（`currencyExists`）。

## 非目标

- 不升级 Gradle、Java、Paper 或 AceLib。
- 不重写 production domain、storage 或 plugin code。
- 不执行架构重写或后续 v2 实现阶段。
- 不发布、推送或创建外部状态。
- 不承诺 v1 binary、data、config 或 banknote 兼容性（标为 RESET 的项目可不兼容）。

## 剩余风险与 v2 前置条件

- R1：本轮使用 Java 21；系统 Java 25 会导致 Gradle 8.12 Kotlin DSL 失败。v2 实现需要决定 CI／本机 `JAVA_HOME` 策略或升级 Gradle。
- R2：capability tests 通过 `V1CurrencyManagerAdapter` 映射 v1 行为；v2 需要新的 adapter，契约介面不变。
- R3：v1 没有独立的 `transfer` 方法（由指令层组合操作）；当前契约锁定 atomic deposit/withdraw，因此 v2 应明确 transfer 语意。
- R4：审计和 rollback 边界尚未由 capability tests 锁定（需要 storage/log 参与，超出本文最小变更范围）；建议 v2 实现加入 `AuditCapability` 契约。

## v2.0.0/v2.1 范围边界

本节以当前 source 和 build 配置为准。完整接线细节见[cutover](cutover.zh-CN.md)；不要把 v1 保留契约或 unit tests 当作 v2.0.0 production availability 的证明。

- **v2.0.0 已接线：** JSON（默认）、SQLite 和 MySQL persistence；bank GUI deposit/redeem；`EconomyService`、`EconomyApiImpl`、`PersistentAuditSink`、`HistoryService`、`RollbackService`、`LeaderboardService`、banknotes、bank GUI 和可选 Vault/PAPI 整合；六个指令 `money`、`pay`、`withdraw`、`baltop`、`bank`、`aceeco`，以及八个 `aceeco` 子指令 `give`、`take`、`set`、`history`、`reload`、`rollback`、`backup`、`restore`。
- **已接线但仍待 live 验证：** `/aceeco history`、`/aceeco rollback` 和管理式 backup/restore。canonical 指令是 `/aceeco backup [label]` 与 `/aceeco restore <backup-id> confirm`，没有 `/backup` 或 `/restore` 根指令。`restore` 仅主控台可用，拒绝有在线玩家的情况，只接受小写 `confirm`，会先做 JSON/schema/records/currency preflight，建立 safety backup，成功后清除 leaderboard cache，但不会热刷新 session 或 GUI。
- JSON、SQLite 和 MySQL 共用 v2 logical JSON snapshot。MySQL 使用 logical snapshot，不是 native dump，因此不能取代 `mysqldump`、`mariadb-dump` 或数据库维运备份。
- **Essentials/CMI import：** 已从本 Plan 和 v2.0.0 移除；保留的 `ImportService` 不代表产品可用性。
- **未验证：** live Folia/Bukkit（包括 Folia 26.1.2 fresh install、RCON／游戏内检查、故障演练及 backup/restore 演练）、live MySQL 和跨进程 smoke 仍是 v2.0.0 release gate。unit tests 和 v1 `lang/messages_*.yml` dead keys 不代表 production availability；v2 语言文件是 `lang/<locale>.yml`。
