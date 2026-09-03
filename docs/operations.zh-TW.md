# AceEconomy v2 伺服器維運

[English](operations.md) · [简体中文](operations.zh-CN.md) · 繁體中文

這份清單用來幫你啟動伺服器、安全地修改設定、確認儲存、建立或還原備份，以及在出問題時先保住資料，而不是先刪資料。

## 目錄

- [每次開服檢查](#每次開服檢查)
- [選擇與確認儲存方式](#選擇與確認儲存方式)
- [安全地修改設定](#安全地修改設定)
- [日常指令](#日常指令)
- [備份與還原](#備份與還原)
- [整合功能](#整合功能)
- [停服、重啟與重新開放](#停服重啟與重新開放)
- [緊急回退](#緊急回退)
- [把問題交給支援人員](#把問題交給支援人員)

## 每次開服檢查

正常啟動或重啟之後，先在伺服器主控台確認出現了 `AceEconomy v2.1.0` 與 `AceLib v1.2.0`，並且只載入了一個 AceLib 版本。接著用一個測試帳號執行 `/money balance`，必要時再執行 `/baltop top`。

如果伺服器還沒準備好，不要對玩家開放。保留最早出現的那條 AceEconomy、AceLib 或儲存錯誤，然後依照[故障排除](troubleshooting.zh-TW.md)處理。

## 選擇與確認儲存方式

v2 後端由 `storage.type` 決定：

| 值 | 位置 | 適用情境 |
| --- | --- | --- |
| `json` | `plugins/AceEconomy/data-v2.json` | 單一伺服器，使用本機檔案 |
| `sqlite` | 插件資料夾下的 `storage.sqlite.path` | 單一伺服器，使用 SQLite 資料庫 |
| `mysql` | `storage.mysql.*` | 使用受管理的 MySQL 或 MariaDB 服務 |

SQLite 的路徑必須留在 `plugins/AceEconomy/` 之內。MySQL 的 `pool-size` 與 `max-lifetime` 必須放在 `storage.mysql` 之下，密碼不要寫進共用文件。

所有受支援的後端都使用同一套 v2 帳戶與交易模型。v1 的資料檔不是 v2 備份，不能拿來互相替換。

## 安全地修改設定

只有在你平時的變更流程能保護檔案不被寫壞時，才在伺服器運作中編輯 `plugins/AceEconomy/config.yml`。修改前先複製一份，保留 `version: "2.0"`，並在套用前確認 YAML 結構正確。

普通的設定或語言變更，請從主控台執行：

```text
/aceeco reload
```

成功時會回顯 `AceEconomy reloaded`。如果新的設定或語言檔載入不了，插件會保留最後一份有效的記憶體快照，不會用半成品設定頂替。但如果你改動了 `storage.type`、SQLite 路徑、MySQL 連線值、插件 JAR、AceLib 或選用插件，就必須完整停服再啟動，光靠 reload 不夠。

成功 reload 也會清空整個同步餘額快取。這是預期的接受行為，不是 bug：Vault 讀取絕不阻塞等待儲存，所以在下一次已持久化的讀取或成功寫入重新填入之前，餘額查詢會回到安全預設值 `0.0`。不會在呼叫執行緒上同步回填，因為那會把快取原本要避開的阻塞 I/O 帶回來。

不要把 Bukkit 的 `/reload` 當成維護或升級的捷徑。

## 日常指令

檢查運作中的伺服器時，可以使用這些格式：

```text
/money balance [player] [currency]
/pay send <player> <amount> [currency]
/withdraw cash <amount> [currency]
/baltop top [currency]
/bank open
/aceeco give <player> <amount> [currency]
/aceeco take <player> <amount> [currency]
/aceeco set <player> <amount> [currency]
```

請用一個測試帳號做小額、可回復的檢查，不要直接改動真實玩家的餘額。管理員調整餘額時，請記到伺服器平時的管理紀錄裡。

## 備份與還原

受管的邏輯快照與手動的災難復原副本是兩套不同的流程，不要把其中一種的說明當成另一種來用。

### 手動檔案與資料庫災難復原

直接複製 JSON 或 SQLite 資料檔之前，必須先停服。手動副本要放在正式插件資料夾之外，檔名帶上日期與用途，也不要覆寫唯一一份確認正常的副本。這屬於檔案級的災難復原副本，不是受管的 `/aceeco backup` 指令。

MySQL 或 MariaDB 的原生／實體備份，請按資料庫管理員平時的備份流程來做，並和對應的伺服器設定備份一起保存。原生或實體的資料庫備份不等於受管的 v2 邏輯快照。目前本文還沒有驗證過真實的 MySQL/Folia 組合與災難復原流程；在正式依賴之前，請先在一次受控演練中確認可行。

### 受管指令

主控台與獲得授權的管理員可以建立一份受管邏輯快照：

```text
/aceeco backup [label]
```

這條指令在伺服器運作中就能執行。它會在插件控制的 `<plugin data folder>/backups/` 目錄下，寫出一份不含任何憑證的 v2 JSON 邏輯快照。寫入時，系統會先以「不存在才建立」的方式生成 `<backup-id>.json`，把完整內容寫進去，再以同樣方式生成 `<backup-id>.ready`。這個 `.ready` 檔案裡含有 SHA-256 校驗和，是整份快照「正式生效」的標記；還原時必須同時具備這個標記，以及與之完全吻合、通過校驗的 JSON 快照。已經存在的快照或標記檔名絕不會被覆蓋。標籤（label）只能使用字母、數字、`.`、`_` 與 `-`。快照包含帳戶、餘額、交易（含已撤銷標記）與已用過的一次性序號，但絕不包含儲存密碼或 webhook 網址。對 MySQL 來說，它是透過即時連線讀取資料；這仍然是一份邏輯快照，不是 `mysqldump`／`mariabackup` 那種原生或實體備份。

要把快照正式發布出來，檔案系統需要支援安全的目錄寫入機制、不跟隨符號連結的屬性檢查、常規檔案檢查，以及強制寫入的檔案通道。這個機制是應用層的生效標記協定，並不依賴系統底層的原子重新命名或硬連結。如果檔案系統不支援，或者標記／快照寫到一半失敗，指令會安全地失敗、拒絕寫入，而不會退而求其次用不安全的方式寫。搬移快照時，請把配對的 `.json` 與 `.ready` 一起搬；只有 `.json` 不算一份已提交的備份。如果留下一個沒有標記的殘檔，還原時也會被拒絕。

還原是破壞性操作，並且有嚴格的門檻：

```text
/aceeco restore <backup-id> confirm
```

- 僅限主控台執行；需要 `aceeconomy.admin` 加上 `aceeconomy.admin.restore`，而且要逐字輸入 `confirm`。
- 只要還有任何玩家在線就會被拒絕；請先請所有人離開。
- 在動到正式資料之前，它會先校驗快照，並為目前狀態建立一份安全備份。如果這份安全備份失敗，什麼都不會還原。
- 成功後會回報舊狀態的安全備份 ID，並清空排行榜快取。讓玩家回來之前必須重啟；已經打開的會話與介面不會熱刷新。

沒有獨立的 `/backup` 或 `/restore` 根指令；這些操作只以 `/aceeco` 的管理子指令存在。

受管還原並不要求你在執行指令前另外手動停服或複製檔案，但仍建議安排在維運時段進行。在線玩家門檻、執行前檢查與還原前的安全備份都在保護這次操作；即使快照損壞或版本不相容，也絕不能當成刪除正式資料的理由。

## 整合功能

Vault 與 PlaceholderAPI 都是選用插件。少了其中任何一個，經濟核心依然能跑。Vault 使用設定裡指定的預設貨幣；PlaceholderAPI 使用 `aceeco` 這個命名空間：

```text
%aceeco_balance%
%aceeco_balance_formatted%
%aceeco_balance_<currency>%
%aceeco_balance_<currency>_formatted%
```

Discord 透過 `discord.enabled` 與 `discord.webhook-url` 設定。真正的 webhook 只放在伺服器本機。通知是非同步、盡力而為的：通知出了問題，要和已經完成的交易分開處理。

## 停服、重啟與重新開放

請使用平常的服務管理方式，或伺服器主控台指令：

```text
stop
```

請等待世界與插件的保存都完成。重啟之後，先重做一遍每次開服檢查，再讓玩家回來。如果這次重啟是因為上一次 reload 失敗，請先還原最後一份確認正常的設定，免得伺服器反覆用同一份錯誤設定啟動。

## 緊急回退

要從 v2 退回 v1，請依照[從 v1 升級](upgrade-from-v1.zh-TW.md)的說明做。在還原升級前的 v1 安裝之前，先保留目前 v2 的資料副本。絕對不要讓 v1 去讀取 `data-v2.json`、`data-v2.sqlite` 或任何 v2 快照。

如果要撤銷單筆已經記錄的交易，請從主控台執行：

```text
/aceeco rollback <transaction-id>
```

這條指令僅限主控台，需要 `aceeconomy.admin` 加上 `aceeconomy.admin.rollback`，並且會明確回報每一種結果：成功時會列出撤銷審計紀錄的 ID；已經撤銷過的交易是安全的無操作；如果標記持久化失敗，代表效果可能已經發生，卻沒有留下持久紀錄。重試之前，請先檢查儲存並人工核對。完整的結果對照表見[指令與權限](commands.zh-TW.md)。

回退指令已經接進正式的指令介面，也有自動化契約測試覆蓋，但真實的線上驗證還沒做完：Folia/Bukkit 銜接層的實際執行、真實的 MySQL 儲存，以及用真實資料做的故障注入演練都還沒跑。在這道發布門檻合上之前，請只在事先備好份的受控演練中使用它。

## 把問題交給支援人員

請使用[故障排除](troubleshooting.zh-TW.md)，並提供：最早出現的相關錯誤、版本號、目前儲存類型、已經去掉敏感值的完整指令，以及事件發生的時間。資料檔、備份、密碼、權杖與 webhook 網址都要保密。
