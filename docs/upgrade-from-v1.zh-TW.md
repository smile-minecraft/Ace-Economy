# 從 AceEconomy v1 升級

[English](upgrade-from-v1.md) · [简体中文](upgrade-from-v1.zh-CN.md) · 繁體中文

這份指南寫給手上有 v1、想換成 v2 的管理員。先講清楚：v2 是全新的安裝，不是直接在舊資料上改。`version: "2.0"`、v2 的儲存檔案或資料表、v2 的插件 API，都和 v1 分開。v1 的設定、資料和 API 不會自己轉過去。

## 目錄

- [這次升級會改變什麼](#這次升級會改變什麼)
- [操作正式伺服器前](#操作正式伺服器前)
- [執行切換](#執行切換)
- [回退](#回退)
- [升級後的維護](#升級後的維護)
- [如果必須延續 v1 資料](#如果必須延續-v1-資料)

## 這次升級會改變什麼

v2 要 Java 25、Paper/Folia 26.1.2，還有 `AceLib-1.2.1.jar`。插件本體是 `AceEconomy-2.1.0.jar`。Vault 和 PlaceholderAPI 還是選用的，有裝才連動。Paper/Folia 26.1.2 是正式支援的版本；Folia 26.2 只有特定 build 通過驗證（VERIFIED-BETA），其他 26.2 build 還沒驗證。

指令也換了樣子。v2 一定要加子指令：`/money balance`、`/pay send`、`/withdraw cash`、`/baltop top`、`/bank open`，管理用 `/aceeco`。不要把 v1 才有的 history、rollback、import 寫法，或舊銀行券的說法，直接當 v2 指令來打。

如果服上還有人靠 v1 餘額在玩，在你點頭接受 v2 之前，請把整套 v1 留下來當回退來源。

## 操作正式伺服器前

1. 排一個維護時段，用平常停服的方式停服。Minecraft 主控台指令是 `stop`。
2. 做一份有日期、能還原的完整伺服器副本。至少要有 v1 的 `plugins/AceEconomy/` 目錄、正在用的 v1 設定、v1 的 AceEconomy JAR、目前的 AceLib JAR，還有把舊安裝救回來需要的伺服器資料。
3. 副本放在正式伺服器目錄外面，不要拿它當 v2 的工作目錄。

動手前先寫下來：v1 真正在用的到底是哪個檔案或資料庫。不要因為 `data-v2.json`、v1 的 JSON 檔和 SQL 資料庫裡都有餘額，就以為它們能互換。

## 執行切換

### 1. 從正式插件清單裡移除 v1

停服後，把舊的 AceEconomy JAR 和舊的 AceLib JAR 移出正式的 `plugins/` 目錄。讓它們留在有日期的備份裡，不要直接刪掉。`plugins/` 裡不要同時留兩個 AceLib 版本。

### 2. 放入 v2 插件組合

把下面兩個檔案放進正式的 `plugins/` 目錄：

```text
AceLib-1.2.1.jar
AceEconomy-2.1.0.jar
```

伺服器真的有用連動，才加 Vault 和 PlaceholderAPI。SQLite 或 MySQL 的 JDBC 驅動不用加，AceEconomy 的 JAR 裡已經有了。

### 3. 建立 v2 設定

讓 v2 自己生出 `plugins/AceEconomy/config.yml`，或拿你寫好的 v2 設定去換掉生出來的檔案。打開確認裡面有：

```yaml
version: "2.0"
```

貨幣、起始餘額、債務設定、語系、儲存方式、排行榜、選用的 Discord，都要重新填一次。不要複製 v1 的 `config-version`，也不要以為 v1 的貨幣名稱和限制會自己進來。

### 4. 選擇 v2 儲存方式

檔案型小服直接用 v2 預設的 JSON：

```yaml
storage:
  type: json
```

SQLite 會在插件資料夾裡用一個新的 v2 檔案：

```yaml
storage:
  type: sqlite
  sqlite:
    path: data-v2.sqlite
```

MySQL 或 MariaDB 走 v2 的 `storage.mysql.*` 設定。密碼只留在本機，切換前先請資料庫管理員照平常流程備份一次資料庫。

v2 的 JSON 快照有自己的 schema 版本。v1 的資料檔不是 v2 快照，不能改個名變成 `data-v2.json`，也不能直接塞進 v2 的儲存後端。

### 5. 啟動並設定 v2

啟動伺服器，等到 `AceEconomy v2.1.0` 出現。先確認選好的 v2 儲存有打開，再改生出來的設定。設定和語言檔的小修改從主控台打 `/aceeco reload`；動過插件檔案、AceLib 或儲存連線設定，就要整台重啟。

### 6. 開放玩家前檢查

拿測試帳號把下面每一條都走一次：

- `/money balance` 回的是預期的 v2 帳戶餘額。
- `/pay send <player> <amount> [currency]` 能完成一筆小額轉帳。
- 有開提領流程的話，`/withdraw cash <amount> [currency]` 能開出一張銀行券。
- `/baltop top [currency]` 和 `/bank open` 回應正常。
- 有開 Vault、PlaceholderAPI、Discord 的話，行為跟設定一致。

插件成功啟用，不代表 v1 的餘額搬過來了。要等管理員決定舊資料留或重建，v2 才算真正就緒。

## 回退

回退的意思是把切換前的 v1 整套還原回來，不是叫 v1 去讀 v2 的檔案。

1. 用 `stop` 停掉 v2 伺服器，等存檔寫完。
2. 把目前的 v2 `plugins/AceEconomy/` 目錄和 v2 資料庫備份另外複製一份，留著查問題；不要蓋掉 v1 的備份。
3. 把 `AceEconomy-2.1.0.jar` 和 `AceLib-1.2.1.jar` 移出正式的 `plugins/` 目錄。
4. 從有日期的備份裡，把切換前的 v1 JAR、v1 設定和 v1 資料還原回來。
5. 啟動伺服器，確認 v1 資料讀得到，才重新開門。

絕對不要把 `data-v2.json`、`data-v2.sqlite` 或任何 v2 快照複製到 v1 的資料位置。在回退決定結束前，把那份 v2 副本留好。

## 升級後的維護

v1 備份、第一份 v2 備份、目前的 v2 備份，請用不同名字分開留。改儲存方式或修資料之前，先備份 `plugins/AceEconomy/`。用 SQL 儲存的話，資料庫管理員照平常流程產生的資料庫備份也要留。

平常的設定和語言檔修改用 `/aceeco reload`。換新 JAR、新 AceLib、`storage.type`、SQLite 路徑、MySQL 連線值或整合插件之後，一定要整台重啟。不要拿 Bukkit 的 `/reload` 當升級流程。

日常管理清單請看[伺服器維運](operations.zh-TW.md)。

## 如果必須延續 v1 資料

v2 不會直接讀 v1 的檔案，v1 的檔案也不是 v2 快照。不要自己改 JSON、改檔名，或把 v2 指到 v1 的儲存位置。[操作正式伺服器前](#操作正式伺服器前)留下的原始備份不要動。

舊餘額真的要帶進 v2，請走匯入流程。只認兩種來源：EssentialsX 2.x 的玩家檔，以及整理好的 CMI 對帳檔，全部從伺服器主控台打：

1. 先把來源檔複製到 `plugins/AceEconomy/import/`。EssentialsX 就是帶 `money:` 欄位的 `<uuid>.yml` 玩家檔；CMI 就是整理好的 UTF-8 對帳檔，每行 `uuid,name,balance`。`import/` 以外的路徑一律不讀。
2. 先預演，不寫入，例如 `/aceeco import essentials userdata` 或 `/aceeco import cmi balances.csv`。預演報的路徑或格式問題先修好，再往下走。
3. 預演結果沒問題，才用精確的 `apply confirm` 寫入，例如 `/aceeco import essentials userdata apply confirm`。寫入前會先做一份 `pre-import` 安全備份；備份失敗就整批不寫。
4. 看 `applied` / `skipped` / `failed` 報告。只要有失敗就不算完全成功，請看失敗摘要，不要以為沒進去的都變成零。
5. 同一份來源重跑是安全的：已經進去的會回報為 `skipped`，重跑只會補上還沒進去的。

完整用法、權限與錯誤對照，見[指令](commands.zh-TW.md#匯入餘額aceeco-import)的「匯入餘額」一節。
