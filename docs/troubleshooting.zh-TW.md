# AceEconomy 故障排除

[English](troubleshooting.md) · [简体中文](troubleshooting.zh-CN.md) · 繁體中文

請從主控台或遊戲裡看得到的症狀入手。在改動儲存檔案之前，先複製相關的設定與資料。所有佔位符只在你本機替換；不要把密碼或 Discord 的 webhook 貼進工單或公開訊息。

## 目錄

- [插件沒有啟用](#插件沒有啟用)
- [Java、Paper 或 Folia 版本不符](#java-paper-或-folia-版本不符)
- [儲存檔案不存在或開啟了錯誤的後端](#儲存檔案不存在或開啟了錯誤的後端)
- [SQLite 路徑被拒絕](#sqlite-路徑被拒絕)
- [MySQL 或 Hikari 連線失敗](#mysql-或-hikari-連線失敗)
- [Discord 沒有收到通知](#discord-沒有收到通知)
- [Vault 或 PlaceholderAPI 整合不可用](#vault-或-placeholderapi-整合不可用)
- [設定重新載入失敗](#設定重新載入失敗)
- [重新載入、重啟或停服行為不如預期](#重新載入重啟或停服行為不如預期)
- [餘額或交易結果不對](#餘額或交易結果不對)
- [仍然失敗時要提供什麼](#仍然失敗時要提供什麼)

## 插件沒有啟用

**可能原因：** 缺少 `AceLib`、裝了錯誤版本的 AceLib，或者伺服器沒有使用要求的 Java/Paper/Folia 組合。

**先檢查：**

- 確認 `plugins/AceLib-1.0.0.jar` 與 `plugins/AceEconomy-2.1.0.jar` 都存在。
- 從正式插件資料夾裡移除 `AceLib-0.5.0-SNAPSHOT.jar` 以及其他重複的 AceLib JAR。
- 看主控台裡最早出現的 AceLib 或 Java 錯誤，不要只盯著最後那條停用的訊息。

**修正：** 使用 Java 25 配 Paper/Folia 26.1.2，安裝一個 `AceLib-1.0.0.jar`，然後完整重啟伺服器。缺少硬依賴時，`/aceeco reload` 救不了你。

## Java、Paper 或 Folia 版本不符

**可能原因：** Java 主版本不對、伺服器構建不受支援，或者 Paper/Folia 沒有提供所需的 API。

**先檢查：** 確認程序用的是 Java 25，伺服器是 Paper/Folia 26.1.2。把能辨識出 Java、伺服器、AceLib 與 AceEconomy 的啟動主控台內容留好。

**修正：** 修正服務所用的 Java 或伺服器安裝，然後重啟。不要為了繞過錯誤而換回舊的插件 JAR。

## 儲存檔案不存在或開啟了錯誤的後端

**可能原因：** `storage.type` 和正在找的檔案對不上、伺服器沒有成功啟動過，或者檔案被放到了插件資料夾之外。

**先檢查：** 打開 `plugins/AceEconomy/config.yml`，確認：

```yaml
storage:
  type: json       # json, sqlite, or mysql
```

JSON 用的是 `plugins/AceEconomy/data-v2.json`。SQLite 用的是 `storage.sqlite.path` 指定的檔案，而且必須留在 `plugins/AceEconomy/` 之下。MySQL 不會在本地建立資料庫檔案。

**修正：** 修正 YAML 裡的巢狀結構，重新啟動伺服器，再看第一條儲存相關的訊息。不要把 v1 的檔案改名成 `data-v2.json`。

## SQLite 路徑被拒絕

**可能原因：** `storage.sqlite` 被寫成了單一值而不是對應表，或者路徑用 `../` 或絕對路徑跑出了插件資料夾。

設定結構必須是：

```yaml
storage:
  type: sqlite
  sqlite:
    path: data-v2.sqlite
```

**修正：** 使用 `plugins/AceEconomy/` 之下的相對檔名或子資料夾，然後完整重啟。改動路徑之前，先把現有的 SQLite 檔案留好。

## MySQL 或 Hikari 連線失敗

**可能原因：** 主機、連接埠、資料庫、使用者、密碼或資料庫權限有誤；資料庫連不上；或者連線池參數放錯了 YAML 區塊。

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

請按資料庫管理員平時的流程確認資料庫服務與憑證。`pool-size` 與 `max-lifetime` 必須放在 `storage.mysql` 之下；JDBC 驅動已經在插件 JAR 裡了。

**修正：** 把值改對，密碼只在本機填寫，然後重啟。如果還是失敗，不要刪 v2 資料；提供去掉敏感值後的連線結構，以及第一條資料庫錯誤。

## Discord 沒有收到通知

**可能原因：** `discord.enabled` 是 false、webhook 網址為空或無效，或者 Discord 拒絕了請求。

```yaml
discord:
  enabled: true
  webhook-url: "<discord-webhook-url>"
```

請在本機確認網址，並查看伺服器附近有沒有 Discord 的訊息。支援請求裡絕對不要包含真實的網址。

**修正：** 把這兩個鍵改對，然後執行 `/aceeco reload`；如果插件或整合組合變了，再重啟。Discord 的通知是非同步、盡力而為的。通知失敗不會撤銷已經完成的交易，所以要分別去確認玩家餘額與交易結果。

## Vault 或 PlaceholderAPI 整合不可用

**可能原因：** 選用插件沒裝、被停用，或者 AceEconomy 啟動時它還沒準備好。

**先檢查：** 確認選用插件本身已經啟用，然後重啟伺服器讓 AceEconomy 跟著重啟。Vault 使用設定裡指定的預設貨幣。PlaceholderAPI 使用 `aceeco` 這個命名空間，包括 `%aceeco_balance%`、`%aceeco_balance_formatted%`、`%aceeco_balance_<currency>%` 與 `%aceeco_balance_<currency>_formatted%`。

**修正：** 安裝或啟用對應的選用插件，然後重啟。如果經濟核心指令正常、只是整合失效，就保持核心服務開著，單獨去排查那個選用插件。

## 設定重新載入失敗

**可能原因：** YAML 無效、v2 的鍵結構錯誤、值不合法，或者語言檔載入不了。

**先檢查：** 檢查 `plugins/AceEconomy/config.yml` 最近一次改了什麼，以及 `plugins/AceEconomy/lang/` 裡選中的檔案。確認設定仍然含有 `version: "2.0"`，並且用到的 `storage.sqlite` 與 `storage.mysql` 都是對應表。

**修正：** 還原到最後一份確認正常的版本，再執行 `/aceeco reload`。重新載入失敗時會保留最後一份有效的記憶體設定，不要以為那份改到一半的檔案已經生效。只有檔案有效之後，或者改動的是啟動期的儲存／依賴設定時，才需要完整重啟。

## 重新載入、重啟或停服行為不如預期

先分清楚這三種操作：

- `/aceeco reload` 重新載入設定與語言檔。
- 完整重啟會重新開啟儲存後端，並重新載入插件依賴。
- `stop` 執行正常的停服；請等保存完成。

**修正：** 改動了 JAR、AceLib、`storage.type`、資料庫連線值或選用插件的可用狀態之後，請完整重啟。不要用 Bukkit 的 `/reload` 來做正式升級或復原。

## 餘額或交易結果不對

**可能原因：** 指令用了不同的貨幣、目標玩家選錯，或者伺服器打開的 v2 後端和預期不同。

**先檢查：** 記下不含密碼的完整指令、貨幣 ID、玩家 UUID 或名稱、目前的 `storage.type`，以及操作時間。再用 `/money balance <player> <currency>` 查一次，並翻看交易前後的伺服器主控台。

**修正：** 在確認後端與貨幣之前，先停掉其他餘額變動。只有在停服時，才能從確認正常的 v2 備份還原。不要把 v1 檔案載入 v2，也不要在正式儲存上反覆嘗試沒把握的修復。

## 仍然失敗時要提供什麼

請提供一份已經去掉敏感值的簡短報告：

1. AceEconomy、AceLib、Java 與 Paper/Folia 的版本。
2. 症狀，以及開始發生的確切時間。
3. 相關指令；依需要替換掉玩家名稱與敏感值。
4. 目前的 `storage.type` 與相關鍵名，但不要給密碼、權杖或 webhook 網址。
5. 第一條 AceEconomy/AceLib/儲存錯誤，以及它前面緊鄰的主控台內容。

請保留原始的資料與設定備份。在有人審過報告之前，不要為了「清乾淨」而刪掉資料檔。
