# AceEconomy v2 安裝操作手冊

[English](admin-install-runbook.md) · [简体中文](admin-install-runbook.zh-CN.md) · 繁體中文

本手冊給負責 Paper 或 Folia 伺服器的管理員使用，目的是把 AceEconomy v2 安全地放上線，不用猜檔案放哪裡。全新安裝請照本頁操作；從 v1 更換時，請改看 [`upgrade-from-v1.md`](upgrade-from-v1.zh-TW.md)。

## 目錄

- [需要準備的環境](#需要準備的環境)
- [在維護時段安裝](#在維護時段安裝)
- [首次啟動不正常時](#首次啟動不正常時)
- [接下來閱讀](#接下來閱讀)

## 需要準備的環境

伺服器需要使用 Java 25，並執行 Paper 或 Folia 26.1.2。AceEconomy 必須搭配 `AceLib-1.0.0.jar`；Vault 和 PlaceholderAPI 都是選用整合。SQLite 與 MySQL 的 JDBC 驅動程式已經包含在 `AceEconomy-2.1.0.jar`，不需要另外下載驅動程式 JAR。

請先準備以下兩個插件檔案：

```text
plugins/AceLib-1.0.0.jar
plugins/AceEconomy-2.1.0.jar
```

`plugins/` 不要留下 `AceLib-0.5.0-SNAPSHOT.jar` 或其他 AceLib 版本。兩個 AceLib 版本同時存在，可能造成相依性判定不明，讓伺服器無法乾淨啟動。

## 在維護時段安裝

### 1. 停服並備份

請使用平常的伺服器主控台或服務管理方式停止 Minecraft 伺服器。主控台指令是：

```text
stop
```

請等程序結束、世界儲存完成後再操作。複製插件檔案前，先備份整個伺服器資料，至少要包含完整的 `plugins/AceEconomy/` 資料夾。備份請放在正式伺服器目錄之外，並標上日期。

全新安裝時這個資料夾可能還不存在，這沒有問題。重點是正式啟動前要有一份可以還原的伺服器備份。

### 2. 檢查相依插件

請從正式 `plugins/` 目錄移走舊版或重複的 AceLib；如果它們屬於舊安裝，仍要保留在備份裡。接著在 `plugins/` 放入 `AceLib-1.0.0.jar` 與 `AceEconomy-2.1.0.jar`。

如果要使用整合功能，再把 Vault 及／或 PlaceholderAPI 放到同一個 `plugins/` 目錄。少了這些選用插件時 AceEconomy 仍可啟動，不要把它們不存在當成安裝失敗。

### 3. 首次啟動並建立 v2 檔案

請用平常的方式啟動伺服器。AceEconomy 首次成功啟動後，會在 `plugins/AceEconomy/` 建立 v2 設定與語言檔。使用預設 JSON 儲存時，還會建立：

```text
plugins/AceEconomy/config.yml
plugins/AceEconomy/lang/en_US.yml
plugins/AceEconomy/lang/zh_TW.yml
plugins/AceEconomy/lang/zh_CN.yml
plugins/AceEconomy/data-v2.json
```

使用 SQLite 時，請在要建立資料庫的那次啟動前設定 `storage.type: sqlite`。預設檔案是 `plugins/AceEconomy/data-v2.sqlite`。

### 4. 設定儲存方式與伺服器行為

請在停服時開啟 `plugins/AceEconomy/config.yml`。檔案必須是含有 `version: "2.0"` 的 v2 設定，不要把 v1 的 `config-version` 區塊貼進來。以下是 v2 支援的儲存設定格式。

JSON 是預設值，不需要連線資訊：

```yaml
storage:
  type: json
```

SQLite 的資料庫檔案必須放在插件資料夾內：

```yaml
storage:
  type: sqlite
  sqlite:
    path: data-v2.sqlite
```

使用 MySQL 或 MariaDB 時，密碼只放在伺服器本機，啟動前再替換預留位置：

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

`pool-size` 與 `max-lifetime` 必須放在 `storage.mysql` 底下。插件本身已提供 JDBC 驅動程式，不要再把 MySQL 或 SQLite 驅動程式放進 `plugins/`。

另外可以設定 `settings.locale`、`start-balance`、`currencies.*`、`economy.allow-negative-balance`、`economy.default-debt-limit` 與 `leaderboard.*`。上面的範例只列出安裝時需要的儲存設定；密碼與 webhook 網址不要放進共用文件。

### 5. 再次啟動並查看主控台

儲存設定後重新啟動伺服器。請在主控台找出包含 `AceEconomy v2.1.0` 的啟用訊息，並確認伺服器能繼續進入平常的可服務狀態。同時確認啟用的 AceLib 只有一個版本。

如果 AceEconomy 自行停用，先不要開放玩家進入。保留第一個錯誤以及附近的 AceEconomy／AceLib 主控台內容，再依照[故障排除指南](troubleshooting.zh-TW.md)往下檢查。

### 6. 執行管理員基本檢查

以下指令中，能在主控台執行的請從主控台執行；限定玩家的指令請用測試玩家執行。下面列出的完整子指令格式就是 v2 的正式指令集。

```text
/money balance
/baltop top
/aceeco give <player> <amount> [currency]
/aceeco take <player> <amount> [currency]
/aceeco set <player> <amount> [currency]
/aceeco history [player] [currency] [page]
/aceeco reload
```

`/aceeco rollback` 刻意不列入上方的例行檢查清單。它是具破壞性、僅限主控台的管理操作：必須同時具備 `aceeconomy.admin` 與 `aceeconomy.admin.rollback`、持有有效的交易 UUID，並經人工核准或專門演練才能執行；不得當成自動化或隨手的初步檢查。

`/aceeco rollback <transaction-id>` 也可從主控台執行。它是具破壞性的管理操作，會復原一筆已記錄的交易，因此不要拿來做例行安裝檢查，留給事故處理使用。它需要同時擁有 `aceeconomy.admin` 與 `aceeconomy.admin.rollback`，會事先拒絕玩家與無效 UUID；成功時回報 reversal 稽核紀錄 ID，已回滾的交易視為明確的空操作，標記寫入失敗則要求先人工核對。

再用測試玩家執行：

```text
/pay send <player> <amount> [currency]
/withdraw cash <amount> [currency]
/bank open
```

`/aceeco reload` 應由主控台執行，會重新載入設定與語言檔。成功時會回報 `AceEconomy reloaded`。修改插件 JAR、AceLib、儲存後端或資料庫連線資訊後，仍然必須完整重啟伺服器。

### 7. 開放玩家進入

請在啟用訊息、預期的儲存檔案或資料庫連線，以及基本指令都正常後，才開放伺服器給玩家。正式公告前，先用一個測試玩家查詢餘額並完成一筆小額轉帳。

開放伺服器後，請保留有日期的安裝前備份與 v2 設定備份。不要把含有密碼或 webhook 網址的副本覆寫到共用位置。

## 首次啟動不正常時

請依症狀查閱 [`troubleshooting.md`](troubleshooting.zh-TW.md)。在動資料之前，先檢查以下幾點：

- `AceLib-1.0.0.jar` 已存在，而且沒有舊版 AceLib JAR 同時啟用。
- `config.yml` 含有 `version: "2.0"`，且 `storage.type` 是有效值。
- SQLite 路徑仍在 `plugins/AceEconomy/` 底下。
- MySQL 密碼與 webhook 網址只在本機設定，沒有貼到工單或公開文章。

首次啟動失敗時，不要直接刪除 `data-v2.json`、SQLite 檔案或資料庫。先複製保留；刪除資料是復原決策，不是一般安裝步驟。

## 接下來閱讀

- [`upgrade-from-v1.md`](upgrade-from-v1.zh-TW.md)：更換 v1 安裝並保留安全的回退路徑。
- [`operations.md`](operations.zh-TW.md)：日常備份、重新載入、重啟與整合管理。
- [`release-v2.1.0.md`](release-v2.1.0.zh-TW.md)：版本需求與 v2 功能總覽。
