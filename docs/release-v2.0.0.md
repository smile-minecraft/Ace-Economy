# AceEconomy v2.0.0

AceEconomy v2.0.0 是 AceLib 重寫版本。此版本以 Java 25、Folia/Paper API `26.1.2 build 74` 編譯，並已在 Folia `26.1.2-8` 與 `26.2-4` 真實伺服器完成 fresh-install smoke 驗證（詳見「這次測試」）。

## 安裝

安裝前先停服，並備份整個 `plugins/AceEconomy/`。最低必要檔案是：

- `plugins/AceEconomy-2.0.0.jar`
- `plugins/AceLib-1.0.0.jar`

`AceLib-0.5.0-SNAPSHOT` 不得和 `v1.0.0` 同時放在 `plugins/`。Vault 和 PlaceholderAPI 是可選整合；需要這些整合時才另外安裝。AceLib、Vault、PlaceholderAPI 的 classes 都不會打包進 AceEconomy JAR。SQLite 與 MySQL driver 已經打包在 shadow JAR 內，不需要再放 driver JAR。

啟動後查看 log，確認 AceEconomy v2.0.0 已啟用。使用預設 JSON 儲存時，`plugins/AceEconomy/data-v2.json` 應該建立；`plugins/AceEconomy/lang/` 應有 `en_US.yml`、`zh_TW.yml` 和 `zh_CN.yml`。若安裝了 Vault 或 PlaceholderAPI，再確認對應整合隨插件啟用。

## 儲存設定

v2.0.0 提供 JSON、SQLite 和 MySQL 三種 persistence。JSON 是預設值。SQLite 的簡短設定例子如下：

```yaml
storage:
  type: sqlite
  sqlite:
    path: data-v2.sqlite
```

要使用 MySQL，將 `storage.type` 設為 `mysql`，再在 `storage.mysql.*` 填入目標資料庫的連線設定。密碼和其他敏感資料不要放進發布文件或公開貼文。live MySQL 連線尚未測試。

JSON 與 SQL backend 使用同一種 v2 JSON snapshot 格式。`backup(OutputStream)` 和 `restore(InputStream)` 是 persistence API，不是 v2.0.0 的玩家指令。`restore` 會先完整解析並驗證 snapshot；格式錯誤或 `schemaVersion` 不相容時，會在寫入 live data 前拒絕輸入，現有資料保持不變。API 的格式與限制見 [`docs/persistence.md`](persistence.md)。

不要使用不存在的 `/backup`、`/restore`、`/aceeco backup` 或 `/aceeco restore` 指令。

## 指令與未納入功能

v2.0.0 的指令只有 `/money`、`/pay`、`/withdraw`、`/baltop`、`/bank` 和 `/aceeco`。管理員可用 `/aceeco reload` 重新載入設定。

Vault 和 PlaceholderAPI integration 已在 Folia `26.1.2-8` 與 `26.2` smoke test 中隨插件啟用。Essentials/CMI 匯入、History、Rollback 和 Import 的 production wiring 不在 v2.0.0；原始碼中的 service 或測試不能視為這些功能已可供生產環境使用。

## 回退

回退前先停服，保留目前的 v2 資料備份，不要先刪除 `data-v2.json`、SQLite 檔案或其他 v2 snapshot。接著移出 `AceEconomy-2.0.0.jar`，恢復上一版 AceEconomy 及其相容的 runtime，再用停服前的上一版資料與設定啟動。

v2 資料不會自動降回 v1，不能直接承諾舊版能讀取 v2 資料。若上一版使用不同的資料格式，先在副本還原並確認資料可讀，再動正式資料。沒有確認相容性前，不要讓舊版直接寫入 v2 資料。

## 這次測試

執行 `./gradlew test --rerun-tasks` 得到 291 tests、0 failures、0 errors；parser target test 為 23 tests、0 failures、0 errors。`clean build` 與 `shadowJar` 均成功。

Folia `26.2-4` smoke：JSON fresh start、restart、以 `storage.type: sqlite` 啟動 SQLite 並建立 `data-v2.sqlite`、enable/disable、RCON 六個 `help` 指令，以及 `aceeco reload`。

發布前最終驗證在 Folia `26.1.2-8` 全新世界完成同一套矩陣，並追加故障演練：JSON fresh start（自動生成 `config.yml`、三語系檔與 `data-v2.json`）、JSON restart、SQLite 啟動並建立含 `ace_v2_*` schema 的 `data-v2.sqlite`、RCON 六個指令回應與 `aceeco reload`、`baltop top` 對空資料的 typed 空結果。故障演練：移除 AceLib 時平台以 `UnknownDependencyException` 拒載、無半初始化服務且伺服器正常啟動；MySQL 指向不可達主機時啟動失敗會輸出 typed error 並自主 disable，不影響伺服器；graceful shutdown 以正確依賴順序反向 disable（AceEconomy 先於 AceLib）並完整存檔。

live MySQL 連線、玩家登入、GUI click 與真實 banknote 提領仍未在真實伺服器驗證，僅由單元與整合測試覆蓋。

## 發布前

發布前重新執行測試與 `shadowJar`，產生 `SHA256SUMS`，並在發布說明中保留「live MySQL、玩家登入、GUI click 未做真實伺服器驗證」的限制。GitHub tag、release 和 push 是否執行，由發布者明確決定。

`SHA256SUMS` 使用 release asset 的裸檔名 `AceEconomy-2.0.0.jar`。將 `SHA256SUMS` 與 JAR 下載到同一個目錄後，可執行 `sha256sum -c SHA256SUMS` 驗證；macOS 可用 `shasum -a 256 AceEconomy-2.0.0.jar`，再將輸出的第一欄與 `SHA256SUMS` 中的 checksum 比對。
