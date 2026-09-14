# AceEconomy v2.2.0 發布準備稿

[English](release-v2.2.0.md) · [简体中文](release-v2.2.0.zh-CN.md) · 繁體中文

> **發布準備稿，還不是正式版本。** 這一頁寫的是進行中的工作。目前 repo 的版本仍是 `2.1.0`，也還沒有 `v2.2.0` 的 artifact、checksum 或 release tag。請不要照這一頁安裝，也不要把它當成下載來源。

現在就要跑正式環境的話，請安裝已發布的 [AceEconomy v2.1.0](release-v2.1.0.zh-TW.md)。這份草稿是寫給伺服器管理員與維護者的：v2.2.0 目前的範圍包含什麼、背後有哪些證據、正式發布前還差哪些關卡。

## 目錄

- [現在能不能用](#現在能不能用)
- [基線與發布狀態](#基線與發布狀態)
- [v2.2.0 多了什麼](#v220-多了什麼)
- [做到哪裡、有什麼證據、還卡在哪裡](#做到哪裡有什麼證據還卡在哪裡)
- [安裝或升級前](#安裝或升級前)
- [發布前必須完成的關卡](#發布前必須完成的關卡)
- [明確不宣稱的事](#明確不宣稱的事)

## 現在能不能用

還不行。這一頁沒有任何可以下載或直接建置的正式版本：

- `build.gradle.kts` 仍宣告 `version = "2.1.0"`，目前的建置產物是 `AceEconomy-2.1.0.jar`。
- 沒有 `v2.2.0` 的 GitHub Release、artifact 或 `SHA256SUMS` 項目，也還沒定發布日期。
- 權限、介面、真實 MySQL/MariaDB 與真人基岩版客戶端的實機驗收關卡都還沒過。

在 v2.2.0 的發布說明取代這一頁之前，請照 [v2.1.0 管理員安裝手冊](admin-install-runbook.zh-TW.md) 安裝已發布的 v2.1.0。

## 基線與發布狀態

| 項目 | 目前狀態 |
| --- | --- |
| Java | 25 |
| 伺服器 | Paper/Folia 26.1.2（正式支援線）；Folia 26.2 僅在特定 build 上通過驗證（VERIFIED-BETA） |
| 必要相依性 | `AceLib v1.2.1` |
| repo 建置版本 | `2.1.0`（未變更） |
| 插件 artifact | 待正式發布前確認 |
| 設定 schema | `version: "2.0"` |
| 發布日期 | 待正式發布前確認 |

基線跟已發布的 v2.1.0 一樣。v2.2.0 的變動在插件功能面，不在 Java 或伺服器基線。

## v2.2.0 多了什麼

以下範圍是目前程式與文件實際涵蓋的內容。每一項都寫出它依賴的設定或指令，方便你對照參考文件。

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

## 做到哪裡、有什麼證據、還卡在哪裡

這張表把三件容易混在一起的事分開：程式已經做了什麼、它背後有哪些自動化或局部證據、以及還沒跑的實機關卡。一列可以有實作也有自動化測試，但發布關卡仍然開著。

| 範圍 | 已實作 | 現有證據 | 發布前仍待完成的關卡 |
| --- | --- | --- | --- |
| 在地化訊息 | 三份語系檔、`{placeholder}` 變數、MiniMessage、使用者輸入轉義 | 資源合約與語言覆蓋測試 | 在執行中的伺服器用 `/aceeco reload` 切換語系 |
| Discord 通知 | 非同步盡力而為的 embed；長度上限與祕密遮蔽 | notifier、payload 與 transport 測試 | 一次真實 webhook 傳送 |
| SQL 連線池與斷線恢復 | 插件自有嚴格連線池；不安全的借用丟棄、過期連線汰換 | 連線池、生命週期與 SQL 後端測試 | 真實 MySQL/MariaDB：連線、寫入、讀取、重啟 |
| SQL 排行榜與讀取快取 | `/baltop` 快照；Vault 讀取用的 `AccountBalanceCache` | 排行榜與餘額快取測試 | 真實 MySQL/MariaDB 的排行榜與快取行為 |
| Vault 名稱型 API | 名稱方法用線上與離線快取紀錄解析 | Vault provider 與名稱查詢測試 | 執行中伺服器上的真實 Vault 消費端 |
| Essentials/CMI 匯入 | 僅限主控台、路徑閘門、匯入前備份、可重複執行 | parser、路徑閘門、TOCTOU 與有界讀取測試 | 從真實 EssentialsX/CMI 資料實際匯入一次 |
| 基岩版降級與銀行表單 | 點擊動作降級；原生表單共用存提路徑 | 基岩降級與表單路由測試 | 真人基岩客戶端：存款、提款、取消、重新開啟 |
| PlaceholderAPI 佔位符 | `aceeco` 命名空間，含 `rank` 與 `top_*` | resolver 與 expansion 測試 | 伺服器上的真實 PlaceholderAPI expansion |
| 銀行支票兌回 | 右鍵兌回走原子入帳路徑 | 兌回與支票 schema 測試 | 真人玩家兌回，含失敗路徑 |
| 可設定銀行 GUI | `bank-gui` 的標題、大小與動作；reload 時關閉舊介面 | 設定 parser 與 GUI session 測試 | 真實 Java 客戶端開啟並點擊介面 |
| 貨幣 reload | 純顯示變更即時套用；結構變更被拒絕並附原因 | reload 計畫與貨幣交易測試 | 在執行中的經濟系統上實際 reload |
| 權限與發送者規則 | 每個指令都宣告發送者與權限政策 | 指令合約測試 | 真人玩家的權限與發送者拒絕 |

自動化測試與原始碼靜態檢查不等於實機驗證，局部的執行時期煙霧測試也不等於發布驗收。只有下面這些發布關卡能補上這段差距。

## 安裝或升級前

1. 現在先安裝已發布的 v2.1.0，流程照 [管理員安裝手冊](admin-install-runbook.zh-TW.md)。
2. 維持 Java 25 與 Paper/Folia 26.1.2 基線，`plugins/` 裡只留一份 `AceLib-1.2.1.jar`。
3. `config.yml` 維持 `version: "2.0"`。改動貨幣結構或只在啟動時讀取的設定，仍需重啟而不是 reload。
4. 任何變更前先備份插件資料夾或資料庫，先在副本上驗證。
5. 把真實 MySQL/MariaDB 演練與真人基岩版客戶端檢查排在發布前的驗證窗口。它們是正式發布聲明不可省略的 gate，必須在發布前通過，不是發布後再補。

## 發布前必須完成的關卡

以下每一項都要在草稿取代 v2.1.0 發布說明之前完成。這裡沒有任何一項回報為已通過。

1. **版本與 artifact** — 把 `build.gradle.kts` 升到 `2.2.0`，產出 artifact，並發布對應的 `SHA256SUMS`。
2. **完整測試與建置** — 在發布 commit 上跑完整測試套件與 release build。
3. **真人玩家權限與發送者拒絕** — 在目標伺服器上驗證玩家限定／主控台限定的發送者拒絕，以及缺少主權限與子權限。
4. **Java 銀行介面** — 用真實 Java 客戶端開啟 `/bank open`，驗證畫面與 `DEPOSIT`、`WITHDRAW`、`CLOSE` 點擊。
5. **真實 MySQL/MariaDB** — 連到目標服務，驗證啟動、寫入、讀取、重啟與邏輯快照路徑。
6. **真人基岩版客戶端** — 在基岩客戶端完成存款、提款、取消與重新開啟，並確認點擊動作的降級提示。
7. **發布文件紀錄** — 把上述執行結果記錄下來，讓正式發布說明能引用。

v2.1.0 留下來的關卡也還開著，同樣適用於這條線：實機 Folia/Bukkit 回溯執行、JSON 跨程序競爭、實體／原生資料庫備份，以及真實資料還原與故障注入。見 v2.1.0 的[還沒驗證什麼](release-v2.1.0.zh-TW.md#還沒驗證什麼)。

## 明確不宣稱的事

- v2.2.0 尚未發布、尚未可下載，也尚未完成建置。這一頁沒有寫出版本號變更、artifact 檔名、checksum 或日期。
- 這裡不提供任何測試數量或覆蓋率數字。
- 上面沒有任何發布關卡回報為已通過。
- Essentials/CMI 匯入不是自動遷移，仍是一道明確的、僅限主控台的指令。
- 邏輯備份指令不能代替 `mysqldump`、`mariadb-dump` 或資料庫管理員的實體復原流程。
- 不支援 Vault 銀行方法，Vault 一律使用設定的預設貨幣。
- 沒有分世界餘額；world-name 參數會被接受但忽略。

安裝、日常維運與復原請用[管理員安裝手冊](admin-install-runbook.zh-TW.md)、[伺服器維運](operations.zh-TW.md) 與[持久化、備份與還原](persistence.zh-TW.md)。蒐集證據或回報問題時，密碼、token、webhook URL、資料檔與備份都要保密。
