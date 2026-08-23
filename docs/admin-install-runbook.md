# AceEconomy v2 installation runbook / AceEconomy v2 安裝操作手冊

This runbook is for the person who looks after a Paper or Folia server and wants to put
AceEconomy v2 into service without guessing which file belongs where. Follow it for a fresh
installation, or use [`upgrade-from-v1.md`](upgrade-from-v1.md) when replacing a v1 server.

本手冊給負責 Paper 或 Folia 伺服器的管理員使用，目的在於把 AceEconomy v2 安全地放上線，
不用猜檔案放哪裡。全新安裝請照本頁操作；從 v1 更換時，請改看
[`upgrade-from-v1.md`](upgrade-from-v1.md)。

## What you need / 需要準備的環境

Use a Java 25 server running Paper or Folia 26.1.2. AceEconomy requires
`AceLib-1.0.0.jar`; Vault and PlaceholderAPI are optional. The SQLite and MySQL JDBC drivers
are already included in `AceEconomy-2.0.0.jar`, so do not download separate driver JARs.

伺服器需要使用 Java 25，並執行 Paper 或 Folia 26.1.2。AceEconomy 必須搭配
`AceLib-1.0.0.jar`；Vault 和 PlaceholderAPI 都是選用整合。SQLite 與 MySQL 的 JDBC driver
已經包含在 `AceEconomy-2.0.0.jar`，不需要另外下載 driver JAR。

Prepare these two plugin files:

請先準備以下兩個插件檔案：

```text
plugins/AceLib-1.0.0.jar
plugins/AceEconomy-2.0.0.jar
```

Do not leave `AceLib-0.5.0-SNAPSHOT.jar` or another AceLib version in `plugins/`. Two AceLib
versions can make the server report an ambiguous dependency and prevent a clean start.

`plugins/` 不要留下 `AceLib-0.5.0-SNAPSHOT.jar` 或其他 AceLib 版本。兩個 AceLib 版本同時存在
可能造成相依性判定不明，讓伺服器無法乾淨啟動。

## Install in a maintenance window / 在維護時段安裝

### 1. Stop the server and make a backup / 1. 停服並備份

Stop the Minecraft server from its normal console or service control. The console command is:

請使用平常的伺服器主控台或服務管理方式停止 Minecraft 伺服器。主控台指令是：

```text
stop
```

Wait until the process has exited and world saving has finished. Before copying plugin files,
make a backup of the server data and at least the complete `plugins/AceEconomy/` directory.
Keep that backup outside the live server directory and label it with the date.

請等程序結束、世界儲存完成後再操作。複製插件檔案前，先備份整個伺服器資料，至少要包含完整的
`plugins/AceEconomy/` 資料夾。備份請放在正式伺服器目錄之外，並標上日期。

On a fresh installation the directory may not exist yet. That is fine; the important part is to
have a restorable copy of the server before the first production start.

全新安裝時這個資料夾可能還不存在，這沒有問題。重點是正式啟動前要有一份可以還原的伺服器備份。

### 2. Check the dependency set / 2. 檢查相依插件

Remove old or duplicate AceLib files from the live `plugins/` directory, but keep them in the
backup if they belong to the previous installation. Place exactly `AceLib-1.0.0.jar` and
`AceEconomy-2.0.0.jar` in `plugins/`.

請從正式 `plugins/` 目錄移走舊版或重複的 AceLib；如果它們屬於舊安裝，仍要保留在備份裡。
接著在 `plugins/` 放入 `AceLib-1.0.0.jar` 與 `AceEconomy-2.0.0.jar`。

If you use integrations, place Vault and/or PlaceholderAPI in the same `plugins/` directory.
AceEconomy starts without either optional plugin, so do not treat their absence as an installation
failure.

如果要使用整合功能，再把 Vault 及／或 PlaceholderAPI 放到同一個 `plugins/` 目錄。少了這些選用
插件時 AceEconomy 仍可啟動，不要把它們不存在當成安裝失敗。

### 3. Start once and let v2 create its files / 3. 首次啟動並建立 v2 檔案

Start the server normally. On the first successful start, AceEconomy creates its v2 configuration
and language files under `plugins/AceEconomy/`. With the default JSON storage it also creates:

請用平常的方式啟動伺服器。AceEconomy 首次成功啟動後，會在 `plugins/AceEconomy/` 建立 v2 設定與
語言檔。使用預設 JSON 儲存時，還會建立：

```text
plugins/AceEconomy/config.yml
plugins/AceEconomy/lang/en_US.yml
plugins/AceEconomy/lang/zh_TW.yml
plugins/AceEconomy/lang/zh_CN.yml
plugins/AceEconomy/data-v2.json
```

For SQLite, set `storage.type: sqlite` before the start that should create the database. The
default file is `plugins/AceEconomy/data-v2.sqlite`.

使用 SQLite 時，請在要建立資料庫的那次啟動前設定 `storage.type: sqlite`。預設檔案是
`plugins/AceEconomy/data-v2.sqlite`。

### 4. Configure storage and server behaviour / 4. 設定儲存方式與伺服器行為

Open `plugins/AceEconomy/config.yml` while the server is stopped. The file must be a v2 file with
`version: "2.0"`; do not paste a v1 `config-version` block into it. The following are the storage
shapes supported by v2.

請在停服時開啟 `plugins/AceEconomy/config.yml`。檔案必須是含有 `version: "2.0"` 的 v2 設定，
不要把 v1 的 `config-version` 區塊貼進來。以下是 v2 支援的儲存設定格式。

JSON is the default and needs no connection details:

JSON 是預設值，不需要連線資訊：

```yaml
storage:
  type: json
```

SQLite keeps its file inside the plugin data folder:

SQLite 的資料庫檔案必須放在插件資料夾內：

```yaml
storage:
  type: sqlite
  sqlite:
    path: data-v2.sqlite
```

For MySQL or MariaDB, keep the password local and replace the placeholder before starting:

使用 MySQL 或 MariaDB 時，密碼只放在伺服器本機，啟動前再替換 placeholder：

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

`pool-size` and `max-lifetime` belong under `storage.mysql`. The plugin supplies the JDBC driver;
do not add a separate MySQL or SQLite driver to `plugins/`.

`pool-size` 與 `max-lifetime` 必須放在 `storage.mysql` 底下。插件本身已提供 JDBC driver，
不要再把 MySQL 或 SQLite driver 放進 `plugins/`。

You can also set `settings.locale`, `start-balance`, the `currencies.*` entries,
`economy.allow-negative-balance`, `economy.default-debt-limit`, and the `leaderboard.*` settings.
The snippets above show the storage keys that matter during installation; keep all secrets and
webhook URLs out of shared documents.

另外可以設定 `settings.locale`、`start-balance`、`currencies.*`、
`economy.allow-negative-balance`、`economy.default-debt-limit` 與 `leaderboard.*`。設定鍵的
安裝時需要的儲存設定鍵已列在上面的範例；密碼與 webhook URL 不要放進共用文件。

### 5. Start again and read the console / 5. 再次啟動並查看主控台

Start the server after saving the configuration. Look for an enable message containing
`AceEconomy v2.0.0`, and confirm that the server continues to its normal ready state. Also check
that there is only one `AceLib` version enabled.

儲存設定後重新啟動伺服器。請在主控台找出包含 `AceEconomy v2.0.0` 的啟用訊息，並確認伺服器能
繼續進入平常的可服務狀態。同時確認啟用的 AceLib 只有一個版本。

If AceEconomy disables itself, stop opening the server to players. Keep the first error and the
nearby AceEconomy/AceLib lines; the troubleshooting guide explains what to check next.

如果 AceEconomy 自行停用，先不要開放玩家進入。保留第一個錯誤以及附近的 AceEconomy／AceLib
主控台內容，再依照故障排除指南往下檢查。

### 6. Run the basic operator checks / 6. 執行管理員基本檢查

Run these from the server console where the command is console-safe, and use a test player for
player-only commands. The explicit subcommand forms below are the v2 command surface.

以下指令中，能在主控台執行的請從主控台執行；限定玩家的指令請用測試玩家執行。下面列出的完整
子指令格式就是 v2 的正式指令表面。

```text
/money balance
/baltop top
/aceeco give <player> <amount> [currency]
/aceeco take <player> <amount> [currency]
/aceeco set <player> <amount> [currency]
/aceeco reload
```

With a test player, also check:

再用測試玩家執行：

```text
/pay send <player> <amount> [currency]
/withdraw cash <amount> [currency]
/bank open
```

`/aceeco reload` is for the console and reloads the configuration and language files. A successful
reload reports `AceEconomy reloaded`. A full restart is still required after changing the plugin
JAR, AceLib, the storage backend, or database connection details.

`/aceeco reload` 應由主控台執行，會重新載入設定與語言檔。成功時會回報
`AceEconomy reloaded`。修改插件 JAR、AceLib、儲存 backend 或資料庫連線資訊後，仍然必須完整
重啟伺服器。

### 7. Open the server to players / 7. 開放玩家進入

Only open the server after the enable message, the expected storage file or database connection,
and the basic commands all look correct. Test one balance lookup and one small transfer before
announcing the server is ready.

請在啟用訊息、預期的儲存檔案或資料庫連線，以及基本指令都正常後，才開放伺服器給玩家。正式公告前，
先用一個測試玩家查詢餘額並完成一筆小額轉帳。

After opening the server, keep the dated pre-install backup and the v2 configuration backup. Do
not overwrite either one with a copy containing secrets in a shared location.

開放伺服器後，請保留有日期的安裝前備份與 v2 設定備份。不要把含有密碼或 webhook 的副本覆寫到
共用位置。

## If the first start does not look right / 首次啟動不正常時

Use [`troubleshooting.md`](troubleshooting.md) by symptom. In particular, check the following
before changing data:

請依症狀查閱 [`troubleshooting.md`](troubleshooting.md)。在動資料之前，先檢查以下幾點：

- `AceLib-1.0.0.jar` is present and no older AceLib JAR is active.
- `AceLib-1.0.0.jar` 已存在，而且沒有舊版 AceLib JAR 同時啟用。
- `config.yml` contains `version: "2.0"` and a valid `storage.type`.
- `config.yml` 含有 `version: "2.0"`，且 `storage.type` 是有效值。
- A SQLite path stays under `plugins/AceEconomy/`.
- SQLite 路徑仍在 `plugins/AceEconomy/` 底下。
- A MySQL password and webhook URL were set locally, not copied into a ticket or public post.
- MySQL 密碼與 webhook URL 只在本機設定，沒有貼到工單或公開文章。

Do not delete `data-v2.json`, a SQLite file, or a database simply because the first start failed.
Take a copy first; deletion is a recovery decision, not a routine installation step.

首次啟動失敗時，不要直接刪除 `data-v2.json`、SQLite 檔案或資料庫。先複製保留；刪除資料是
復原決策，不是一般安裝步驟。

## Next reading / 接下來閱讀

- [`upgrade-from-v1.md`](upgrade-from-v1.md): replace a v1 installation and keep a safe rollback path.
- [`upgrade-from-v1.md`](upgrade-from-v1.md)：更換 v1 安裝並保留安全的回退路徑。
- [`operations.md`](operations.md): routine backups, reloads, restarts, and integrations.
- [`operations.md`](operations.md)：日常備份、重新載入、重啟與整合管理。
- [`release-v2.0.0.md`](release-v2.0.0.md): version requirements and the v2 feature overview.
- [`release-v2.0.0.md`](release-v2.0.0.md)：版本需求與 v2 功能總覽。
