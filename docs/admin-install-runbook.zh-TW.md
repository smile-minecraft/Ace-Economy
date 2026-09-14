# AceEconomy v2 安裝操作手冊

[English](admin-install-runbook.md) · [简体中文](admin-install-runbook.zh-CN.md) · 繁體中文

這本手冊寫給第一次安裝 AceEconomy v2 的伺服器管理員，從放好 JAR 一路帶到第一次上線前的檢查。

## 目錄

- [需要準備的環境](#需要準備的環境)
- [在維護時段安裝](#在維護時段安裝)
- [首次啟動不正常時](#首次啟動不正常時)
- [接下來閱讀](#接下來閱讀)

## 需要準備的環境

伺服器要用 Java 25，跑 Paper 或 Folia 26.1.2。AceEconomy 一定要配 `AceLib-1.2.1.jar` 才能啟動。Vault 和 PlaceholderAPI 是選用的，有裝才有對應功能，沒裝也能開服。SQLite 和 MySQL 需要的 JDBC 驅動已經包在 `AceEconomy-2.2.0.jar` 裡面，不用自己再找驅動。

Paper/Folia 26.1.2 是正式支援的版本。Folia 26.2 只有特定 build 通過驗證（VERIFIED-BETA），其他 26.2 build 還沒驗證過。

先準備好這兩個檔案：

```text
plugins/AceLib-1.2.1.jar
plugins/AceEconomy-2.2.0.jar
```

`plugins/` 裡面不要留 `AceLib-0.5.0-SNAPSHOT.jar` 或其他 AceLib。兩個 AceLib 同時存在，伺服器會分不清要用哪一個，可能開不乾淨。

### AceLib v1.2.1 下載與校驗和

`AceLib-1.2.1.jar` 請從 AceLib v1.2.1 的 GitHub Release 下載：<https://github.com/smile-minecraft/AceLib/releases/tag/v1.2.1>。

放進 `plugins/` 之前，先對一次官方公布的 SHA-256：

```text
2da9d21e6a81eb3086aac3dcf87ad11f6dbcbfef5d3c80263270b3f074dc1d6d  AceLib-1.2.1.jar
```

在本機算出 digest，一個字一個字比：

```text
shasum -a 256 AceLib-1.2.1.jar   # macOS
sha256sum AceLib-1.2.1.jar       # Linux
```

對不起來就不要裝這個 JAR。寧可重抓，也不要拿來路不明的檔案開服。

## 在維護時段安裝

下面七步要在玩家不在的時候做。先停服，裝完、測完再開門。

### 1. 停服並備份

先用平常停服的方式把 Minecraft 伺服器停下來。主控台指令是：

```text
stop
```

等程序完全結束、世界存檔寫完再動手。複製插件檔案之前，先把伺服器資料備份起來，至少要含完整的 `plugins/AceEconomy/` 資料夾。備份放在正式伺服器目錄外面，檔名標上日期。

全新安裝還沒有這個資料夾很正常。重點是開服前手上有一份能還原的備份。

### 2. 檢查相依插件

把正式 `plugins/` 裡舊的或重複的 AceLib 移走。如果那是舊安裝留下來的，就讓它留在備份裡。接著把 `AceLib-1.2.1.jar` 和 `AceEconomy-2.2.0.jar` 放進 `plugins/`。

有用到連動功能，才把 Vault 或 PlaceholderAPI 放進同一個 `plugins/` 目錄。沒裝這兩個，AceEconomy 照樣能啟動，不要當成安裝失敗。

### 3. 首次啟動並建立 v2 檔案

照平常方式啟動伺服器。第一次成功啟動後，AceEconomy 會在 `plugins/AceEconomy/` 建好 v2 的設定和語言檔。用預設 JSON 儲存的話，還會看到：

```text
plugins/AceEconomy/config.yml
plugins/AceEconomy/lang/en_US.yml
plugins/AceEconomy/lang/zh_TW.yml
plugins/AceEconomy/lang/zh_CN.yml
plugins/AceEconomy/data-v2.json
```

想用 SQLite 的話，要在建資料庫的那次啟動前先把 `storage.type: sqlite` 寫好。預設檔案是 `plugins/AceEconomy/data-v2.sqlite`。

### 4. 設定儲存方式與伺服器行為

停服狀態下打開 `plugins/AceEconomy/config.yml`。這份必須是含 `version: "2.0"` 的 v2 設定。v2 認得的儲存寫法只有下面幾種。

JSON 是預設值，不用填連線資訊：

```yaml
storage:
  type: json
```

SQLite 的檔案一定要放在插件資料夾裡面：

```yaml
storage:
  type: sqlite
  sqlite:
    path: data-v2.sqlite
```

用 MySQL 或 MariaDB 的話，密碼只寫在伺服器本機，啟動前把佔位文字換掉：

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

`pool-size` 和 `max-lifetime` 一定要放在 `storage.mysql` 下面。驅動已經在插件裡面了，不要再丟 MySQL 或 SQLite 的驅動 JAR 進 `plugins/`。

裝機時先顧好儲存就好。`settings.locale`、`start-balance`、`currencies.*`、`economy.allow-negative-balance`、`economy.default-debt-limit` 和 `leaderboard.*` 可以晚點再調，意思都在設定指南裡。密碼和 webhook 網址不要寫進會外流的文件。

### 5. 再次啟動並查看主控台

存檔後重新啟動伺服器。在主控台找到含 `AceEconomy v2.2.0` 的啟用訊息，確認伺服器有進到平常能接玩家的狀態。同時看一下，啟用的 AceLib 只有一個版本。

如果 AceEconomy 自己停用了，先不要放玩家進來。把第一個錯誤和附近 AceEconomy／AceLib 的主控台內容留下來，再照[故障排除指南](troubleshooting.zh-TW.md)往下查。

### 6. 執行管理員基本檢查

能在主控台跑的指令就在主控台跑，只能玩家用的指令就開測試玩家跑。下面就是 v2 正式的指令長相，照著打：

```text
/money balance
/baltop top
/aceeco give <player> <amount> [currency]
/aceeco take <player> <amount> [currency]
/aceeco set <player> <amount> [currency]
/aceeco history [player] [currency] [page]
/aceeco reload
```

`/aceeco rollback <transaction-id>` 故意不放在上面的例行檢查裡。它會把一筆已記錄的交易整個復原，屬於具破壞性、只能在主控台執行的管理操作：要同時有 `aceeconomy.admin` 和 `aceeconomy.admin.rollback`、手上有有效的交易 UUID，還要有人點頭或事先演練過才能打，不能拿來當自動化或隨手測試。它會先擋掉玩家和格式錯的 UUID；成功會回報 reversal 稽核紀錄 ID，已經回滾過的交易算明確的空操作，標記寫入失敗就先停下來人工核對。

再用測試玩家跑：

```text
/pay send <player> <amount> [currency]
/withdraw cash <amount> [currency]
/bank open
```

`/aceeco reload` 請從主控台打，會重新載入設定和語言檔。成功會回 `AceEconomy reloaded`。換過插件 JAR、AceLib、儲存後端或資料庫連線資訊，還是要整台重啟，只做重新載入不夠。

### 7. 開放玩家進入

啟用訊息、預期的儲存檔案或資料庫連線、基本指令都正常了，才開門。公告之前，先拿測試玩家查一次餘額，再做一筆小額轉帳。

開門之後，把有日期的安裝前備份和 v2 設定備份留好。含密碼或 webhook 網址的副本不要蓋到共用位置。

## 首次啟動不正常時

先照症狀翻 [`troubleshooting.md`](troubleshooting.zh-TW.md)。動資料之前，先看這四件事：

- `AceLib-1.2.1.jar` 有放，而且沒有舊版 AceLib 同時啟用。
- `config.yml` 有 `version: "2.0"`，`storage.type` 是有效值。
- SQLite 路徑還在 `plugins/AceEconomy/` 底下。
- MySQL 密碼和 webhook 網址只寫在本機，沒貼到工單或公開文章。

第一次啟動失敗，不要直接刪 `data-v2.json`、SQLite 檔案或資料庫。先複製留底；刪資料是救災時的決定，不是安裝步驟。

## 接下來閱讀

- [伺服器維運](operations.zh-TW.md)：日常備份、重新載入、重啟與整合管理。
- [AceEconomy v2.2.0 發布說明](release-v2.2.0.zh-TW.md)：版本需求與 v2 功能總覽。
