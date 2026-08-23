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

Stop the server before copying a JSON or SQLite data file. Keep the backup outside the live plugin
folder, use a date and purpose in its name, and do not replace the only known-good copy.

複製 JSON 或 SQLite 資料檔前先停服。備份放在正式插件資料夾外，檔名標上日期與用途，不要覆寫唯一
一份已知正常的備份。

For SQL storage, use the database administrator's normal database backup process and keep the
result with the matching server configuration backup. A v2 snapshot is parsed and checked before
it replaces live data; a malformed or incompatible snapshot leaves the current live data untouched.
There is no `/backup` or `/restore` player command.

SQL 儲存請使用資料庫管理員平常的資料庫備份流程，並與相同版本的伺服器設定備份一起保存。v2
snapshot 會在取代正式資料前先完成解析與檢查；格式錯誤或版本不相容時，現有資料會保持不變。沒有
`/backup` 或 `/restore` 玩家指令。

Before restoring, stop the server, copy the current data aside, and confirm that the backup is a v2
snapshot. A malformed or incompatible snapshot must not be used as a reason to delete the live data.

還原前先停服，把目前資料另外複製保留，並確認備份是 v2 snapshot。snapshot 損壞或版本不相容時，
不要因此刪除正式資料。

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

## Handing a problem to support / 將問題交給支援人員

Use [`troubleshooting.md`](troubleshooting.md) and provide the first relevant error, the versions,
the active storage type, the exact sanitized command, and the time of the incident. Keep data files,
backups, passwords, tokens, and webhook URLs private.

請依照 [`troubleshooting.md`](troubleshooting.md) 整理第一個相關錯誤、版本、目前儲存類型、已移除
敏感值的完整指令與發生時間。資料檔、備份、密碼、token 與 webhook URL 都要保密。
