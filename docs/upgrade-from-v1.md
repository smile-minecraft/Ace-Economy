# Upgrade from AceEconomy v1 / 從 AceEconomy v1 升級

AceEconomy v2 is a clean v2 installation, not an in-place schema upgrade. It uses
`version: "2.0"` in `config.yml` and v2 storage files or tables. v1 configuration, v1 data, and
v1 plugin APIs are not converted automatically.

AceEconomy v2 是全新的 v2 安裝，不是直接在 v1 資料上升級 schema。v2 的 `config.yml` 使用
`version: "2.0"`，資料也使用 v2 的檔案或資料表。v1 設定、v1 資料與 v1 插件 API 不會自動轉換。

If the server still needs its v1 balances, treat the v1 installation as the rollback source and
keep it intact until the v2 server has been accepted by the server owner.

如果伺服器還需要保留 v1 餘額，請把完整的 v1 安裝當成回退來源，在伺服器管理員確認 v2 前都不要
破壞它。

## What changes / 這次升級會改變什麼

The v2 runtime requires Java 25, Paper/Folia 26.1.2, and `AceLib-1.0.0.jar`. The v2 plugin is
`AceEconomy-2.1.0.jar`. Vault and PlaceholderAPI remain optional integrations.

v2 執行環境需要 Java 25、Paper/Folia 26.1.2，以及 `AceLib-1.0.0.jar`。v2 插件檔案是
`AceEconomy-2.1.0.jar`。Vault 和 PlaceholderAPI 仍然是選用整合。

The v2 command surface is the explicit subcommand form: `/money balance`, `/pay send`,
`/withdraw cash`, `/baltop top`, `/bank open`, and `/aceeco` administration commands. Do not use
v1-only history, rollback, import, or old banknote data instructions as if they were v2 commands.

v2 指令使用明確的子指令格式：`/money balance`、`/pay send`、`/withdraw cash`、
`/baltop top`、`/bank open`，以及 `/aceeco` 管理指令。不要把 v1 的 history、rollback、import
或舊 banknote 資料操作當成 v2 指令。

## Before touching the live server / 操作正式伺服器前

1. Schedule a maintenance window and stop the server from its normal console or service control.
   The Minecraft console command is `stop`.
2. Make a dated, restorable copy of the whole server. At minimum include the v1
   `plugins/AceEconomy/` directory, the active v1 configuration, the v1 AceEconomy JAR, the
   current AceLib JAR, and the server data needed to restore the old installation.
3. Keep the copy outside the live server directory. Do not use it as the working directory for
   v2 files.

1. 安排維護時段，使用平常的主控台或服務管理方式停服。Minecraft 主控台指令是 `stop`。
2. 建立一份有日期、可以還原的完整伺服器副本。至少要包含 v1 的
   `plugins/AceEconomy/`、正在使用的 v1 設定、v1 AceEconomy JAR、目前的 AceLib JAR，以及
   還原舊安裝所需的伺服器資料。
3. 備份放在正式伺服器目錄外，不要把它當成 v2 的工作目錄。

Before proceeding, write down which v1 storage files or database are authoritative. Do not assume
that a file named `data-v2.json`, a v1 JSON file, and a SQL database can be exchanged merely because
they all contain balances.

開始前先記下 v1 實際使用的資料檔或資料庫。不要因為 `data-v2.json`、v1 JSON 檔與 SQL 資料庫
都存有餘額，就假設它們可以互相替換。

## The cutover / 執行切換

### 1. Remove v1 from the live plugin set / 1. 從正式插件清單移除 v1

With the server stopped, move the old AceEconomy JAR and old AceLib JAR out of the live
`plugins/` directory. Keep them in the dated backup rather than deleting them. Do not leave two
AceLib versions in `plugins/`.

停服後，把舊版 AceEconomy JAR 與舊版 AceLib JAR 移出正式 `plugins/` 目錄。請保留在有日期的備份中，
不要直接刪除。`plugins/` 裡不要同時留下兩個 AceLib 版本。

### 2. Install the v2 pair / 2. 放入 v2 插件組合

Place these files in the live `plugins/` directory:

請把以下檔案放入正式 `plugins/` 目錄：

```text
AceLib-1.0.0.jar
AceEconomy-2.1.0.jar
```

Add Vault and PlaceholderAPI only if the server uses those integrations. Do not add a separate
SQLite or MySQL JDBC driver; both are included in the AceEconomy JAR.

只有伺服器需要時才放入 Vault 與 PlaceholderAPI。不要另外加入 SQLite 或 MySQL JDBC driver，
兩者已包含在 AceEconomy JAR 裡。

### 3. Create a v2 configuration / 3. 建立 v2 設定

Let v2 create `plugins/AceEconomy/config.yml`, or replace the generated file with a deliberately
written v2 configuration. Confirm the first line of the active schema is:

讓 v2 建立 `plugins/AceEconomy/config.yml`，或用明確撰寫的 v2 設定取代產生檔。確認啟用中的 schema
包含：

```yaml
version: "2.0"
```

Re-enter currencies, starting balance, debt settings, locale, storage selection, leaderboard
settings, and optional Discord settings. Do not copy a v1 `config-version` block or assume that v1
currency names and limits were imported.

請重新填寫貨幣、起始餘額、債務設定、語系、儲存方式、排行榜設定與選用的 Discord 設定。不要複製
v1 的 `config-version` 區塊，也不要假設 v1 貨幣名稱與限制已被匯入。

### 4. Choose the v2 storage / 4. 選擇 v2 儲存方式

For a file-based server, v2 JSON is the default:

檔案型伺服器可以使用 v2 預設的 JSON：

```yaml
storage:
  type: json
```

SQLite uses a new v2 file under the plugin data folder:

SQLite 會在插件資料夾內使用新的 v2 檔案：

```yaml
storage:
  type: sqlite
  sqlite:
    path: data-v2.sqlite
```

MySQL or MariaDB uses the v2 `storage.mysql.*` settings. Keep the password as a local value and
make a database backup with the database administrator's normal backup process before cutover.

MySQL 或 MariaDB 使用 v2 的 `storage.mysql.*` 設定。密碼只保留在本機，並在切換前使用資料庫管理員
平常的備份流程完成資料庫備份。

The v2 JSON snapshot format has its own schema version. A v1 data file is not a v2 snapshot and
must not be renamed to `data-v2.json` or loaded into a v2 backend.

v2 JSON snapshot 有自己的 schema version。v1 資料檔不是 v2 snapshot，不能只改名成
`data-v2.json`，也不能直接載入 v2 backend。

### 5. Start and configure v2 / 5. 啟動並設定 v2

Start the server and wait for `AceEconomy v2.1.0` to enable. Confirm that the chosen v2 storage
has been opened, then edit the generated settings if needed. Use `/aceeco reload` from the console
for configuration and language changes; use a full restart after changing the plugin files, AceLib,
or storage connection settings.

啟動伺服器並等待 `AceEconomy v2.1.0` 啟用。確認選定的 v2 儲存已開啟，再視需要修改產生的設定。
設定與語言檔變更可從主控台執行 `/aceeco reload`；修改插件檔案、AceLib 或儲存連線設定後，請完整
重啟伺服器。

### 6. Check before opening to players / 6. 開放玩家前檢查

Use a test account and check:

請用測試帳號確認：

- `/money balance` returns the expected v2 account value.
- `/money balance` 能回傳預期的 v2 帳戶餘額。
- `/pay send <player> <amount> [currency]` completes a small transfer.
- `/pay send <player> <amount> [currency]` 能完成一筆小額轉帳。
- `/withdraw cash <amount> [currency]` creates a banknote when that workflow is enabled.
- `/withdraw cash <amount> [currency]` 在啟用該流程時能建立銀行支票。
- `/baltop top [currency]` and `/bank open` respond normally.
- `/baltop top [currency]` 與 `/bank open` 回應正常。
- Optional Vault, PlaceholderAPI, and Discord behaviour matches the settings you enabled.
- 選用的 Vault、PlaceholderAPI 與 Discord 行為符合你啟用的設定。

Do not interpret a clean plugin enable as proof that v1 balances were migrated. The v2 server is
ready only after the owner has decided how the old data will be retained or recreated.

插件成功啟用不代表 v1 餘額已經 migration。只有在管理員決定如何保留或重新建立舊資料後，v2 才算
完成切換準備。

## Rollback / 回退

Rollback means restoring the pre-cutover v1 installation. It does not mean asking v1 to read v2
files.

回退是還原切換前的完整 v1 安裝，不是讓 v1 嘗試讀取 v2 檔案。

1. Stop the v2 server with `stop` and wait for saving to finish.
2. Make a separate copy of the current v2 `plugins/AceEconomy/` directory and any v2 database
   backup. Keep it for investigation; do not overwrite the v1 backup.
3. Move `AceEconomy-2.1.0.jar` and `AceLib-1.0.0.jar` out of the live `plugins/` directory.
4. Restore the pre-cutover v1 JARs, v1 configuration, and v1 data from the dated backup.
5. Start the server and confirm that the v1 data is readable before allowing players back in.

1. 用 `stop` 停止 v2 伺服器，等待儲存完成。
2. 另外複製目前 v2 的 `plugins/AceEconomy/` 資料夾與任何 v2 資料庫備份，留作調查；不要覆寫
   v1 備份。
3. 把 `AceEconomy-2.1.0.jar` 與 `AceLib-1.0.0.jar` 移出正式 `plugins/` 目錄。
4. 從有日期的備份還原 v1 JAR、v1 設定與 v1 資料。
5. 啟動伺服器，確認 v1 資料可讀後，才重新開放玩家。

Never copy `data-v2.json`, `data-v2.sqlite`, or a v2 snapshot into the v1 data location. Keep the
v2 copy until the rollback decision is closed.

絕對不要把 `data-v2.json`、`data-v2.sqlite` 或 v2 snapshot 複製到 v1 資料位置。回退決策正式結束
前，都要保留 v2 副本。

## After the upgrade / 升級後的日常維護

Keep the v1 backup, the first v2 backup, and the current v2 backup under separate names. Back up
`plugins/AceEconomy/` before changing storage or doing any data repair. For SQL storage, also keep
the database backup produced by the database administrator's normal process.

請用不同名稱分開保留 v1 備份、第一份 v2 備份與目前的 v2 備份。變更儲存方式或修復資料前，先備份
`plugins/AceEconomy/`；使用 SQL 儲存時，也要保留資料庫管理員平常產生的資料庫備份。

Use `/aceeco reload` for ordinary configuration and language changes. Use a full server restart
for a new JAR, a new AceLib, `storage.type`, SQLite path, MySQL connection values, or integration
plugin changes. Do not use Bukkit `/reload` as the upgrade procedure.

一般設定與語言檔變更使用 `/aceeco reload`。更換 JAR、AceLib、`storage.type`、SQLite 路徑、MySQL
連線值或整合插件時，請完整重啟伺服器。不要把 Bukkit `/reload` 當成升級流程。

For the operator checklist and regular maintenance rhythm, see [`operations.md`](operations.md).

日常檢查與維護節奏請參考 [`operations.md`](operations.md)。

## If v1 data must be carried forward / 如果必須延續 v1 資料

The product does not perform an automatic v1-to-v2 data migration. Do not improvise by editing
JSON, renaming files, or pointing v2 at the v1 storage location. Preserve the original backup and
raise a separate, explicitly scoped data conversion request with a reversible plan.

產品不會自動執行 v1 到 v2 的資料 migration。不要自行編輯 JSON、改檔名，或讓 v2 指向 v1 儲存位置。
請保留原始備份，另行提出範圍明確、可回復的資料轉換需求。
