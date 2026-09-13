# AceEconomy v2.1.0 發布說明

[English](release-v2.1.0.md) · [简体中文](release-v2.1.0.zh-CN.md) · 繁體中文

v2.1.0 在 v2 伺服器功能的基礎上，多了交易歷史查詢與回溯、管理式邏輯備份與還原、可自訂貨幣與指令轉送、銀行票據與銀行介面操作，以及 JSON/SQLite 持久化路徑。發布基線是 Java 25 與 Paper/Folia 26.1.2，這是正式支援的伺服器線；Folia 26.2 只在特定 build 上通過驗證（VERIFIED-BETA），其他 26.2 build 尚未驗證。`AceLib-1.2.0.jar` 是必要的執行時期相依套件，預期的插件檔案是 `AceEconomy-2.1.0.jar`。

這份文件寫給要決定是否升級、如何安裝與維護的伺服器管理員：先確認環境合不合，再看這版多了什麼、怎麼裝，最後看哪些已經驗證、哪些還沒驗證。還沒通過的驗證關卡不會說成已經通過。

## 目錄

- [這版跑在什麼環境](#這版跑在什麼環境)
- [這版多了什麼](#這版多了什麼)
  - [查歷史與回溯交易](#查歷史與回溯交易)
  - [備份與還原](#備份與還原)
  - [貨幣與設定](#貨幣與設定)
  - [銀行票據、銀行介面與指令轉送](#銀行票據銀行介面與指令轉送)
  - [資料存在哪裡](#資料存在哪裡)
- [安裝、升級與回退](#安裝升級與回退)
  - [全新安裝](#全新安裝)
  - [從 v1 更換](#從-v1-更換)
  - [發布回退](#發布回退)
- [上線前先驗證檔案](#上線前先驗證檔案)
- [已經驗證到哪裡](#已經驗證到哪裡)
- [還沒驗證什麼](#還沒驗證什麼)
- [明確不做的事](#明確不做的事)

安裝與日常維運請用 [`admin-install-runbook.zh-TW.md`](admin-install-runbook.zh-TW.md)、[`operations.zh-TW.md`](operations.zh-TW.md) 與 [`troubleshooting.zh-TW.md`](troubleshooting.zh-TW.md)。從 v1 更換請用 [`upgrade-from-v1.zh-TW.md`](upgrade-from-v1.zh-TW.md)，完整指令與持久化參考見 [`commands.zh-TW.md`](commands.zh-TW.md) 與 [`persistence.zh-TW.md`](persistence.zh-TW.md)。

## 這版跑在什麼環境

| 項目 | v2.1.0 值 |
| --- | --- |
| Java | 25 |
| Paper/Folia | 26.1.2 |
| 必要相依性 | `AceLib-1.2.0.jar` |
| 插件 artifact | `AceEconomy-2.1.0.jar`（預期檔名） |
| AceLib config schema | `version: "2.0"` |

設定 schema 維持 `2.0`；這版沒有引入 `version: "2.1"`。`plugins/` 裡只留一個相容的 AceLib JAR。請從 <https://github.com/smile-minecraft/AceLib/releases/tag/v1.2.0> 下載 `AceLib-1.2.0.jar`，安裝前先核對它的 SHA-256 `da9f196b47c2b28c6db443d102236b27c1a1bbdf7dd3e7c22470170420935278`；實際命令見 [`admin-install-runbook.zh-TW.md`](admin-install-runbook.zh-TW.md)。Vault 與 PlaceholderAPI 還是選用整合，文件裡儲存路徑用到的 JDBC drivers 由插件檔案提供。

## 這版多了什麼

### 查歷史與回溯交易

- `/aceeco history [player] [currency] [page]` 查唯讀的交易歷史，由新排到舊。頁碼從 `0` 開始，文件定義每頁 `10` 筆。
- `/aceeco rollback <transaction-id>` 從主控台回溯一筆已記錄的交易。它需要 `aceeconomy.admin` 與 `aceeconomy.admin.rollback`，查詢前會先驗證交易 UUID，並回報成功（附這筆回溯的稽核紀錄 ID）、`already-reverted`（這筆已經回溯過）、`typed failure`（有明確類型的失敗回報），以及回溯標記有沒有寫進儲存。
- 已經回溯過的交易再送一次是安全無效果的（`no-op`）。如果標記沒寫進儲存，效果可能已經發生卻沒有留下紀錄；重試前請先檢查儲存並人工核對。

完整指令與權限表見 [`commands.zh-TW.md`](commands.zh-TW.md)。回溯路徑已經實作，也有自動化合約測試覆蓋，但實機 Folia/Bukkit 橋接、正式資料庫路徑與真實資料故障演練都還沒驗證，見[還沒驗證什麼](#還沒驗證什麼)。

主要指令政策如下：

| 指令 | 執行者 | 權限 |
| --- | --- | --- |
| `/aceeco history [player] [currency] [page]` | 玩家或主控台 | `aceeconomy.admin` + `aceeconomy.admin.history` |
| `/aceeco reload` | 僅限主控台 | `aceeconomy.admin` + `aceeconomy.admin.reload` |
| `/aceeco rollback <transaction-id>` | 僅限主控台 | `aceeconomy.admin` + `aceeconomy.admin.rollback` |
| `/aceeco backup [label]` | 玩家或主控台 | `aceeconomy.admin` + `aceeconomy.admin.backup` |
| `/aceeco restore <backup-id> confirm` | 僅限主控台，且不可有線上玩家 | `aceeconomy.admin` + `aceeconomy.admin.restore` |
| `/withdraw cash <amount> [currency]` | 僅限玩家 | `aceeconomy.command.withdraw` |
| `/bank open` | 僅限玩家 | `aceeconomy.command.bank` |

這些只是宣告出來的指令政策，還不是在實機上驗證過的發送者或權限拒絕證據；後者仍是尚未完成的驗證關卡。

### 備份與還原

請用標準的管理式指令做邏輯備份與還原：

```text
/aceeco backup [label]
/aceeco restore <backup-id> confirm
```

`backup` 會在 `<plugin data folder>/backups` 下寫出 v2 邏輯 JSON 快照，快照與配對的 `.ready` 就緒標記一起保存，而且不會蓋掉已有的目標。快照內容是邏輯帳戶、餘額、交易、已回溯標記與已消耗的一次性序號，不含資料庫密碼或 webhook URL。

`restore` 是破壞性操作，只能由主控台執行，需要 `aceeconomy.admin` 與 `aceeconomy.admin.restore`，有玩家在線會拒絕，而且只接受小寫 `confirm`。它會先做事前檢查，再建一份安全備份，之後才動到正式狀態。還原成功後，玩家回來前必須重啟伺服器，因為會話與介面不會熱刷新。

這些是應用程式層的邏輯快照，不能代替 `mysqldump`、`mariadb-dump` 或資料庫管理員的實體／災難復原流程，也沒有獨立的 `/backup` 或 `/restore` 根指令。

### 貨幣與設定

貨幣清單 `currencies.*` 由管理員定義。每種貨幣要給 ID、顯示名稱、符號與小數位數，而且必須恰好有一種預設貨幣。貨幣 ID 會統一大小寫並去掉前後空白；無效、重複、空白或格式錯誤的貨幣設定會讓插件停在啟動階段，不會以不完整狀態上線。

`/aceeco reload` 會重新載入設定與語言檔；重新載入失敗時保留最後一份有效的記憶體設定。它不會重新註冊指令，也不會重建只在啟動時建立的貨幣與別名登錄表。換過插件 JAR、AceLib、儲存後端或連線設定、貨幣，或管理員主指令別名之後請重啟。

### 銀行票據、銀行介面與指令轉送

`/withdraw cash <amount> [currency]` 會建立一張 v2 銀行票據。`/bank open` 會開啟銀行介面。文件定義的銀行介面操作合約（GUI，約定各欄位點擊行為）包含 slot `4` 的 `DEPOSIT`、slots `11` 與 `13` 的 `WITHDRAW`，以及 slot `15` 的 `CLOSE`。有效的銀行票據會先入帳並防止重播，之後才移除物品或減少堆疊；票據無效、重播或入帳失敗時，物品會留在玩家物品欄。

指令登錄表會把 `plugin.yml` 宣告的別名轉送到標準主指令：`/balance` 與 `/bal` 轉送到 `/money`，`/balancetop` 與 `/top` 轉送到 `/baltop`，`/menu` 與 `/bankmenu` 轉送到 `/bank`。`settings.main-command-alias` 設定額外的管理員主指令別名，預設為 `aceeco`。別名只在啟動時生效，跟其他已宣告指令標籤衝突時會拒絕啟動。不包含右鍵兌回銀行票據。

### 資料存在哪裡

文件裡的 v2 後端包含 JSON、SQLite，以及給 MySQL/MariaDB 用的 MySQL 相容設定。JSON 用 `data-v2.json`；SQLite 用插件資料夾內設定好的路徑；MySQL/MariaDB 用 `storage.type: mysql` 與 `storage.mysql.*`。JSON 與 SQLite 的持久化路徑已有自動化測試覆蓋 schema、重啟、快照與交易邊界；目前的發布證據不等於正式 MySQL/MariaDB 或 JSON 跨程序已經核准。

## 安裝、升級與回退

### 全新安裝

1. 停服，在正式伺服器目錄外建一份有日期、可還原的副本；已經有 `plugins/AceEconomy/` 的話請完整納入。
2. 把 `AceLib-1.2.0.jar` 與預期的 `AceEconomy-2.1.0.jar` 放進 `plugins/`，不要讓其他 AceLib 版本留在旁邊。
3. 先啟動一次建立 v2 檔案，再確認啟用中的 `plugins/AceEconomy/config.yml` 包含 `version: "2.0"`。
4. 選 JSON、SQLite 或設定好的 MySQL 相容後端。資料庫密碼與 webhook URL 只留在本機。
5. 再次啟動，檢查啟用訊息並執行適用的管理員檢查。完整流程見 [`admin-install-runbook.zh-TW.md`](admin-install-runbook.zh-TW.md)。

### 從 v1 更換

v2 是全新乾淨安裝（不沿用舊資料），不會自動遷移 v1 設定或資料。不得把 v1 檔案改名成 `data-v2.json`，也不能把它載入 v2 後端。請保留完整的切換前 v1 安裝作為回退來源；照著 [`upgrade-from-v1.zh-TW.md`](upgrade-from-v1.zh-TW.md) 做，不要把 v1 檔案複製到 v2。

### 發布回退

要從 v2.1.0 回到 v1 時，先停掉 v2，另外留一份目前 v2 資料的副本，把 v2 JAR 移出 `plugins/`，再從有日期的備份還原 v1 JAR、設定與資料。啟動 v1 並確認資料可讀之後，才能讓玩家回來。絕對不要讓 v1 去讀 `data-v2.json`、`data-v2.sqlite` 或 v2 快照。

## 上線前先驗證檔案

v2.1.0 已經作為 GitHub Release `v2.1.0`（發布 commit `2bb86c4`）發布。Publish Release workflow 會附上 full、slim、sources、javadoc 四種 JAR，以及 `SHA256SUMS` asset。請以這份已發布的 `SHA256SUMS` 為準：把它放在下載的檔案旁邊，驗證不帶路徑的檔名項目：

```text
sha256sum -c SHA256SUMS
```

macOS 可用以下命令計算本機的值：

```text
shasum -a 256 AceEconomy-2.1.0.jar
```

把第一欄跟 `SHA256SUMS` 裡 `AceEconomy-2.1.0.jar` 的項目比對後，再把插件放到正式伺服器。不要拿舊版本複製來的值代替這次比對。

## 已經驗證到哪裡

目前的有界執行時期證據同時涵蓋 Folia `26.1.2-8` 與 Folia `26.2-4`。兩次都用同一份 v2.0.0 artifact，範圍包括啟動與插件啟用、AceLib capability、狀態與健康檢查、RCON 路由與說明及有明確類型的錯誤、已宣告的別名、備份與還原的確認流程及安全備份路徑，以及重新載入與重啟行為。

這是有界的執行時期證據，不是正式上線認證。它不代表真實玩家經濟操作、正式 MySQL/MariaDB 行為、介面渲染或點擊、JSON 多程序安全、實體資料庫備份還原或故障注入還原已經成功。

## 還沒驗證什麼

以下項目明確屬於未執行或仍開放。未來發布或管理員驗收紀錄必須補上對應的實機證據，才能把受影響的路徑視為可上正式環境。

- **玩家發送者與權限拒絕**——尚未執行。驗證玩家限定／主控台限定的發送者拒絕、缺少主權限與缺少子權限。
- **介面渲染與點擊**——尚未執行。用真實客戶端開啟銀行介面，驗證畫面與 `DEPOSIT`、`WITHDRAW`、`CLOSE` 點擊。
- **正式 MySQL/MariaDB**——尚未執行。連到目標服務，驗證啟動、寫入、讀取、重啟與邏輯快照路徑。
- **JSON 跨程序競爭**——尚未執行。執行多程序競爭測試；同程序的原子寫檔行為不代表跨程序保證。
- **實體／原生備份**——插件尚未執行。SQL 正式維運要另外驗證資料庫管理員的原生備份與還原流程；邏輯指令不能代替它。
- **真實資料還原與故障注入**——尚未執行。用代表性資料與受控故障，驗證還原、標記處理與必要的人工核對路徑。
- **真實玩家歷史與回溯路徑**——部分路徑尚未執行。用真實玩家帳戶演練歷史查詢與受控回溯，包含轉帳對應方（`transfer-counterpart`）與持久化失敗（`persistence-failure`）情境。

## 明確不做的事

- 不包含自動 v1 遷移。
- 不包含 Essentials/CMI import。
- 不包含原生資料庫傾印替代方案。
- 不包含右鍵兌回銀行票據。
- 不包含獨立的 `/backup` 與 `/restore` 根指令；請用 `/aceeco` 子指令。

發布範圍與維運邊界也記在 [`operations.zh-TW.md`](operations.zh-TW.md)、[`persistence.zh-TW.md`](persistence.zh-TW.md) 與 [`cutover.zh-TW.md`](cutover.zh-TW.md)。蒐集證據或回報問題時，密碼、token、webhook URL、資料檔與備份都要保密。
