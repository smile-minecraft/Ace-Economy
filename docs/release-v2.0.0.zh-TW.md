# AceEconomy v2.0.0 發布說明

[English](release-v2.0.0.md) · [简体中文](release-v2.0.0.zh-CN.md) · 繁體中文

AceEconomy v2.0.0 是給 Java 25 與 Paper/Folia 26.1.2 使用的 v2 伺服器版本。它需要 `AceLib-1.0.0.jar`，發布插件檔案是 `AceEconomy-2.0.0.jar`。

> **歷史發布說明。** 本頁版本號描述的是 v2.0.0 發布當時的狀態。若要全新安裝，請使用目前版本 [AceEconomy v2.1.0](release-v2.1.0.zh-TW.md)，它需要 `AceLib-1.2.0.jar`。

本頁供安裝或替換伺服器插件時使用，說明發布內容、必須放置的檔案、v2 資料落在哪裡，以及安裝前如何核對檔案。

## 目錄

- [本版本包含什麼](#本版本包含什麼)
- [檔案與相依性](#檔案與相依性)
- [設定與資料](#設定與資料)
- [指令](#指令)
- [升級與回退](#升級與回退)
- [驗證發布檔案](#驗證發布檔案)

首次安裝請先閱讀 [`admin-install-runbook.zh-TW.md`](admin-install-runbook.zh-TW.md)。從 v1 更換請使用 [`upgrade-from-v1.zh-TW.md`](upgrade-from-v1.zh-TW.md)，日常維護見 [`operations.zh-TW.md`](operations.zh-TW.md)。

## 本版本包含什麼

- 用於 v2 資料的 JSON、SQLite 與 MySQL/MariaDB 儲存。
- 多貨幣、起始餘額、債務限制、轉帳、管理員餘額調整、交易紀錄、銀行票據、銀行選單與餘額排行榜。
- 選用的 Vault 與 PlaceholderAPI 整合。
- 使用本機 webhook 設定的選用 Discord 交易通知。
- English、繁體中文與簡體中文語言檔。

## 檔案與相依性

请把以下檔案放在 `plugins/`：

```text
AceLib-1.0.0.jar
AceEconomy-2.0.0.jar
```

AceLib 是必要相依插件。Vault 與 PlaceholderAPI 是選用插件，啟用後才會被偵測。SQLite 與 MySQL JDBC drivers 已包含在 AceEconomy JAR，不需要額外的 driver 檔案。

不要讓 `AceLib-0.5.0-SNAPSHOT.jar` 或其他 AceLib 版本與 v2 並存。

## 設定與資料

目前設定是 `plugins/AceEconomy/config.yml`，其中包含 `version: "2.0"`。JSON 是預設 backend，使用 `plugins/AceEconomy/data-v2.json`。SQLite 使用插件資料夾下由 `storage.sqlite.path` 指定的檔案。MySQL/MariaDB 使用 `storage.mysql.*` 區塊。

v1 設定與資料不會自動 migration。不得只把 v1 檔案改名為 v2 檔案。若可能需要回退，請保留完整的升級前備份。

上面的安裝與維運指南包含伺服器管理員需要的設定與備份步驟。密碼與 webhook URL 只保留在本機；公開範例必須使用 placeholder。

## 指令

v2 使用以下明確格式：

| 指令 | 用途 |
| --- | --- |
| `/money balance [player] [currency]` | 查詢餘額 |
| `/pay send <player> <amount> [currency]` | 轉帳 |
| `/withdraw cash <amount> [currency]` | 建立銀行票據 |
| `/baltop top [currency]` | 顯示排行榜 |
| `/bank open` | 開啟銀行選單 |
| `/aceeco give <player> <amount> [currency]` | 增加餘額 |
| `/aceeco take <player> <amount> [currency]` | 扣除餘額 |
| `/aceeco set <player> <amount> [currency]` | 設定餘額 |
| `/aceeco reload` | 從主控台重新載入設定與語言檔 |

修改 plugin JAR、AceLib、storage backend、資料庫連線或選用插件組合後，`/aceeco reload` 不能取代重啟。

## 升級與回退

停止伺服器，備份完整的 v1 安裝，放入 v2 JAR 組合並建立 v2 設定。不要讓 v2 指向 v1 儲存。需要回退時，停止 v2，另外保留 v2 資料副本，再從有日期的備份還原升級前的 v1 JAR、設定與資料。

完整流程見 [`upgrade-from-v1.zh-TW.md`](upgrade-from-v1.zh-TW.md)。不要把 `data-v2.json`、`data-v2.sqlite` 或 v2 snapshot 複製到 v1 資料位置。

## 驗證發布檔案

發布提供 `SHA256SUMS` asset 時，請把它放在 `AceEconomy-2.0.0.jar` 旁邊，並驗證裸檔名項目：

```text
sha256sum -c SHA256SUMS
```

macOS 可使用以下命令計算值：

```text
shasum -a 256 AceEconomy-2.0.0.jar
```

把第一欄與 `SHA256SUMS` 中 `AceEconomy-2.0.0.jar` 那一行比對，確認後再把檔案放到正式伺服器。
