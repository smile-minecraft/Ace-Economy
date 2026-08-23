# AceEconomy troubleshooting / AceEconomy 故障排除

Use this page from the symptom you can see in the server console or in-game. Make a copy of the
relevant configuration and data before changing storage files. Replace every placeholder locally;
never paste a password or Discord webhook into a ticket or public message.

請從主控台或遊戲內實際看到的症狀開始查。修改儲存檔案前，先複製相關設定與資料。所有 placeholder
只在本機替換；不要把密碼或 Discord webhook 貼到工單或公開訊息。

## The plugin does not enable / 插件沒有啟用

**Possible causes:** `AceLib` is missing, the wrong AceLib version is present, or the server is not
running the required Java/Paper/Folia combination.

**可能原因：** 缺少 `AceLib`、放入錯誤版本的 AceLib，或伺服器沒有使用要求的 Java／Paper／Folia
組合。

**Check first / 先檢查：**

- Confirm that `plugins/AceLib-1.0.0.jar` and `plugins/AceEconomy-2.0.0.jar` exist.
- 確認 `plugins/AceLib-1.0.0.jar` 與 `plugins/AceEconomy-2.0.0.jar` 存在。
- Remove `AceLib-0.5.0-SNAPSHOT.jar` and any duplicate AceLib JAR from the live plugin folder.
- 從正式插件資料夾移除 `AceLib-0.5.0-SNAPSHOT.jar` 與其他重複的 AceLib JAR。
- Check the server console for the first AceLib or Java error, not only the final disable message.
- 查看主控台最早出現的 AceLib 或 Java 錯誤，不要只看最後的停用訊息。

**Fix / 修正：** use Java 25 with Paper/Folia 26.1.2, install one `AceLib-1.0.0.jar`, and perform
a full server restart. Do not try to fix a missing hard dependency with `/aceeco reload`.

**修正：** 使用 Java 25 與 Paper/Folia 26.1.2，安裝單一 `AceLib-1.0.0.jar`，再完整重啟伺服器。
缺少必要相依插件時，`/aceeco reload` 沒有用。

## Java, Paper, or Folia mismatch / Java、Paper 或 Folia 版本不符

**Possible causes:** the server is using a different Java major version, an unsupported server build,
or a Paper/Folia installation that does not expose the required API.

**可能原因：** 伺服器使用不同的 Java major version、不支援的伺服器版本，或 Paper/Folia 沒有提供
要求的 API。

**Check first / 先檢查：** confirm the server process uses Java 25 and the server version is
Paper/Folia 26.1.2. Keep the startup lines that identify Java, the server, AceLib, and AceEconomy.

確認伺服器程序使用 Java 25，伺服器版本是 Paper/Folia 26.1.2。保留啟動時顯示 Java、伺服器、AceLib
與 AceEconomy 版本的主控台內容。

**Fix / 修正：** correct the service's Java selection or server installation, then restart. Do not
replace the plugin JAR with an older copy just to bypass the error.

**修正：** 修正服務使用的 Java 或伺服器安裝，再重啟。不要為了繞過錯誤而換回舊插件 JAR。

## The storage file is missing or the wrong backend opens / 儲存檔案不存在或開啟了錯誤 backend

**Possible causes:** the selected `storage.type` does not match the file you are looking for, the
server has not completed a successful start, or the file was placed outside the plugin data folder.

**可能原因：** `storage.type` 與正在查看的檔案不一致、伺服器沒有完成成功啟動，或檔案被放在插件
資料夾以外。

**Check first / 先檢查：** open `plugins/AceEconomy/config.yml` and verify:

開啟 `plugins/AceEconomy/config.yml`，確認：

```yaml
storage:
  type: json       # json, sqlite, or mysql
```

JSON uses `plugins/AceEconomy/data-v2.json`. SQLite uses the file named by
`storage.sqlite.path`, which must remain under `plugins/AceEconomy/`. MySQL does not create a local
database file.

JSON 使用 `plugins/AceEconomy/data-v2.json`。SQLite 使用 `storage.sqlite.path` 指定的檔案，且檔案
必須留在 `plugins/AceEconomy/` 之下。MySQL 不會建立本機資料庫檔案。

**Fix / 修正：** correct the nested YAML shape, start the server again, and inspect the first storage
message. Do not rename a v1 file to `data-v2.json`.

**修正：** 修正 YAML 巢狀結構後重新啟動，並查看第一個儲存相關訊息。不要把 v1 檔案改名成
`data-v2.json`。

## SQLite path rejected / SQLite 路徑被拒絕

**Possible causes:** `storage.sqlite` was written as a scalar instead of a map, or the path escapes
the plugin data folder with `../` or an absolute path.

**可能原因：** `storage.sqlite` 被寫成單一值而不是區塊，或路徑用 `../`／絕對路徑離開插件資料夾。

**Check first / 先檢查：** the shape must be:

設定結構必須是：

```yaml
storage:
  type: sqlite
  sqlite:
    path: data-v2.sqlite
```

**Fix / 修正：** use a relative filename or subdirectory below `plugins/AceEconomy/`, then perform a
full restart. Keep any existing SQLite file before changing its path.

**修正：** 使用 `plugins/AceEconomy/` 底下的相對檔名或子資料夾，再完整重啟。修改路徑前先保留現有
SQLite 檔案。

## MySQL or Hikari connection failure / MySQL 或 Hikari 連線失敗

**Possible causes:** the host, port, database, user, password, or database permissions are wrong;
the database is unreachable; or the pool values are under the wrong YAML block.

**可能原因：** host、port、database、使用者、密碼或資料庫權限不正確；資料庫無法連線；或連線池
設定放錯 YAML 區塊。

**Check first / 先檢查：**

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

Check the database server and credentials with the database administrator's normal procedure.
`pool-size` and `max-lifetime` belong under `storage.mysql`; the JDBC driver is already in the
plugin JAR.

請用資料庫管理員平常的方式確認資料庫服務與憑證。`pool-size` 與 `max-lifetime` 必須放在
`storage.mysql` 底下；JDBC driver 已經在插件 JAR 裡。

**Fix / 修正：** correct the values, keep the password local, and restart the server. If the error
continues, do not delete the v2 data; provide the sanitized connection shape and the first database
error.

**修正：** 修正設定、只在本機填入密碼，然後重啟伺服器。若仍失敗，不要刪除 v2 資料；提供移除敏感
值後的連線設定結構與第一個資料庫錯誤。

## Discord notifications do not arrive / Discord 沒有收到通知

**Possible causes:** `discord.enabled` is false, the webhook URL is empty or invalid, or Discord
rejected the request.

**可能原因：** `discord.enabled` 是 false、webhook URL 空白或無效，或 Discord 拒絕了請求。

**Check first / 先檢查：**

```yaml
discord:
  enabled: true
  webhook-url: "<discord-webhook-url>"
```

Verify the URL locally and check the server's surrounding Discord messages. Never include the real
URL in a support request.

請在本機確認 URL，並查看伺服器附近的 Discord 相關訊息。支援請求中絕對不要包含真正的 URL。

**Fix / 修正：** correct the two keys and use `/aceeco reload`; restart if the plugin or integration
set changed. Discord delivery is asynchronous and best-effort. A failed notification does not undo
an already completed economy transaction, so check the player balance and transaction result
separately.

**修正：** 修正兩個設定鍵後執行 `/aceeco reload`；若更換了插件或整合組合，請重啟。Discord 投遞
是非同步且盡力而為；通知失敗不會撤銷已完成的經濟交易，所以要分開確認玩家餘額與交易結果。

## Vault or PlaceholderAPI integration is unavailable / Vault 或 PlaceholderAPI 整合不可用

**Possible causes:** the optional plugin is missing, disabled, or not ready when AceEconomy starts.

**可能原因：** 選用插件未安裝、未啟用，或 AceEconomy 啟動時它還沒準備好。

**Check first / 先檢查：** confirm the optional plugin itself is enabled, then restart AceEconomy by
restarting the server. Vault uses the configured default currency. PlaceholderAPI uses the `aceeco`
namespace, including `%aceeco_balance%`, `%aceeco_balance_formatted%`,
`%aceeco_balance_<currency>%`, and `%aceeco_balance_<currency>_formatted%`.

確認選用插件本身已啟用，再透過重啟伺服器重新啟動 AceEconomy。Vault 使用設定中的預設貨幣。
PlaceholderAPI 使用 `aceeco` namespace，包括 `%aceeco_balance%`、`%aceeco_balance_formatted%`、
`%aceeco_balance_<currency>%` 與 `%aceeco_balance_<currency>_formatted%`。

**Fix / 修正：** install or enable the matching optional plugin and restart. If the core economy
commands work while the integration does not, keep the core service open and troubleshoot the
optional plugin separately.

**修正：** 安裝或啟用相符的選用插件後重啟。如果經濟核心指令正常、只有整合失效，先維持核心服務
運作，再單獨排查選用插件。

## Configuration reload fails / 設定 reload 失敗

**Possible causes:** invalid YAML, a wrong v2 key shape, an invalid value, or a language file that
cannot be loaded.

**可能原因：** YAML 無效、v2 設定鍵結構錯誤、值不合法，或語言檔無法載入。

**Check first / 先檢查：** review the last edit in `plugins/AceEconomy/config.yml` and the selected
file in `plugins/AceEconomy/lang/`. Ensure the config still contains `version: "2.0"` and that
`storage.sqlite` and `storage.mysql` are maps when used.

檢查 `plugins/AceEconomy/config.yml` 最近一次修改，以及 `plugins/AceEconomy/lang/` 中選用的檔案。
確認設定仍含有 `version: "2.0"`，並且使用時 `storage.sqlite` 與 `storage.mysql` 都是區塊。

**Fix / 修正：** restore the last known-good edit and run `/aceeco reload` again. A failed reload
keeps the last valid in-memory configuration; do not assume a partially edited file is active. Use
a full restart only after the file is valid or when the changed setting belongs to startup storage
or dependency setup.

**修正：** 還原到最後一份已知正常的內容，再執行 `/aceeco reload`。reload 失敗時會保留最後有效的
記憶體設定，不要以為半完成的檔案已經套用。只有檔案修好後，或修改的是啟動時的儲存／相依設定，才
需要完整重啟。

## Reload, restart, or stop behaves unexpectedly / reload、重啟或停服行為不如預期

**Check first / 先檢查：** distinguish the three operations:

請先分清楚三種操作：

- `/aceeco reload` reloads configuration and language files.
- `/aceeco reload` 重新載入設定與語言檔。
- A full server restart reopens the storage backend and reloads plugin dependencies.
- 完整重啟會重新開啟儲存 backend，並重新載入插件相依性。
- `stop` performs a normal server shutdown and should be allowed to finish saving.
- `stop` 會執行正常停服，請等待儲存完成。

**Fix / 修正：** use a full restart after changing JARs, AceLib, `storage.type`, database
connection values, or optional plugin availability. Do not use Bukkit `/reload` for a production
upgrade or recovery.

**修正：** 修改 JAR、AceLib、`storage.type`、資料庫連線值或選用插件狀態後，請完整重啟。不要用
Bukkit `/reload` 執行正式升級或復原。

## A balance or transaction looks wrong / 餘額或交易結果不對

**Possible causes:** the command used a different currency, the wrong player was targeted, or the
server was pointed at a different v2 backend than expected.

**可能原因：** 指令使用了不同貨幣、目標玩家錯誤，或伺服器開啟的 v2 backend 與預期不同。

**Check first / 先檢查：** record the exact command without passwords, the currency ID, the player UUID
or name, the active `storage.type`, and the time of the operation. Query the balance again with
`/money balance <player> <currency>` and inspect the server log around the transaction.

記下不含密碼的完整指令、貨幣 ID、玩家 UUID 或名稱、目前的 `storage.type` 與操作時間。再用
`/money balance <player> <currency>` 查詢一次，並查看交易時間附近的伺服器主控台。

**Fix / 修正：** stop making further balance changes until the backend and currency are confirmed.
Restore data only from a known-good v2 backup and only while the server is stopped. Do not load a
v1 file into v2 or retry an unknown repair on the live store.

**修正：** 確認 backend 與貨幣前先停止其他餘額操作。只有在停服時，才可從已知正常的 v2 備份還原
資料。不要把 v1 檔案載入 v2，也不要在正式儲存上反覆嘗試未確認的修復。

## What to send when it still fails / 仍然失敗時要提供什麼

Send a short, sanitized report containing:

請提供一份已移除敏感值的簡短報告，包含：

1. AceEconomy, AceLib, Java, and Paper/Folia versions.
2. AceEconomy、AceLib、Java 與 Paper/Folia 版本。
3. The symptom and the exact time it started.
4. 症狀與開始發生的確切時間。
5. The relevant command, with player names and secrets replaced where needed.
6. 相關指令；必要時替換玩家名稱與所有敏感值。
7. The active `storage.type` and the relevant key names, but not passwords, tokens, or webhook URLs.
8. 目前的 `storage.type` 與相關設定鍵名稱，但不要提供密碼、token 或 webhook URL。
9. The first AceEconomy/AceLib/storage error and the lines immediately before it.
10. 第一個 AceEconomy／AceLib／storage 錯誤，以及錯誤前面緊鄰的主控台內容。

Keep the original data and configuration backups available. Do not “clean up” the server by deleting
the data file before someone has reviewed the report.

請保留原始資料與設定備份。有人檢查報告前，不要為了「清乾淨」而刪除資料檔。
