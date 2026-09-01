# 從 AceEconomy v1 升級

[English](upgrade-from-v1.md) · [简体中文](upgrade-from-v1.zh-CN.md) · 繁體中文

要把現有的 v1 安裝換成 AceEconomy v2，請使用本指南。v2 是一次全新的安裝，不是在 v1 資料結構上原地升級：`version: "2.0"`、v2 的儲存檔案或資料表，以及 v2 的插件 API，都和 v1 是分開的。v1 的設定、資料與 API 都不會自動轉換。

## 目錄

- [這次升級會改變什麼](#這次升級會改變什麼)
- [操作正式伺服器前](#操作正式伺服器前)
- [執行切換](#執行切換)
- [回退](#回退)
- [升級後的維護](#升級後的維護)
- [如果必須延續 v1 資料](#如果必須延續-v1-資料)

## 這次升級會改變什麼

v2 的執行環境需要 Java 25、Paper/Folia 26.1.2，以及 `AceLib-1.2.0.jar`。v2 的插件檔案是 `AceEconomy-2.1.0.jar`。Vault 與 PlaceholderAPI 仍然是選用整合。Paper/Folia 26.1.2 是正式支援的伺服器線；Folia 26.2 僅在特定 build 上通過驗證（VERIFIED-BETA），其餘 26.2 build 尚未驗證。

v2 的指令採用明確的子指令形式：`/money balance`、`/pay send`、`/withdraw cash`、`/baltop top`、`/bank open`，以及 `/aceeco` 的管理指令。不要把 v1 專用的 history、rollback、import，或者舊的銀行券資料說明，當成 v2 指令來用。

如果伺服器還需要 v1 的餘額，在伺服器管理員接受 v2 之前，請把完整的 v1 安裝保留下來作為回退來源。

## 操作正式伺服器前

1. 安排一個維護時段，用平常的主控台或服務管理方式停服。Minecraft 的主控台指令是 `stop`。
2. 做一份有日期、可以還原的完整伺服器副本。至少包含 v1 的 `plugins/AceEconomy/` 目錄、正在使用的 v1 設定、v1 的 AceEconomy JAR、目前的 AceLib JAR，以及還原舊安裝所需的伺服器資料。
3. 把副本放在正式伺服器目錄之外，不要把它當成 v2 檔案的工作目錄。

開始之前，先記清楚 v1 實際使用的權威儲存檔案或資料庫是哪一個。不要因為 `data-v2.json`、v1 的 JSON 檔與 SQL 資料庫裡都存有餘額，就以為它們可以互相替換。

## 執行切換

### 1. 從正式插件清單裡移除 v1

停服後，把舊的 AceEconomy JAR 與舊的 AceLib JAR 移出正式的 `plugins/` 目錄。把它們留在那份有日期的備份裡，不要直接刪掉。`plugins/` 裡不要同時留著兩個 AceLib 版本。

### 2. 放入 v2 插件組合

請把以下檔案放進正式的 `plugins/` 目錄：

```text
AceLib-1.2.0.jar
AceEconomy-2.1.0.jar
```

只有伺服器確實用到這些整合時，才加入 Vault 與 PlaceholderAPI。不要另外加 SQLite 或 MySQL 的 JDBC 驅動；兩者都已經包含在 AceEconomy 的 JAR 裡了。

### 3. 建立 v2 設定

讓 v2 自己生成 `plugins/AceEconomy/config.yml`，或者用你明確寫好的 v2 組態去替換生成出來的檔案。確認裡面含有：

```yaml
version: "2.0"
```

重新填寫貨幣、起始餘額、債務設定、語系、儲存方式、排行榜設定，以及選用的 Discord 設定。不要複製 v1 的 `config-version` 區塊，也不要假設 v1 的貨幣名稱與限制已經被匯入。

### 4. 選擇 v2 儲存方式

檔案型伺服器可以使用 v2 預設的 JSON：

```yaml
storage:
  type: json
```

SQLite 會在插件資料夾內使用一個新的 v2 檔案：

```yaml
storage:
  type: sqlite
  sqlite:
    path: data-v2.sqlite
```

MySQL 或 MariaDB 使用 v2 的 `storage.mysql.*` 設定。密碼只留在本機，並且在切換之前，按資料庫管理員平時的流程先完成一次資料庫備份。

v2 的 JSON 快照有自己的 schema 版本。v1 的資料檔不是 v2 快照，既不能只改個名變成 `data-v2.json`，也不能直接塞進 v2 的儲存後端。

### 5. 啟動並設定 v2

啟動伺服器，等待 `AceEconomy v2.1.0` 啟用。確認選定的 v2 儲存已經打開，再視需要修改生成出來的設定。設定與語言檔的變更，請從主控台執行 `/aceeco reload`；改動了插件檔案、AceLib 或儲存連線設定之後，則必須完整重啟。

### 6. 開放玩家前檢查

請用測試帳號確認：

- `/money balance` 能回傳預期的 v2 帳戶餘額。
- `/pay send <player> <amount> [currency]` 能完成一筆小額轉帳。
- 啟用該流程時，`/withdraw cash <amount> [currency]` 能開出一張銀行券。
- `/baltop top [currency]` 與 `/bank open` 回應正常。
- 已經啟用的 Vault、PlaceholderAPI 與 Discord，行為符合設定。

插件成功啟用，並不代表 v1 的餘額已經遷移過來。只有在管理員決定好舊資料要如何保留或重建之後，v2 才算真正準備完成。

## 回退

回退是指還原切換前的完整 v1 安裝，而不是讓 v1 去讀取 v2 的檔案。

1. 用 `stop` 停止 v2 伺服器，等待保存完成。
2. 另外複製一份目前的 v2 `plugins/AceEconomy/` 目錄與任何 v2 資料庫備份，留作調查；不要覆寫 v1 的備份。
3. 把 `AceEconomy-2.1.0.jar` 與 `AceLib-1.2.0.jar` 移出正式的 `plugins/` 目錄。
4. 從那份有日期的備份裡，還原切換前的 v1 JAR、v1 設定與 v1 資料。
5. 啟動伺服器，確認 v1 資料可讀之後，才重新開放玩家。

絕對不要把 `data-v2.json`、`data-v2.sqlite` 或任何 v2 快照，複製到 v1 的資料位置。在回退決策結束之前，都要保留那份 v2 副本。

## 升級後的維護

請用不同的名稱，分別保留 v1 備份、第一份 v2 備份，以及目前的 v2 備份。改動儲存方式或修復資料之前，先備份 `plugins/AceEconomy/`。使用 SQL 儲存時，也要保留資料庫管理員按平時流程產生的資料庫備份。

普通的設定與語言檔變更，使用 `/aceeco reload`。更換新 JAR、新 AceLib、`storage.type`、SQLite 路徑、MySQL 連線值或整合插件之後，必須完整重啟。不要把 Bukkit 的 `/reload` 當成升級流程。

日常管理清單請看[伺服器維運](operations.zh-TW.md)。

## 如果必須延續 v1 資料

本產品不會自動執行 v1 到 v2 的資料遷移。不要自己動手編輯 JSON、改檔名，或者讓 v2 指向 v1 的儲存位置。請保留原始備份，另外提出一份範圍明確、可以回退的資料轉換需求。
