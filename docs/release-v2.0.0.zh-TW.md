# AceEconomy v2.0.0 發布說明

[English](release-v2.0.0.md) · [简体中文](release-v2.0.0.zh-CN.md) · 繁體中文

AceEconomy v2.0.0 是給 Java 25 與 Paper/Folia 26.1.2 用的 v2 伺服器版本。它需要 `AceLib-1.0.0.jar`，發布的插件檔案是 `AceEconomy-2.0.0.jar`。

> **歷史發布說明。** 本頁的版本號描述的是 v2.0.0 發布當時的狀態。若要全新安裝，請使用目前版本 [AceEconomy v2.1.0](release-v2.1.0.zh-TW.md)，它需要 `AceLib-1.2.1.jar`。

這份文件寫給要安裝或更換伺服器插件的管理員：先看這版帶來什麼，再照著放檔案、確認設定與資料位置，最後在上線前驗證檔案。

## 目錄

- [這版包含什麼](#這版包含什麼)
- [要放哪些檔案](#要放哪些檔案)
- [設定與資料在哪裡](#設定與資料在哪裡)
- [指令長什麼樣](#指令長什麼樣)
- [升級與回退怎麼做](#升級與回退怎麼做)
- [上線前先驗證檔案](#上線前先驗證檔案)

首次安裝請先讀 [`admin-install-runbook.zh-TW.md`](admin-install-runbook.zh-TW.md)。從 v1 更換請用 [`upgrade-from-v1.zh-TW.md`](upgrade-from-v1.zh-TW.md)，日常維護見 [`operations.zh-TW.md`](operations.zh-TW.md)。

## 這版包含什麼

- 給 v2 資料用的 JSON、SQLite 與 MySQL/MariaDB 儲存。
- 多貨幣、起始餘額、債務限制、轉帳、管理員餘額調整、交易紀錄、銀行票據、銀行選單與餘額排行榜。
- 選用的 Vault 與 PlaceholderAPI 整合。
- 用本機 webhook 設定做的選用 Discord 交易通知。
- English、繁體中文與簡體中文語言檔。

## 要放哪些檔案

請把以下檔案放進 `plugins/`：

```text
AceLib-1.0.0.jar
AceEconomy-2.0.0.jar
```

AceLib 是必要的相依插件。Vault 與 PlaceholderAPI 是選用插件，啟用後才會被偵測到。SQLite 與 MySQL JDBC drivers 已經包在 AceEconomy JAR 裡，不需要另外放 driver 檔案。

不要讓 `AceLib-0.5.0-SNAPSHOT.jar` 或其他 AceLib 版本跟 v2 放在一起。

## 設定與資料在哪裡

啟用中的設定是 `plugins/AceEconomy/config.yml`，裡面包含 `version: "2.0"`。JSON 是預設後端，資料放在 `plugins/AceEconomy/data-v2.json`。SQLite 用插件資料夾下 `storage.sqlite.path` 指定的檔案。MySQL/MariaDB 用 `storage.mysql.*` 區塊。

v1 的設定與資料不會自動遷移。不得只把 v1 檔案改名成 v2 檔案。如果之後可能回退，請先留一份完整的升級前備份。

上面的安裝與維運指南有伺服器管理員需要的設定與備份步驟。密碼與 webhook URL 只留在本機；公開範例一律用佔位符。

## 指令長什麼樣

v2 用以下明確格式：

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

換過插件 JAR、AceLib、儲存後端、資料庫連線或選用插件組合之後，`/aceeco reload` 不能代替重啟。

## 升級與回退怎麼做

先停服，把完整的 v1 安裝備份下來，再放進 v2 的 JAR 組合併建立 v2 設定。不要讓 v2 指到 v1 的儲存。真的要回退時，先停掉 v2，另外留一份 v2 資料副本，再從有日期的備份還原升級前的 v1 JAR、設定與資料。

完整流程見 [`upgrade-from-v1.zh-TW.md`](upgrade-from-v1.zh-TW.md)。不要把 `data-v2.json`、`data-v2.sqlite` 或 v2 快照複製到 v1 的資料位置。

## 上線前先驗證檔案

發布有提供 `SHA256SUMS` asset 時，請把它放在 `AceEconomy-2.0.0.jar` 旁邊，驗證不帶路徑的檔名項目：

```text
sha256sum -c SHA256SUMS
```

macOS 可用以下命令計算本機的值：

```text
shasum -a 256 AceEconomy-2.0.0.jar
```

把第一欄跟 `SHA256SUMS` 裡 `AceEconomy-2.0.0.jar` 那一行比對，確認無誤後再把檔案放到正式伺服器。
