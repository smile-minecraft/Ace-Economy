# AceEconomy v2.0.0 release / AceEconomy v2.0.0 發布說明

AceEconomy v2.0.0 is the v2 server release for Java 25 and Paper/Folia 26.1.2. It uses
`AceLib-1.0.0.jar` as a required dependency and ships as `AceEconomy-2.0.0.jar`.

AceEconomy v2.0.0 是給 Java 25 與 Paper/Folia 26.1.2 使用的 v2 伺服器版本。它需要
`AceLib-1.0.0.jar`，插件檔案是 `AceEconomy-2.0.0.jar`。

For a first installation, start with [`admin-install-runbook.md`](admin-install-runbook.md). For a
v1 replacement, use [`upgrade-from-v1.md`](upgrade-from-v1.md). Daily maintenance is covered in
[`operations.md`](operations.md).

全新安裝請先閱讀 [`admin-install-runbook.md`](admin-install-runbook.md)；從 v1 更換請使用
[`upgrade-from-v1.md`](upgrade-from-v1.md)；日常維護請參考 [`operations.md`](operations.md)。

## What is included / 本版本包含什麼

- JSON, SQLite, and MySQL/MariaDB storage for v2 data.
- JSON、SQLite 與 MySQL/MariaDB 三種 v2 資料儲存方式。
- Multiple currencies, starting balances, debt limits, transfers, administrative balance changes,
  transaction records, banknotes, the bank menu, and balance leaderboards.
- 多貨幣、起始餘額、債務限制、轉帳、管理員餘額調整、交易記錄、銀行支票、銀行選單與餘額排行榜。
- Optional Vault and PlaceholderAPI integration.
- 選用的 Vault 與 PlaceholderAPI 整合。
- Optional Discord transaction notifications using a local webhook setting.
- 使用本機 webhook 設定的選用 Discord 交易通知。
- English, Traditional Chinese, and Simplified Chinese language files.
- English、繁體中文與簡體中文語言檔。

## Files and dependencies / 檔案與相依性

Put these files in `plugins/`:

請把以下檔案放在 `plugins/`：

```text
AceLib-1.0.0.jar
AceEconomy-2.0.0.jar
```

AceLib is required. Vault and PlaceholderAPI are optional and are detected when enabled. SQLite and
MySQL JDBC drivers are included in the AceEconomy JAR; no additional driver file is required.

AceLib 是必要相依插件。Vault 與 PlaceholderAPI 是選用插件，啟用後才會被使用。SQLite 與 MySQL
JDBC driver 已包含在 AceEconomy JAR，不需要額外放置 driver 檔案。

Do not keep `AceLib-0.5.0-SNAPSHOT.jar` or another AceLib version beside v2.

不要讓 `AceLib-0.5.0-SNAPSHOT.jar` 或其他 AceLib 版本與 v2 同時存在。

## Configuration and data / 設定與資料

The active configuration is `plugins/AceEconomy/config.yml` with `version: "2.0"`. JSON is the
default backend and uses `plugins/AceEconomy/data-v2.json`. SQLite uses the file named by
`storage.sqlite.path` under the plugin data folder. MySQL/MariaDB uses the `storage.mysql.*` block.

啟用中的設定是 `plugins/AceEconomy/config.yml`，版本欄位為 `version: "2.0"`。JSON 是預設
backend，使用 `plugins/AceEconomy/data-v2.json`。SQLite 使用 `storage.sqlite.path` 指定的
插件資料夾內檔案。MySQL/MariaDB 使用 `storage.mysql.*` 區塊。

v1 configuration and data are not migrated automatically. A v1 file must not be renamed to a v2
file. Keep the complete pre-upgrade backup if a rollback may be needed.

v1 設定與資料不會自動 migration。不能只把 v1 檔案改名成 v2 檔案。若可能需要回退，請保留完整的
升級前備份。

The installation and operations guides above contain the settings and backup steps needed by a
server administrator. Keep passwords and webhook URLs as local values; public examples must use
placeholders.

上面的安裝與維運指南已列出伺服器管理員需要的設定與備份步驟。密碼與 webhook URL 只放在本機；公開
範例一律使用 placeholder。

## Commands / 指令

The v2 commands use these explicit forms:

v2 指令使用以下明確格式：

| Command | Use / 用途 |
|---|---|
| `/money balance [player] [currency]` | Check a balance / 查詢餘額 |
| `/pay send <player> <amount> [currency]` | Transfer funds / 轉帳 |
| `/withdraw cash <amount> [currency]` | Create a banknote / 提領銀行支票 |
| `/baltop top [currency]` | Show the leaderboard / 顯示排行榜 |
| `/bank open` | Open the bank menu / 開啟銀行選單 |
| `/aceeco give <player> <amount> [currency]` | Add balance / 增加餘額 |
| `/aceeco take <player> <amount> [currency]` | Remove balance / 扣除餘額 |
| `/aceeco set <player> <amount> [currency]` | Set balance / 設定餘額 |
| `/aceeco reload` | Reload config and language files from the console / 從主控台重新載入設定與語言檔 |

`/aceeco reload` is not a replacement for a restart after changing a plugin JAR, AceLib, storage
backend, database connection, or optional plugin set.

修改插件 JAR、AceLib、儲存 backend、資料庫連線或選用插件組合後，`/aceeco reload` 不能取代完整重啟。

## Upgrade and rollback / 升級與回退

Stop the server, back up the complete v1 installation, install the v2 JAR pair, and create a v2
configuration. Do not point v2 at v1 storage. If rollback is required, stop v2, preserve a copy of
the v2 data, and restore the pre-upgrade v1 JARs, configuration, and data from the dated backup.

請停服並備份完整 v1 安裝，再放入 v2 JAR 組合與 v2 設定。不要讓 v2 指向 v1 儲存。需要回退時，
先停下 v2、保留 v2 資料副本，再從有日期的備份還原升級前的 v1 JAR、設定與資料。

The full procedure is [`upgrade-from-v1.md`](upgrade-from-v1.md). Do not copy `data-v2.json`,
`data-v2.sqlite`, or a v2 snapshot into a v1 data location.

完整流程請看 [`upgrade-from-v1.md`](upgrade-from-v1.md)。不要把 `data-v2.json`、`data-v2.sqlite`
或 v2 snapshot 複製到 v1 資料位置。

## Verify the release file / 驗證發布檔案

When a `SHA256SUMS` asset is supplied with the release, place it beside
`AceEconomy-2.0.0.jar` and verify the bare filename entry:

發布同時提供 `SHA256SUMS` 時，請把它與 `AceEconomy-2.0.0.jar` 放在同一個目錄，並驗證裸檔名項目：

```text
sha256sum -c SHA256SUMS
```

On macOS, calculate the value with:

macOS 可使用：

```text
shasum -a 256 AceEconomy-2.0.0.jar
```

Compare the first column with the `AceEconomy-2.0.0.jar` line in `SHA256SUMS` before placing the
file on the live server.

把輸出的第一欄與 `SHA256SUMS` 中 `AceEconomy-2.0.0.jar` 那一行比對，確認後再放到正式伺服器。
