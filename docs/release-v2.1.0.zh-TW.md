# AceEconomy v2.1.0 發布說明

[English](release-v2.1.0.md) · [简体中文](release-v2.1.0.zh-CN.md) · 繁體中文

AceEconomy v2.1.0 擴充 v2 伺服器功能，加入維運歷史與 rollback 指令、管理式 logical backup 與 restore、可設定貨幣與指令轉送、銀行票據與銀行 GUI 操作，以及 JSON/SQLite 持久化路徑。發布基線是 Java 25 與 Paper/Folia 26.1.2。`AceLib-1.0.0.jar` 是必要的 runtime dependency，預期插件 artifact 為 `AceEconomy-2.1.0.jar`。

本文供發布操作員與維護者使用，說明已實作的指令與 persistence surface，以及目前可取得的有界 runtime evidence。不宣稱尚未完成的真實玩家、正式資料庫、客戶端 GUI、跨程序或復原 gate 已通過。

## 目錄

- [發布基線](#發布基線)
- [本版本包含什麼](#本版本包含什麼)
  - [維運、歷史與 rollback](#維運歷史與-rollback)
  - [備份與復原](#備份與復原)
  - [動態貨幣與設定](#動態貨幣與設定)
  - [銀行票據、GUI 操作與指令轉送](#銀行票據gui-操作與指令轉送)
  - [持久化](#持久化)
- [安裝、升級與回退](#安裝升級與回退)
  - [全新安裝](#全新安裝)
  - [從 v1 更換](#從-v1-更換)
  - [發布回退](#發布回退)
- [驗證發布檔案](#驗證發布檔案)
- [有界 Folia runtime evidence](#有界-folia-runtime-evidence)
- [尚未完成的驗證 gate](#尚未完成的驗證-gate)
- [明確非目標](#明確非目標)

安裝與日常維運請使用 [`admin-install-runbook.zh-TW.md`](admin-install-runbook.zh-TW.md)、[`operations.zh-TW.md`](operations.zh-TW.md) 與 [`troubleshooting.zh-TW.md`](troubleshooting.zh-TW.md)。從 v1 更換請使用 [`upgrade-from-v1.zh-TW.md`](upgrade-from-v1.zh-TW.md)，完整指令與持久化參考見 [`commands.zh-TW.md`](commands.zh-TW.md) 與 [`persistence.zh-TW.md`](persistence.zh-TW.md)。

## 發布基線

| 項目 | v2.1.0 值 |
| --- | --- |
| Java | 25 |
| Paper/Folia | 26.1.2 |
| 必要相依性 | `AceLib-1.0.0.jar` |
| 插件 artifact | `AceEconomy-2.1.0.jar`（預期檔名） |
| AceLib config schema | `version: "2.0"` |

config schema 維持 `2.0`；本版本沒有引入 `version: "2.1"`。`plugins/` 內只保留一個相容的 AceLib JAR。Vault 與 PlaceholderAPI 仍是選用整合，文件所述儲存路徑使用的 JDBC drivers 由插件 artifact 提供。

## 本版本包含什麼

### 維運、歷史與 rollback

- `/aceeco history [player] [currency] [page]` 提供唯讀、由新到舊的交易歷史。頁碼從 `0` 開始，文件定義每頁 `10` 筆。
- `/aceeco rollback <transaction-id>` 從主控台回滾一筆已記錄的交易。它需要 `aceeconomy.admin` 與 `aceeconomy.admin.rollback`，會在查詢前驗證交易 UUID，並回報成功、already-reverted、typed failure 與 marker-persistence 結果。
- 已回滾的交易是安全 no-op。若 marker persistence 失敗，效果可能已發生但沒有持久化紀錄；重試前請檢查儲存並人工核對。

完整指令與權限表見 [`commands.zh-TW.md`](commands.zh-TW.md)。rollback 路徑已實作並有自動化 contract tests 覆蓋，但 live Folia/Bukkit bridge、live database 路徑與真實資料故障演練仍是開放 gate，見[尚未完成的驗證 gate](#尚未完成的驗證-gate)。

| 指令 | 執行者 | 權限 |
| --- | --- | --- |
| `/aceeco history [player] [currency] [page]` | 玩家或主控台 | `aceeconomy.admin` + `aceeconomy.admin.history` |
| `/aceeco reload` | 僅限主控台 | `aceeconomy.admin` + `aceeconomy.admin.reload` |
| `/aceeco rollback <transaction-id>` | 僅限主控台 | `aceeconomy.admin` + `aceeconomy.admin.rollback` |
| `/aceeco backup [label]` | 玩家或主控台 | `aceeconomy.admin` + `aceeconomy.admin.backup` |
| `/aceeco restore <backup-id> confirm` | 僅限主控台，且不可有線上玩家 | `aceeconomy.admin` + `aceeconomy.admin.restore` |
| `/withdraw cash <amount> [currency]` | 僅限玩家 | `aceeconomy.command.withdraw` |
| `/bank open` | 僅限玩家 | `aceeconomy.command.bank` |

這些是宣告的指令政策，不是 live sender 或 permission-denial 證據；後者仍是開放驗證 gate。

### 備份與復原

使用 canonical 管理式指令：

```text
/aceeco backup [label]
/aceeco restore <backup-id> confirm
```

`backup` 會在 `<plugin data folder>/backups` 下寫出 v2 logical JSON snapshot，讓 snapshot 與配對的 `.ready` marker 一起保存，且不會替換既有目標。snapshot 包含邏輯帳戶、餘額、交易、reverted markers 與已消耗 nonces，不包含資料庫密碼或 webhook URL。

`restore` 是破壞性操作，只能由主控台執行，需要 `aceeconomy.admin` 與 `aceeconomy.admin.restore`，有玩家在線時會拒絕，而且只接受小寫 `confirm`。它會先做 preflight 檢查，再建立 safety backup，之後才修改 live state。還原成功後，玩家回來前必須重啟伺服器，因為 session 與 GUI 不會熱刷新。

這些是應用程式層的 logical snapshots，不能取代 `mysqldump`、`mariadb-dump` 或資料庫管理員的 physical/disaster-recovery 流程，也沒有獨立的 `/backup` 或 `/restore` 根指令。

### 動態貨幣與設定

`currencies.*` map 由管理員定義。每種貨幣要提供 ID、顯示名稱、符號與 scale，而且必須恰好有一筆預設貨幣。貨幣 ID 會規範大小寫與前後空白；無效、重複、空白或格式錯誤的貨幣設定會阻止 partial startup。

`/aceeco reload` 會重新載入設定與語言檔；reload 失敗時保留最後一份有效的記憶體設定。它不會重新註冊指令，也不會重建只在啟動時建立的 currency 與 alias registries。修改 plugin JAR、AceLib、storage backend 或連線設定、貨幣，或主要指令 alias 後請重啟。

### 銀行票據、GUI 操作與指令轉送

`/withdraw cash <amount> [currency]` 會建立 v2 銀行票據。`/bank open` 會開啟銀行介面。文件定義的 GUI action contract 包含 slot `4` 的 `DEPOSIT`、slots `11` 與 `13` 的 `WITHDRAW`，以及 slot `15` 的 `CLOSE`。有效銀行票據會在移除物品或減少 stack 前完成入帳並防止 replay；票據無效、重播或入帳失敗時，物品會保留在玩家物品欄。

command registry 會把 `plugin.yml` 宣告的 aliases 轉送到 canonical roots：`/balance` 與 `/bal` 轉送到 `/money`，`/balancetop` 與 `/top` 轉送到 `/baltop`，`/menu` 與 `/bankmenu` 轉送到 `/bank`。`settings.main-command-alias` 設定額外的管理員 root alias，預設為 `aceeco`。Alias 只在啟動時生效，與其他 declared command label 衝突時會拒絕啟動。不包含右鍵兌回銀行票據。

### 持久化

文件中的 v2 backend 包含 JSON、SQLite，以及供 MySQL/MariaDB 使用的 MySQL-compatible 設定。JSON 使用 `data-v2.json`；SQLite 使用插件資料夾內設定的路徑；MySQL/MariaDB 使用 `storage.type: mysql` 與 `storage.mysql.*`。JSON 與 SQLite persistence paths 已有 schema、restart、snapshot 與 transaction boundaries 的自動化覆蓋；目前 release evidence 不把這些覆蓋等同於 live MySQL/MariaDB 或 JSON 跨程序已核准。

## 安裝、升級與回退

### 全新安裝

1. 停止伺服器，在 live server directory 外建立有日期且可還原的副本；若已有 `plugins/AceEconomy/`，請完整納入。
2. 把 `AceLib-1.0.0.jar` 與預期的 `AceEconomy-2.1.0.jar` 放進 `plugins/`，不要讓其他 AceLib 版本並存。
3. 先啟動一次建立 v2 檔案，再確認啟用中的 `plugins/AceEconomy/config.yml` 包含 `version: "2.0"`。
4. 選擇 JSON、SQLite 或設定好的 MySQL-compatible backend。資料庫密碼與 webhook URL 只保留在本機。
5. 再次啟動，檢查 enable messages 並執行適用的 operator checks。完整流程見 [`admin-install-runbook.zh-TW.md`](admin-install-runbook.zh-TW.md)。

### 從 v1 更換

v2 是 clean-slate 安裝，不會自動 migration v1 設定或資料。不得把 v1 檔案改名為 `data-v2.json`，也不能把它載入 v2 backend。請保留完整的 pre-cutover v1 安裝作為回退來源；依照 [`upgrade-from-v1.zh-TW.md`](upgrade-from-v1.zh-TW.md)，不要把 v1 檔案複製到 v2。

### 發布回退

從 v2.1.0 回到 v1 時，停止 v2，另外保留目前 v2 資料副本，把 v2 JAR 移出 `plugins/`，再從有日期的備份還原 v1 JAR、設定與資料。啟動 v1 並確認資料可讀後，才能讓玩家回來。絕對不要讓 v1 讀取 `data-v2.json`、`data-v2.sqlite` 或 v2 snapshot。

## 驗證發布檔案

v2.1.0 已作為 GitHub Release `v2.1.0`（發布 commit `2bb86c4`）發布。Publish Release workflow 會附帶 full、slim、sources、javadoc 四種 JAR，以及 `SHA256SUMS` asset。請以這個已發布的 `SHA256SUMS` 為準：把它放在下載的 artifact 旁邊，並驗證裸檔名項目：

```text
sha256sum -c SHA256SUMS
```

macOS 可使用以下命令計算本機 digest：

```text
shasum -a 256 AceEconomy-2.1.0.jar
```

把第一欄與 `SHA256SUMS` 中 `AceEconomy-2.1.0.jar` 項目比對後，再把插件放到正式伺服器。不要用舊版本複製的值取代這項比對。

## 有界 Folia runtime evidence

目前的有界證據同時涵蓋了 Folia `26.1.2-8` 與 Folia `26.2-4`。兩次都使用同一份 v2.0.0 artifact，範圍包括啟動與 plugin enable、AceLib capability、status/health、RCON route/help 與 typed errors、宣告的 aliases、backup/restore confirmation 與 safety-backup paths，以及 reload/restart 行為。

這是有界 runtime evidence，不是 production certification。不代表真實玩家經濟操作、live MySQL/MariaDB 行為、GUI render 或 click、JSON 多程序安全、實體資料庫備份復原或故障注入復原已成功。

## 尚未完成的驗證 gate

以下項目明確屬於 not-run 或仍開放。未來發布或 operator acceptance record 必須提供相應 live evidence，才能把受影響路徑視為 production-ready。

- **Player sender 與 permission denial**——尚未執行。驗證玩家／主控台限定 sender 拒絕、缺少 root permission 與缺少 child permission。
- **GUI render 與 click**——尚未執行。使用真實客戶端開啟 bank GUI，驗證畫面與 `DEPOSIT`、`WITHDRAW`、`CLOSE` clicks。
- **Live MySQL/MariaDB**——尚未執行。連線目標服務，驗證啟動、寫入、讀取、重啟與 logical snapshot 路徑。
- **JSON cross-process race**——尚未執行。執行多程序競爭測試；同程序 atomic file 行為不代表跨程序保證。
- **Physical/native backup**——插件尚未執行。SQL 正式維運需另外驗證資料庫管理員的 native backup/restore 流程；邏輯指令不能取代它。
- **Real-data recovery 與 fault injection**——尚未執行。使用代表性資料與受控故障，驗證復原、marker 處理與必要的人工核對路徑。
- **Real-player history 與 rollback paths**——部分路徑尚未執行。使用真實玩家帳戶演練 history 查詢與受控 rollback，包含 transfer-counterpart 與 persistence-failure 情境。

## 明確非目標

- 不包含自動 v1 migration。
- 不包含 Essentials/CMI import。
- 不包含 native database dump replacement。
- 不包含右鍵兌回銀行票據。
- 不包含獨立的 `/backup` 與 `/restore` 根指令；請使用 `/aceeco` 子指令。

發布範圍與維運邊界也記錄在 [`operations.zh-TW.md`](operations.zh-TW.md)、[`persistence.zh-TW.md`](persistence.zh-TW.md) 與 [`cutover.zh-TW.md`](cutover.zh-TW.md)。蒐集 evidence 或回報問題時，密碼、token、webhook URL、資料檔與備份都要保密。
