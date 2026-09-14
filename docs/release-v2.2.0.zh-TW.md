# AceEconomy v2.2.0 發布說明

[English](release-v2.2.0.md) · [简体中文](release-v2.2.0.zh-CN.md) · 繁體中文

AceEconomy v2.2.0 是 v2 伺服器線的正式發布版本。這版新增在地化語系檔、Discord 通知、插件自有的 SQL 連線池與斷線恢復、SQL 排行榜快照與帳戶讀取快取、Vault 名稱型 API、Essentials/CMI 匯入指令、基岩版訊息降級與原生銀行表單、PlaceholderAPI 佔位符、銀行支票兌回、可設定的銀行 GUI，以及純顯示的貨幣 reload。基線是 Java 25 與 Paper/Folia 26.1.2，這是正式支援的伺服器線；Folia 26.2 僅在特定 build 上通過驗證（VERIFIED-BETA）。`AceLib-1.2.1.jar` 是必要相依套件，插件 artifact 為 `AceEconomy-2.2.0.jar`。這版已發布為 GitHub Release `v2.2.0`：<https://github.com/smile-minecraft/Ace-Economy/releases/tag/v2.2.0>。

這份文件寫給要決定是否安裝或升級的伺服器管理員與維護者：說明這版包含什麼、怎麼驗證下載的檔案，以及發布前的驗證涵蓋了哪些範圍。

## 目錄

- [現在能不能安裝](#現在能不能安裝)
- [基線與發布狀態](#基線與發布狀態)
- [驗證發布檔案](#驗證發布檔案)
- [v2.2.0 多了什麼](#v220-多了什麼)
- [實作了什麼、背後有什麼證據](#實作了什麼背後有什麼證據)
- [Folia 實機重測](#folia-實機重測)
- [發布驗證](#發布驗證)
- [安裝或升級前](#安裝或升級前)
- [明確不宣稱的事](#明確不宣稱的事)

## 現在能不能安裝

可以。v2.2.0 是已發布的正式版本：

- 從 GitHub Release `v2.2.0` 下載 `AceEconomy-2.2.0.jar`：<https://github.com/smile-minecraft/Ace-Economy/releases/tag/v2.2.0>。同一個 Release 也附上 slim、sources、javadoc 三種 JAR 與 `SHA256SUMS`。
- `plugins/` 裡只留一份 `AceLib-1.2.1.jar` 跟插件放在一起。AceLib 是啟動的必要條件。
- 首次安裝或升級照[管理員安裝手冊](admin-install-runbook.zh-TW.md) 進行，並依[驗證發布檔案](#驗證發布檔案) 用已發布的 `SHA256SUMS` 核對下載的 JAR。

## 基線與發布狀態

| 項目 | v2.2.0 值 |
| --- | --- |
| Java | 25 |
| 伺服器 | Paper/Folia 26.1.2（正式支援線）；Folia 26.2 僅在特定 build 上通過驗證（VERIFIED-BETA） |
| 必要相依性 | `AceLib v1.2.1` |
| repo 建置版本 | `2.2.0` |
| 插件 artifact | `AceEconomy-2.2.0.jar` |
| GitHub Release / tag | `v2.2.0`（已發布） |
| 設定 schema | `version: "2.0"` |
| 發布日期 | 2026-09-14 |

基線跟已發布的 v2.1.0 一樣。v2.2.0 的變動在插件功能面，不在 Java 或伺服器基線。

## 驗證發布檔案

v2.2.0 已發布為 GitHub Release `v2.2.0`。Publish Release workflow 會附上 full、slim、sources、javadoc 四種 JAR，以及 `SHA256SUMS` asset。請以 Release 上的這份 `SHA256SUMS` 為準：把它放在下載的檔案旁邊，驗證不帶路徑的檔名項目：

```text
sha256sum -c SHA256SUMS
```

macOS 上可以這樣算本地摘要：

```text
shasum -a 256 AceEconomy-2.2.0.jar
```

把第一欄跟 `SHA256SUMS` 裡 `AceEconomy-2.2.0.jar` 的項目比對後，再把插件放到正式伺服器。根目錄的 `SHA256SUMS` 是本地建置紀錄，不是發布附件，不要拿它來驗證；也不要拿舊版本複製來的值代替這次比對。

## v2.2.0 多了什麼

以下範圍是這版涵蓋的內容。每一項都寫出它依賴的設定或指令，方便你對照參考文件。

- **在地化訊息** — `lang/<locale>.yml` 提供 `en_US`、`zh_TW`、`zh_CN` 三種語系，變數寫成 `{placeholder}`，呈現用 MiniMessage，使用者輸入會被轉義。
- **Discord 通知** — 交易成立後以非同步、盡力而為的方式送出 embed，由 `discord.enabled` 與 `discord.webhook-url` 設定。傳送失敗不會改動已成立的經濟結果。
- **SQL 連線池與斷線恢復** — `storage.mysql.pool-size` 與 `storage.mysql.max-lifetime` 交給插件自有的嚴格連線池。清理步驟失敗的借用連線會被丟棄而不是再借出去；閒置連線過期或已關閉時，下一次借用會換一條新的。
- **SQL 排行榜查詢與帳戶讀取快取** — `/baltop` 讀取快照（`leaderboard.cache-time-seconds`），`AccountBalanceCache` 讓 Vault 的同步讀取不碰儲存；快取未命中時回傳安全預設值 `0.0`，不會阻塞。
- **Vault 名稱型 API** — `hasAccount(String)`、`getBalance(String)`、`has`、`depositPlayer`、`withdrawPlayer`、`createPlayerAccount` 會用線上玩家與快取的離線紀錄來解析名稱，比對不分大小寫。UUID 仍是帳戶鍵，玩家改名後帳戶不變；呼叫執行緒上不會做任何儲存或網路 I/O。
- **Essentials/CMI 匯入** — 僅限主控台的 `/aceeco import <essentials|cmi> <path> [currency] [apply confirm]`。少了精確的 `apply confirm` 組合只會預演；來源限定在插件控管的 `import/` 目錄；執行前會做安全備份；重跑時略過已套用的紀錄。
- **基岩版訊息降級與原生銀行表單** — 裝了 Floodgate 時，聊天訊息的點擊動作會換成 `message.bedrock.fallback.*` 的提示文字，`/bank open` 也改送原生表單而不是箱子選單。兩種介面共用同一條存款／提款路徑。
- **PlaceholderAPI 佔位符** — `aceeco` 命名空間，包含原始與格式化餘額、`rank`、`top_name`、`top_balance`，以及 `currency_name`／`currency_symbol`。排名與排行榜數值讀的是跟 `/baltop` 相同的快照，不會觸發資料庫查詢。
- **銀行支票兌回** — 拿著有效的支票按右鍵，會走跟介面按鈕相同的原子入帳路徑。重播會被拒絕；無效或入帳失敗時物品留在玩家身上。
- **可設定銀行 GUI** — `bank-gui` 區段設定標題鍵、介面大小與各欄位動作。重新載入成功時會先關閉已開啟的介面，不會有半舊半新的規則被點到。
- **貨幣 reload** — `/aceeco reload` 會即時套用純顯示變更（`name`、`symbol`）；新增、移除貨幣或改動 `scale`／`default` 會被拒絕並附上原因，需要重啟。

指令與權限細節見 [指令與權限](commands.zh-TW.md)；設定見[設定指南](config.zh-TW.md)；整合行為見[整合功能](integrations.zh-TW.md) 與[整合 API](integration-api.zh-TW.md)。

## 實作了什麼、背後有什麼證據

這張表把兩件容易混在一起的事分開：已經發布的程式碼，以及它背後的證據。涵蓋實機檢查的發布前驗證記在[發布驗證](#發布驗證)。

| 範圍 | 已實作 | 證據 |
| --- | --- | --- |
| 在地化訊息 | 三份語系檔、`{placeholder}` 變數、MiniMessage、使用者輸入轉義 | 資源合約與語言覆蓋測試 |
| Discord 通知 | 非同步盡力而為的 embed；長度上限與祕密遮蔽 | notifier、payload 與 transport 測試 |
| SQL 連線池與斷線恢復 | 插件自有嚴格連線池；不安全的借用丟棄、過期連線汰換 | 連線池、生命週期與 SQL 後端測試；本次 Folia 重測在受控 MySQL 8.4 服務上完成連線、寫入、讀取與重啟持久化 |
| SQL 排行榜與讀取快取 | `/baltop` 快照；Vault 讀取用的 `AccountBalanceCache` | 排行榜與餘額快取測試 |
| Vault 名稱型 API | 名稱方法用線上與離線快取紀錄解析 | Vault provider 與名稱查詢測試 |
| Essentials/CMI 匯入 | 僅限主控台、路徑閘門、匯入前備份、可重複執行 | parser、路徑閘門、TOCTOU 與有界讀取測試 |
| 基岩版降級與銀行表單 | 點擊動作降級；原生表單共用存提路徑 | 基岩降級與表單路由測試 |
| PlaceholderAPI 佔位符 | `aceeco` 命名空間，含 `rank` 與 `top_*` | resolver 與 expansion 測試 |
| 銀行支票兌回 | 右鍵兌回走原子入帳路徑 | 兌回與支票 schema 測試 |
| 可設定銀行 GUI | `bank-gui` 的標題、大小與動作；reload 時關閉舊介面 | 設定 parser 與 GUI session 測試；本次 Folia 重測在真實 Java 客戶端完成呈現，以及 `DEPOSIT`、`WITHDRAW`、`CLOSE`、重新開啟與無支票的存款路徑 |
| 貨幣 reload | 純顯示變更即時套用；結構變更被拒絕並附原因 | reload 計畫與貨幣交易測試 |
| 權限與發送者規則 | 每個指令都宣告發送者與權限政策 | 指令合約測試；本次 Folia 重測觀察到主控台限定的發送者拒絕 |

自動化測試與原始碼靜態檢查不等於實機驗證；單靠這些無法涵蓋的實機檢查，記在[發布驗證](#發布驗證)。

## Folia 實機重測

我們在測試服對 v2.2.0 的 artifact 做了一輪有界的 Folia 實機重測。伺服器是 Folia `26.2-7`、Java 25，`storage.type` 設為 `mysql`，連到受控的 MySQL `8.4` 服務。一名 Java 玩家在沒有其他玩家的伺服器上連線，跑完下列檢查；收尾時用主控台 `/aceeco set` 把測試餘額校回起始值，並關閉介面。

這輪重測涵蓋 Java 銀行介面、權限與發送者規則，以及 MySQL 的連線／寫入／讀取／重啟後持久化。

**本次重測通過**

- 插件啟用 — 主控台啟用記錄顯示 `AceEconomy 2.2.0` 與 `AceLib 1.2.1` 都已載入並啟用，PlaceholderAPI 也把 `aceeco` 擴充註冊為 `2.2.0`。
- 餘額顯示 — `/money` 與 `/baltop top` 顯示出這名玩家的餘額與排名。
- 銀行支票往返 — `/withdraw cash 100` 產生一張 v2 支票，右鍵使用後兌回成功。聊天確認兌換完成，餘額回到提領前的金額。
- 銀行介面呈現 — `/bank open` 開啟 Java 的 `Generic_9x3` 銀行選單，`DEPOSIT`（slot `4`）、`WITHDRAW`（slot `11`、`13`）與 `CLOSE`（slot `15`）按鈕都連同 lore 一起顯示出來。
- 銀行介面提款 — 點 `WITHDRAW` 成功產生一張新的支票。
- 銀行介面存款 — 先 `/withdraw cash 100`，再開 `/bank open`；讀到當次 active inventory id 後點 `DEPOSIT`（slot `4`），關閉介面，`/money` 顯示暫時測試餘額已回到提領前的值。
- 銀行介面關閉與重開 — 以當次 active inventory id 點 `CLOSE`（slot `15`）後介面關閉，inventories list 已不再列出它，之後再 `/bank open` 成功重開。
- 手上沒有支票時存款 — 手上沒有支票時重開 `/bank open`，點 `DEPOSIT`（slot `4`）；關閉後 `/money` 仍是同一個暫時測試餘額，證明無支票路徑沒有入帳。
- 僅限主控台的發送者拒絕 — 玩家執行 `/aceeco reload` 得到 `subcommand is console-only: reload`。
- MySQL 寫入／讀取／重啟持久化 — 伺服器以 `storage.type: mysql` 連到本機受控的 MySQL `8.4` 服務，該服務回應 `SELECT 1` 探測。主控台 `/aceeco set` 寫入一筆暫時測試餘額，`/money` 讀回；接著做一次非破壞性重啟，重連後讀回同一個值。收尾時已把測試餘額校回起始值。

這輪重測是 v2.2.0 的有界執行期證據；其餘發布前檢查的結果記在[發布驗證](#發布驗證)。

## 發布驗證

v2.2.0 的發布關卡都已關閉。發布前，維護者完成了自動化證據無法涵蓋的發布前驗證：

- 真人玩家權限與發送者矩陣。
- 真人基岩版客戶端：原生表單、降級提示、取消與重新開啟。
- MySQL/MariaDB 剩餘項目：並行 transfer、邏輯快照／還原、斷線恢復，以及 recovery 與故障注入。
- v2.1.0 沿用的檢查：JSON 跨程序競爭、實體／原生資料庫備份，以及真實資料還原。

v2.2.0 也在 Folia `26.2-7`、Java 25 與 AceLib 1.2.1 上通過有界實機重測；各項檢查與觀察到的結果記在[Folia 實機重測](#folia-實機重測)。

## 安裝或升級前

1. 安裝已發布的 v2.2.0，流程照[管理員安裝手冊](admin-install-runbook.zh-TW.md)。
2. 維持 Java 25 與 Paper/Folia 26.1.2 基線，`plugins/` 裡只留一份 `AceLib-1.2.1.jar`。
3. `config.yml` 維持 `version: "2.0"`。改動貨幣結構或只在啟動時讀取的設定，仍需重啟而不是 reload。
4. 任何變更前先備份插件資料夾或資料庫，先在副本上驗證。

## 明確不宣稱的事

- 這裡不提供任何測試數量或覆蓋率數字。
- Essentials/CMI 匯入不是自動遷移，仍是一道明確的、僅限主控台的指令。
- 邏輯備份指令不能代替 `mysqldump`、`mariadb-dump` 或資料庫管理員的實體復原流程。
- 不支援 Vault 銀行方法，Vault 一律使用設定的預設貨幣。
- 沒有分世界餘額；world-name 參數會被接受但忽略。

安裝、日常維運與復原請用[管理員安裝手冊](admin-install-runbook.zh-TW.md)、[伺服器維運](operations.zh-TW.md) 與[持久化、備份與還原](persistence.zh-TW.md)。蒐集證據或回報問題時，密碼、token、webhook URL、資料檔與備份都要保密。
