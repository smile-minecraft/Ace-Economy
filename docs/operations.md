# AceEconomy v2 server operations / AceEconomy v2 伺服器維運

This page is the day-to-day checklist for a server administrator: start the service, make a safe
configuration change, confirm storage, and respond to a problem without deleting data first.

本頁是伺服器管理員的日常清單：啟動服務、安全地修改設定、確認儲存狀態，遇到問題時先保護資料再處理。

## Start-of-day checks / 每次開服檢查

After a normal start or restart, check the server console for `AceEconomy v2.0.0` and
`AceLib v1.0.0`. Confirm that only one AceLib version is loaded. Then use a test account to run
`/money balance` and, when appropriate, `/baltop top`.

正常啟動或重啟後，請在主控台確認 `AceEconomy v2.0.0` 與 `AceLib v1.0.0`，並確認只載入一個 AceLib
版本。接著用測試帳號執行 `/money balance`，需要時再執行 `/baltop top`。

If the server is not ready, do not open it to players. Keep the first AceEconomy, AceLib, or
storage error and follow [`troubleshooting.md`](troubleshooting.md).

伺服器尚未準備好時，不要開放玩家進入。保留第一個 AceEconomy、AceLib 或 storage 錯誤，並依照
[`troubleshooting.md`](troubleshooting.md) 排查。

## Choosing and checking storage / 選擇與確認儲存方式

The v2 backend is selected by `storage.type`:

v2 backend 由 `storage.type` 選擇：

| Value | Location / 位置 | Good fit / 適用情境 |
|---|---|---|
| `json` | `plugins/AceEconomy/data-v2.json` | One server with a local file / 單一伺服器本機檔案 |
| `sqlite` | `storage.sqlite.path` under the plugin data folder | One server with a SQLite database / 單一伺服器 SQLite |
| `mysql` | `storage.mysql.*` | A managed MySQL or MariaDB service / 管理式 MySQL 或 MariaDB |

Keep the SQLite path inside `plugins/AceEconomy/`. For MySQL, keep `pool-size` and
`max-lifetime` under `storage.mysql`, and keep the password outside shared documentation.

SQLite 路徑必須留在 `plugins/AceEconomy/` 內。MySQL 的 `pool-size` 與 `max-lifetime` 要放在
`storage.mysql` 底下，密碼不要放進共用文件。

The same v2 account and transaction model is used for the supported backends. A v1 data file is
not a v2 backup and must not be substituted for one.

支援的 backend 都使用相同的 v2 帳戶與交易模型。v1 資料檔不是 v2 備份，不能拿來互相替換。

## Safe configuration changes / 安全修改設定

Edit `plugins/AceEconomy/config.yml` while the server is running only when your normal change
process protects the file. Make a copy before editing, keep `version: "2.0"`, and validate the
YAML shape before applying it.

只有在既有變更流程能保護檔案時，才在伺服器運作中編輯 `plugins/AceEconomy/config.yml`。修改前先
複製，保留 `version: "2.0"`，並在套用前確認 YAML 結構。

Use the console command for ordinary configuration or language changes:

一般設定或語言檔變更請從主控台執行：

```text
/aceeco reload
```

The reload reports `AceEconomy reloaded` on success and keeps the last valid in-memory snapshot if
the new configuration or language files cannot be loaded. After changing `storage.type`, the
SQLite path, MySQL connection values, plugin JARs, AceLib, or optional plugins, stop and restart
the full server instead.

成功時會回報 `AceEconomy reloaded`；新的設定或語言檔無法載入時，會保留最後有效的記憶體設定。
修改 `storage.type`、SQLite 路徑、MySQL 連線值、插件 JAR、AceLib 或選用插件後，請完整停服再啟動。

Do not use Bukkit `/reload` as a maintenance or upgrade shortcut.

不要把 Bukkit `/reload` 當成維護或升級捷徑。

## Routine commands / 日常指令

Use these forms when checking a live server:

檢查正式伺服器時使用以下格式：

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

Run a small, reversible check with a test account rather than changing a real player's balance.
Admin balance changes should be recorded in the server's normal administration notes.

請用測試帳號做小額、可回復的檢查，不要直接修改真實玩家餘額。管理員調整餘額時，請記錄在伺服器
平常使用的管理紀錄中。

## Backups and restore / 備份與還原

Managed logical snapshots and manual disaster-recovery copies are different procedures. Do not use
the instructions for one as a substitute for the other.

受管理的邏輯快照與手動災難復原備份是兩種不同流程，不要把其中一種的操作方式套用到另一種。

### Manual file and database disaster recovery / 手動檔案與資料庫災難復原

Before directly copying a JSON or SQLite data file, stop the server. Keep that manual copy outside
the live plugin folder, use a date and purpose in its name, and do not replace the only known-good
copy. This is a file-level disaster-recovery copy, not the managed `/aceeco backup` command.

直接複製 JSON 或 SQLite 資料檔前，請先停服。這類手動備份應放在正式插件資料夾外，檔名標上日期與用途，
不要覆寫唯一一份已知正常的備份。這是檔案層級的災難復原備份，不是受管理的 `/aceeco backup` 指令。

For MySQL or MariaDB native/physical backups, follow the database administrator's normal backup
process and keep the result with the matching server configuration backup. Do not treat a native or
physical database backup as the managed v2 logical snapshot. The live MySQL/Folia combination and
disaster-recovery workflows have not been validated here; verify them in a controlled drill before
relying on them in production.

MySQL 或 MariaDB 的 native／physical backup 請依資料庫管理員平常的備份流程執行，並與相同版本的伺服器
設定備份一起保存。native 或 physical database backup 不等同於受管理的 v2 邏輯快照。本文件尚未驗證
live MySQL／Folia 組合與災難復原流程；正式依賴前，請先在受控演練中確認。

### Managed commands / 管理指令

The console and authorized administrators can also create a managed logical snapshot:

主控台與獲得授權的管理員也可以建立受管理的邏輯快照：

```text
/aceeco backup [label]
```

This command can run while the server is running. It writes a credential-free v2 JSON logical
snapshot under the plugin-controlled `<plugin data folder>/backups/` directory using a verified
secure directory handle. It creates `<backup-id>.json` with handle-relative `CREATE_NEW`, writes
and forces the complete content, then creates `<backup-id>.ready` with handle-relative
`CREATE_NEW`. The ready file contains a SHA-256 digest and is the application-level logical
commit point; restore requires the marker and a matching, fully validated JSON snapshot. Existing
target or marker names are never replaced. The optional label accepts only letters, digits, `.`,
`_` and `-`. The snapshot contains accounts, balances, transactions (including reverted markers)
and consumed nonces — never storage passwords or webhook URLs.
For MySQL it reads through the live connection; it is a logical snapshot, not a
`mysqldump`/`mariabackup` native or physical backup.

伺服器運作中即可執行此指令。它會在插件受控的 `<plugin data folder>/backups/` 目錄下，使用已驗證的
secure directory handle，以 handle-relative `CREATE_NEW` 建立 `<backup-id>.json`，完整寫入並 force，
再以 handle-relative `CREATE_NEW` 建立 `<backup-id>.ready`。ready 檔包含 SHA-256 digest，是 application-level
logical commit point；還原時必須同時存在 marker，且 JSON 完整驗證並與 digest 相符。既有 target 或 marker
都不會被覆寫。選用的 label 只接受英文字母、數字、`.`、`_` 與 `-`。snapshot 內容包含帳戶、餘額、交易（含
reverted 標記）與已消耗 nonce，不會包含資料庫密碼或 webhook URL。MySQL 會透過運作中的連線讀取；這是邏輯
快照，不是 `mysqldump`／`mariabackup` 的 native 或 physical backup。

Snapshot publication requires a filesystem that supports secure directory handles, no-follow
attribute checks, regular-file checks, and forced file channels. The protocol is an application-level
commit-marker protocol; it does not claim an OS atomic rename or hard-link publication. On an
unsupported filesystem, or after a partial target/marker failure, the command fails closed instead
of falling back to an unsafe write. Keep the `.json` and matching `.ready` files together when
moving a snapshot; a bare JSON file is not a committed backup. An unmarked orphan may remain and
restore will reject it.

snapshot 的發布需要檔案系統支援 secure directory handles、no-follow attribute 檢查、regular-file 檢查與
forced file channel。這是 application-level commit marker protocol，不宣稱 OS atomic rename 或 hard-link
發布。在不支援的檔案系統上，或 target／marker 部分寫入失敗後，指令會以明確錯誤 fail closed，不會退回
不安全的寫入方式。搬移 snapshot 時必須保留配對的 `.json` 與 `.ready`；只有 JSON 不是已提交的備份；沒有
marker 的 orphan 可以留下，但還原一律拒絕。

Restoring is destructive and strictly gated:

還原是破壞性操作，且有嚴格閘門：

```text
/aceeco restore <backup-id> confirm
```

- Console only, requires `aceeconomy.admin` plus `aceeconomy.admin.restore`, and the literal
  word `confirm` exactly (uppercase or any other spelling is rejected).
- Rejected while any player is online; ask everyone to leave first.
- Before touching live data it validates the snapshot (JSON shape, schema version, records,
  currency compatibility) and writes a safety backup of the current state. If the safety
  backup fails, nothing is restored.
- On success the old state's safety backup id is reported and the leaderboard cache is
  cleared. Restart the server before letting players back in: open sessions and GUIs are not
  hot-refreshed by design.

- 僅限主控台，需要 `aceeconomy.admin` 加 `aceeconomy.admin.restore`，且必須逐字輸入
  `confirm`（大寫或其他拼法都會被拒絕）。
- 有任何玩家在線時一律拒絕；請先請所有玩家離線。
- 動到正式資料前，會先驗證 snapshot（JSON 結構、schema 版本、紀錄、貨幣相容性），並對目前
  狀態寫出一份安全備份；安全備份失敗時不會執行還原。
- 成功時會回報舊狀態的安全備份 ID 並清除排行榜快取。讓玩家回來前請先重啟伺服器：設計上不會
  熱刷新既有 session 與 GUI。

There are no separate `/backup` or `/restore` root commands; these operations exist only as
`/aceeco` admin subcommands.

沒有獨立的 `/backup` 或 `/restore` 根指令；這兩個操作只以 `/aceeco` 管理子命令存在。

The managed restore does not require a separate manual shutdown or file copy before running the
command. It is still best scheduled in a maintenance window. The no-online-player gate, preflight,
and pre-restore safety backup protect the managed operation; a malformed or incompatible snapshot
must not be used as a reason to delete the live data.

受管理的還原不要求執行指令前另外手動停服或複製資料，但仍建議安排在維運窗口。無在線玩家閘門、
preflight 與還原前安全備份會保護這個受管理操作；snapshot 損壞或版本不相容時，不要因此刪除正式資料。

## Integrations / 整合功能

Vault and PlaceholderAPI are optional. If either is absent, the core economy service can still run.
Vault uses the configured default currency. PlaceholderAPI uses the `aceeco` namespace:

Vault 與 PlaceholderAPI 都是選用插件。缺少其中之一時，經濟核心仍可運作。Vault 使用設定的預設貨幣；
PlaceholderAPI 使用 `aceeco` namespace：

```text
%aceeco_balance%
%aceeco_balance_formatted%
%aceeco_balance_<currency>%
%aceeco_balance_<currency>_formatted%
```

Discord is configured under `discord.enabled` and `discord.webhook-url`. Store the real webhook
only on the server. Delivery is asynchronous and best-effort: a notification problem must be
handled separately from the already completed economy transaction.

Discord 使用 `discord.enabled` 與 `discord.webhook-url` 設定。真正的 webhook 只放在伺服器本機。
通知採非同步、盡力而為；通知問題要和已完成的經濟交易分開處理。

## Stop, restart, and reopen / 停服、重啟與重新開放

Use the normal service control or the server console command:

請使用平常的服務管理方式或伺服器主控台指令：

```text
stop
```

Wait for world and plugin saving to finish. After a restart, repeat the start-of-day checks before
letting players back in. If a restart follows a failed reload, restore the last known-good config
first so the server does not repeatedly boot with the same bad edit.

請等待世界與插件儲存完成。重啟後先重做每次開服檢查，再讓玩家回來。如果重啟是因 reload 失敗而起，
先還原最後一份已知正常的設定，避免伺服器反覆用同一份錯誤設定啟動。

## Emergency rollback / 緊急回退

For a v2-to-v1 rollback, follow [`upgrade-from-v1.md`](upgrade-from-v1.md). Keep the current v2
data copy before restoring the pre-upgrade v1 installation. Never ask v1 to read
`data-v2.json`, `data-v2.sqlite`, or a v2 snapshot.

要從 v2 回退到 v1，請依照 [`upgrade-from-v1.md`](upgrade-from-v1.md)。還原升級前的 v1 安裝前，
先保留目前的 v2 資料副本。絕對不要讓 v1 讀取 `data-v2.json`、`data-v2.sqlite` 或 v2 snapshot。

To reverse a single recorded transaction (for example, a mistaken admin grant), use
`/aceeco rollback <transaction-id>` from the console. It is console-only, requires
`aceeconomy.admin` plus `aceeconomy.admin.rollback`, and reports each outcome explicitly:
success lists the reversal audit record ids, an already reverted transaction is a safe no-op,
and a marker persist failure means the effect may exist without durable bookkeeping — inspect
storage and reconcile manually before retrying. See [`commands.md`](commands.md) for the full
outcome table.

若要復原單筆已記錄的交易（例如管理員誤發餘額），請從主控台執行
`/aceeco rollback <transaction-id>`。它僅限主控台、需要 `aceeconomy.admin` 加
`aceeconomy.admin.rollback`，且每種結果都有明確回覆：成功時列出 reversal 稽核紀錄 ID、
已回滾的交易是安全 no-op、marker 寫入失敗代表效果可能已發生但缺少持久化紀錄——先檢查儲存並
人工核對再考慮重試。完整結果對照見 [`commands.md`](commands.md)。

The rollback command is wired into the production command surface and covered by automated
contract tests, but live validation is still pending: Folia/Bukkit bridge execution, live MySQL
storage, and fault-injection drills with real data have not been run yet. Until that release gate
closes, use it only in controlled drills with backups taken beforehand.

rollback 指令已接入 production command surface，也有自動化 contract tests 覆蓋，但 live 驗證
尚未完成：Folia/Bukkit bridge 實機執行、live MySQL 儲存與真實資料的故障注入演練都還沒做。
在該 release gate 結束前，只建議在受控演練中使用，並事先完成備份。

## Handing a problem to support / 將問題交給支援人員

Use [`troubleshooting.md`](troubleshooting.md) and provide the first relevant error, the versions,
the active storage type, the exact sanitized command, and the time of the incident. Keep data files,
backups, passwords, tokens, and webhook URLs private.

請依照 [`troubleshooting.md`](troubleshooting.md) 整理第一個相關錯誤、版本、目前儲存類型、已移除
敏感值的完整指令與發生時間。資料檔、備份、密碼、token 與 webhook URL 都要保密。
