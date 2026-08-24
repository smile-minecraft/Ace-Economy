# AceEconomy v2.1.0 release / AceEconomy v2.1.0 發布說明

AceEconomy v2.1.0 extends the v2 server surface with operational history and rollback commands, managed logical backup and restore, configurable currencies and command forwarding, banknote and bank GUI actions, and JSON/SQLite persistence paths. The release baseline is Java 25 with Paper/Folia 26.1.2. `AceLib-1.0.0.jar` is a required runtime dependency. The expected plugin artifact is `AceEconomy-2.1.0.jar`.

AceEconomy v2.1.0 延伸 v2 伺服器功能，包含維運交易歷史與回滾指令、受管理的邏輯備份與還原、可設定貨幣與指令轉送、銀行票據與銀行 GUI 操作，以及 JSON／SQLite 持久化路徑。發布基線是 Java 25 與 Paper/Folia 26.1.2。`AceLib-1.0.0.jar` 是必要的 runtime 相依插件；預期插件檔案是 `AceEconomy-2.1.0.jar`。

This document describes the implemented command and persistence surfaces together with the bounded runtime evidence currently available. It does not claim that the remaining live-player, live-database, client-GUI, cross-process, or recovery gates have passed.

本文同時說明已接線的指令與持久化功能，以及目前可取得的 bounded runtime evidence；不宣稱尚未完成的真實玩家、正式資料庫、客戶端 GUI、跨程序或復原驗證已通過。

For installation and daily operation, use [`admin-install-runbook.md`](admin-install-runbook.md), [`operations.md`](operations.md), and [`troubleshooting.md`](troubleshooting.md). For a v1 replacement, use [`upgrade-from-v1.md`](upgrade-from-v1.md). The detailed command and persistence references are [`commands.md`](commands.md) and [`persistence.md`](persistence.md).

安裝與日常維運請搭配 [`admin-install-runbook.md`](admin-install-runbook.md)、[`operations.md`](operations.md) 和 [`troubleshooting.md`](troubleshooting.md)。從 v1 更換時請使用 [`upgrade-from-v1.md`](upgrade-from-v1.md)。完整指令與持久化說明見 [`commands.md`](commands.md) 和 [`persistence.md`](persistence.md)。

## Release baseline / 發布基線

| Item / 項目 | v2.1.0 value / v2.1.0 值 |
|---|---|
| Java | 25 |
| Paper/Folia | 26.1.2 |
| Required dependency / 必要相依性 | `AceLib-1.0.0.jar` |
| Plugin artifact / 插件檔案 | `AceEconomy-2.1.0.jar`（預期檔名） |
| AceLib config schema / AceLib 設定 schema | `version: "2.0"` |

The config schema remains `2.0`; this release does not introduce `version: "2.1"`. Keep exactly one compatible AceLib JAR in `plugins/`. Vault and PlaceholderAPI remain optional integrations, and the JDBC drivers used by the documented storage paths are supplied by the plugin artifact.

設定 schema 維持 `2.0`；本版本沒有引入 `version: "2.1"`。`plugins/` 內只保留一個相容的 AceLib JAR。Vault 與 PlaceholderAPI 仍是選用整合；文件所述儲存路徑使用的 JDBC driver 由插件檔案提供。

## What is included / 本版本包含什麼

### Operations, history, and rollback / 維運、歷史與回滾

- `/aceeco history [player] [currency] [page]` provides a read-only, newest-first transaction history view. Page numbers start at `0`, and the documented page size is `10`.
- `/aceeco rollback <transaction-id>` reverses one recorded transaction from the console. It requires `aceconomy.admin` and `aceeconomy.admin.rollback`, validates the transaction UUID before lookup, and reports success, already-reverted, typed failure, and marker-persistence outcomes.
- An already reverted transaction is a safe no-op. If marker persistence fails, the effect may exist without durable bookkeeping; inspect storage and reconcile manually before retrying.

- `/aceeco history [player] [currency] [page]` 提供唯讀、由新到舊的交易歷史查詢。頁碼從 `0` 開始，文件定義的每頁數量是 `10` 筆。
- `/aceeco rollback <transaction-id>` 從主控台回滾一筆已記錄的交易。它需要 `aceconomy.admin` 與 `aceeconomy.admin.rollback`，會在查詢前驗證交易 UUID，並回報成功、已回滾、typed failure 與 marker 持久化結果。
- 已回滾的交易是安全 no-op。若 marker 持久化失敗，效果可能已發生但缺少持久化紀錄；重試前請先檢查儲存並人工核對。

The command surface and permission table are in [`commands.md`](commands.md). The rollback path is implemented and covered by automated contract tests, but its live Folia/Bukkit bridge execution, live database path, and real-data fault drills remain open gates; see [Remaining validation gates](#remaining-validation-gates--尚未完成的驗證閘門).

完整指令與權限表見 [`commands.md`](commands.md)。回滾路徑已實作並有自動化 contract tests 覆蓋，但 live Folia／Bukkit bridge 執行、live database 路徑與真實資料故障演練仍是未完成的閘門；請見[尚未完成的驗證閘門](#remaining-validation-gates--尚未完成的驗證閘門)。

The key command policies are:

重要指令政策如下：

| Command / 指令 | Sender / 執行者 | Permission / 權限 |
|---|---|---|
| `/aceeco history [player] [currency] [page]` | Player or console / 玩家或主控台 | `aceeconomy.admin` + `aceeconomy.admin.history` |
| `/aceeco reload` | Console only / 僅限主控台 | `aceconomy.admin` + `aceeconomy.admin.reload` |
| `/aceeco rollback <transaction-id>` | Console only / 僅限主控台 | `aceeconomy.admin` + `aceeconomy.admin.rollback` |
| `/aceeco backup [label]` | Player or console / 玩家或主控台 | `aceeconomy.admin` + `aceeconomy.admin.backup` |
| `/aceeco restore <backup-id> confirm` | Console only; no online players / 僅限主控台，且不可有玩家在線 | `aceeconomy.admin` + `aceconomy.admin.restore` |
| `/withdraw cash <amount> [currency]` | Player only / 僅限玩家 | `aceeconomy.command.withdraw` |
| `/bank open` | Player only / 僅限玩家 | `aceeconomy.command.bank` |

These are declared command policies, not live sender- or permission-denial evidence. The latter remains an open validation gate.

以上是宣告的指令政策，不是 live sender 或 permission denial evidence；後者仍是未完成的驗證閘門。

### Backup and restore / 備份與還原

Use the canonical managed commands:

使用以下 canonical 受管理指令：

```text
/aceeco backup [label]
/aceeco restore <backup-id> confirm
```

`backup` writes a v2 logical JSON snapshot under `<plugin data folder>/backups`, keeps the snapshot and matching `.ready` marker together, and does not replace an existing target. The snapshot contains logical accounts, balances, transactions, reverted markers, and consumed nonces; it does not contain database passwords or webhook URLs.

`backup` 會在 `<plugin data folder>/backups` 下寫出 v2 logical JSON snapshot，並要求 snapshot 與配對的 `.ready` marker 一起保存；既有目標不會被替換。snapshot 包含邏輯上的帳戶、餘額、交易、reverted marker 與已消耗 nonce，不包含資料庫密碼或 webhook URL。

`restore` is destructive. It is console-only, requires `aceconomy.admin` and `aceconomy.admin.restore`, rejects an online player, and accepts only lowercase `confirm`. It performs preflight checks and creates a safety backup before changing live state. After a successful restore, restart the server before players return because sessions and GUIs are not hot-refreshed.

`restore` 是破壞性操作，只能從主控台執行，需要 `aceconomy.admin` 與 `aceeconomy.admin.restore`，有玩家在線時會拒絕，而且只接受小寫 `confirm`。它會先進行 preflight 檢查並建立 safety backup，再修改正式狀態。還原成功後，讓玩家回來前請先重啟伺服器，因為 session 與 GUI 不會熱刷新。

These are logical application snapshots. They do not replace `mysqldump`, `mariadb-dump`, or a database administrator's physical/disaster-recovery process, and there are no independent `/backup` or `/restore` root commands.

這些是應用程式層的邏輯 snapshot，不能取代 `mysqldump`、`mariadb-dump` 或資料庫管理員的實體／災難復原流程，也沒有獨立的 `/backup` 或 `/restore` 根指令。

### Dynamic currency and configuration / 動態貨幣與設定

The `currencies.*` map is operator-defined. Each currency supplies an ID, display name, symbol, scale, and exactly one default currency is required. Currency IDs are normalized for case and surrounding whitespace, while invalid, duplicate, empty, or malformed currency configuration prevents a partial startup.

`currencies.*` 是由管理員定義的 map。每筆貨幣要提供 ID、顯示名稱、符號與 scale，而且必須恰好設定一筆預設貨幣。貨幣 ID 會處理大小寫與前後空白；貨幣設定無效、重複、空白或格式錯誤時，不會以半套設定啟動。

`/aceeco reload` reloads configuration and language files while preserving the last valid in-memory configuration when reload fails. It does not re-register commands or rebuild startup-only currency and alias registries. Restart after changing the plugin JAR, AceLib, storage backend or connection settings, currencies, or the configured main command alias.

`/aceeco reload` 會重新載入設定與語言檔；reload 失敗時保留最後一份有效的記憶體設定。它不會重新註冊指令，也不會重建只在啟動時建立的貨幣與 alias registry。修改插件 JAR、AceLib、儲存 backend 或連線設定、貨幣，或主指令 alias 後，請重啟伺服器。

### Banknotes, GUI actions, and command forwarding / 銀行票據、GUI 操作與指令轉送

`/withdraw cash <amount> [currency]` creates a v2 banknote. `/bank open` opens the bank interface. The documented GUI action contract includes `DEPOSIT` at slot `4`, `WITHDRAW` at slots `11` and `13`, and `CLOSE` at slot `15`. A valid banknote is credited and protected against replay before the item is removed or its stack is reduced. Invalid, replayed, or failed credits leave the item in the player's inventory.

`/withdraw cash <amount> [currency]` 會建立 v2 銀行票據；`/bank open` 會開啟銀行介面。文件中的 GUI action contract 包含：`DEPOSIT` 在 slot `4`、`WITHDRAW` 在 slot `11` 與 `13`、`CLOSE` 在 slot `15`。有效票據會先完成入帳與 replay protection，再移除物品或減少 stack；票據無效、重播或入帳失敗時，物品會保留在玩家物品欄。

The command registry forwards `plugin.yml`-declared aliases to their canonical roots: `/balance` and `/bal` forward to `/money`, `/balancetop` and `/top` forward to `/baltop`, and `/menu` and `/bankmenu` forward to `/bank`. `settings.main-command-alias` configures the additional administrator root alias and defaults to `aceeco`. Alias changes are startup-only and are rejected when they collide with another declared command label. Right-click banknote redemption is not included.

指令 registry 會把 `plugin.yml` 宣告的 alias 轉送到對應的 canonical root：`/balance` 與 `/bal` 轉送到 `/money`，`/balancetop` 與 `/top` 轉送到 `/baltop`，`/menu` 與 `/bankmenu` 轉送到 `/bank`。`settings.main-command-alias` 可設定額外的管理員 root alias，預設值是 `aceeco`。Alias 只在啟動時生效，與其他宣告的指令標籤衝突時會拒絕啟動。右鍵兌回銀行票據不在本版本範圍內。

### Persistence / 持久化

The documented v2 backends are JSON, SQLite, and MySQL-compatible configuration for MySQL/MariaDB. JSON uses `data-v2.json`; SQLite uses the configured path inside the plugin data folder; MySQL/MariaDB uses `storage.type: mysql` and `storage.mysql.*`. JSON and SQLite persistence paths have automated coverage for schema, restart, snapshot, and transaction boundaries. The current release evidence does not turn that coverage into live MySQL/MariaDB or cross-process JSON approval.

文件中的 v2 backend 包含 JSON、SQLite，以及供 MySQL／MariaDB 使用的 MySQL 相容設定。JSON 使用 `data-v2.json`；SQLite 使用插件資料夾內設定的路徑；MySQL／MariaDB 使用 `storage.type: mysql` 與 `storage.mysql.*`。JSON 與 SQLite 持久化路徑已有 schema、重啟、snapshot 與交易邊界的自動化覆蓋；目前發布 evidence 不把這些覆蓋等同於 live MySQL／MariaDB 或跨程序 JSON 已核准。

## Install, upgrade, and rollback / 安裝、升級與回退

### Fresh installation / 全新安裝

1. Stop the server and make a dated, restorable copy outside the live server directory. Include the complete `plugins/AceEconomy/` directory when it already exists.
2. Put `AceLib-1.0.0.jar` and the expected `AceEconomy-2.1.0.jar` in `plugins/`. Do not keep another AceLib version beside them.
3. Start once to create the v2 files, then confirm the active `plugins/AceEconomy/config.yml` contains `version: "2.0"`.
4. Choose JSON, SQLite, or the configured MySQL-compatible backend. Keep database passwords and webhook URLs as local values.
5. Start again, check the enable messages, and run the appropriate operator checks. Use [`admin-install-runbook.md`](admin-install-runbook.md) for the full procedure.

1. 停服，並在正式伺服器目錄外建立一份有日期、可還原的副本；若 `plugins/AceEconomy/` 已存在，請完整納入。
2. 把 `AceLib-1.0.0.jar` 與預期的 `AceEconomy-2.1.0.jar` 放進 `plugins/`，不要讓其他 AceLib 版本與它們並存。
3. 先啟動一次建立 v2 檔案，再確認啟用中的 `plugins/AceEconomy/config.yml` 含有 `version: "2.0"`。
4. 選擇 JSON、SQLite 或設定好的 MySQL 相容 backend。資料庫密碼與 webhook URL 只保留在本機。
5. 再次啟動，確認啟用訊息，並執行適用的管理員檢查。完整流程請見 [`admin-install-runbook.md`](admin-install-runbook.md)。

### Replacing v1 / 從 v1 更換

v2 is a clean-slate installation. It does not automatically migrate v1 configuration or data, and a v1 file must not be renamed to `data-v2.json` or loaded into a v2 backend. Keep the complete pre-cutover v1 installation as the rollback source. Follow [`upgrade-from-v1.md`](upgrade-from-v1.md) rather than copying v1 files into v2.

v2 是 clean-slate 安裝，不會自動 migration v1 設定或資料；不能只把 v1 檔案改名為 `data-v2.json`，也不能載入 v2 backend。請保留完整的切換前 v1 安裝作為回退來源。請依照 [`upgrade-from-v1.md`](upgrade-from-v1.md)，不要把 v1 檔案直接複製到 v2。

### Release rollback / 發布回退

To return from v2.1.0 to v1, stop v2, preserve a separate copy of the current v2 data, move the v2 JARs out of `plugins/`, and restore the dated v1 JARs, configuration, and data. Start v1 and confirm that its data is readable before allowing players back in. Never ask v1 to read `data-v2.json`, `data-v2.sqlite`, or a v2 snapshot.

要從 v2.1.0 回到 v1，請停止 v2、另外保留目前 v2 資料副本、把 v2 JAR 移出 `plugins/`，再從有日期的備份還原 v1 JAR、設定與資料。啟動 v1 並確認資料可讀後，才能讓玩家回來。絕對不要讓 v1 讀取 `data-v2.json`、`data-v2.sqlite` 或 v2 snapshot。

## Verify the release file / 驗證發布檔案

The final checksum, tag SHA, and GitHub asset URL are not included in this document. At release time, use the published `SHA256SUMS` asset as the source of truth, place it beside the artifact, and verify the bare filename entry:

本文不填入最終 checksum、tag SHA 或 GitHub asset URL。發布時請以發布的 `SHA256SUMS` 為準，把它與插件檔案放在同一個目錄，並驗證裸檔名項目：

```text
sha256sum -c SHA256SUMS
```

On macOS, calculate the local digest with:

macOS 可用以下指令計算本機 digest：

```text
shasum -a 256 AceEconomy-2.1.0.jar
```

Compare the first column with the `AceEconomy-2.1.0.jar` entry in `SHA256SUMS` before placing the plugin on a live server. Do not replace this comparison with a value copied from an earlier release.

把輸出的第一欄與 `SHA256SUMS` 中的 `AceEconomy-2.1.0.jar` 項目比對，確認後再放到正式伺服器。不要用舊版本的值取代這個比對。

## Bounded Folia runtime evidence / 有界 Folia runtime evidence

The available bounded evidence used the same v2.0.0 artifact on Folia `26.1.2-8` and `26.2-4`. It covered startup and plugin enable, AceLib capability, status/health, RCON route/help and typed errors, declared aliases, backup/restore confirmation and safety-backup paths, and reload/restart behavior.

目前的 bounded evidence 使用同一份 v2.0.0 artifact，分別在 Folia `26.1.2-8` 與 `26.2-4` 執行。涵蓋啟動與插件 enable、AceLib capability、status／health、RCON route／help 與 typed errors、宣告的 alias、備份／還原確認與 safety-backup 路徑，以及 reload／restart 行為。

This is bounded runtime evidence, not a production certification. It does not prove successful real-player economy actions, live MySQL/MariaDB behavior, GUI rendering or clicking, JSON multi-process safety, physical database backup recovery, or fault-injection recovery.

這是有界的 runtime evidence，不是 production certification。它不代表真實玩家經濟操作、live MySQL／MariaDB 行為、GUI render 或 click、JSON 多程序安全性、實體資料庫備份復原或故障注入復原已成功。

## Remaining validation gates / 尚未完成的驗證閘門

The following items are explicitly not-run or still open. A future release or operator acceptance record must provide the corresponding live evidence before treating the affected path as production-ready.

以下項目明確屬於 not-run 或仍未關閉的閘門。未來發布或管理員驗收紀錄必須提供相對應的實機 evidence，才能把受影響路徑視為 production-ready。

- **Player sender and permission denial** — not run. Verify player-only and console-only sender rejection, missing root permission, and missing child permission on the target server.
- **玩家 sender 與 permission denial** — 尚未執行。請在目標伺服器驗證玩家限定／主控台限定的 sender 拒絕、缺少 root 權限與缺少子指令權限。
- **GUI render and click** — not run. Open the bank GUI with a real client and verify rendering plus `DEPOSIT`, `WITHDRAW`, and `CLOSE` clicks.
- **GUI render 與 click** — 尚未執行。請用真實客戶端開啟銀行 GUI，驗證畫面以及 `DEPOSIT`、`WITHDRAW`、`CLOSE` 點擊。
- **Live MySQL/MariaDB** — not run. Connect to the intended service and verify startup, writes, reads, restart, and the documented logical snapshot path.
- **Live MySQL／MariaDB** — 尚未執行。請連線目標服務，驗證啟動、寫入、讀取、重啟與文件所述 logical snapshot 路徑。
- **JSON cross-process race** — not run. Run a multi-process contention test; same-process atomic file behavior is not a cross-process guarantee.
- **JSON cross-process race** — 尚未執行。請執行多程序競爭測試；同一程序內的 atomic file 行為不等於跨程序保證。
- **Physical/native backup** — not run by the plugin. For SQL production operations, validate the database administrator's native backup and restore procedure separately; the logical commands do not replace it.
- **Physical/native backup** — 插件尚未執行此項。SQL 正式維運請另外驗證資料庫管理員的 native backup／restore 流程；邏輯指令不能取代它。
- **Real-data recovery and fault injection** — not run. Use representative data and controlled failures to verify recovery, marker handling, and the required manual reconciliation path.
- **真實資料復原與故障注入** — 尚未執行。請使用代表性資料與受控故障，驗證復原、marker 處理以及必要的人工核對流程。
- **Real-player history and rollback paths** — partial paths remain not run. Exercise history queries and controlled rollback scenarios with real player accounts, including the transfer-counterpart and persistence-failure cases.
- **真實玩家 history 與 rollback 路徑** — 部分路徑仍未執行。請用真實玩家帳戶演練 history 查詢與受控 rollback 情境，包含轉帳對應腿與持久化失敗案例。

## Explicit non-goals / 明確非目標

- Automatic v1 migration is not included.
- Essentials/CMI import is not included.
- Native database dump replacement is not included.
- Right-click banknote redemption is not included.
- Independent `/backup` and `/restore` root commands are not included; use the `/aceeco` subcommands.

- 不包含 v1 自動 migration。
- 不包含 Essentials／CMI 匯入。
- 不包含 native database dump replacement。
- 不包含右鍵兌回銀行票據。
- 不包含獨立的 `/backup` 與 `/restore` 根指令；請使用 `/aceeco` 子指令。

The release scope and operational boundaries are also recorded in [`operations.md`](operations.md), [`persistence.md`](persistence.md), and [`cutover.md`](cutover.md). Keep passwords, tokens, webhook URLs, data files, and backups private when collecting evidence or reporting a problem.

發布範圍與維運邊界也記錄在 [`operations.md`](operations.md)、[`persistence.md`](persistence.md) 與 [`cutover.md`](cutover.md)。蒐集 evidence 或回報問題時，密碼、token、webhook URL、資料檔與備份都要保密。
