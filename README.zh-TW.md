# AceEconomy

[English](README.md) · [简体中文](README.zh-CN.md) · 繁體中文

AceEconomy 為 Paper 與 Folia 伺服器提供遊戲內經濟系統。玩家可以查詢餘額、互相付款、提領銀行鈔票、開啟銀行面板，並在排行榜上比較名次。伺服器管理員可以選擇儲存後端、定義貨幣、接入 Vault 或 PlaceholderAPI，並將交易通知送到 Discord。

## 文件索引

### 開始使用

| 文件 | 適合在以下情況閱讀 |
| --- | --- |
| [玩家指南](docs/player-guide.zh-TW.md) | 查詢餘額、向玩家付款、使用銀行鈔票或開啟銀行面板 |
| [管理員安裝手冊](docs/admin-install-runbook.zh-TW.md) | 安裝 AceEconomy v2 並完成伺服器首次檢查 |

### 日常使用

| 文件 | 適合在以下情況閱讀 |
| --- | --- |
| [指令與權限](docs/commands.zh-TW.md) | 查詢指令語法、權限、執行者與別名 |
| [設定指南](docs/config.zh-TW.md) | 設定儲存方式、貨幣、語系、經濟規則與 Discord |

### 維運與升級

| 文件 | 適合在以下情況閱讀 |
| --- | --- |
| [伺服器維運](docs/operations.zh-TW.md) | 執行日常檢查、安全修改設定、備份資料或復原伺服器 |
| [持久化、備份與還原](docs/persistence.zh-TW.md) | 選擇儲存方式，或了解備份與還原行為 |
| [從 AceEconomy v1 升級](docs/upgrade-from-v1.zh-TW.md) | 將 v1 安裝替換為 v2，或規劃回退 |
| [故障排除](docs/troubleshooting.zh-TW.md) | 排查啟動、儲存、整合或指令問題 |

### 整合與開發

| 文件 | 適合在以下情況閱讀 |
| --- | --- |
| [整合功能](docs/integrations.zh-TW.md) | 接入 AceLib、Vault、PlaceholderAPI 或 Discord |
| [整合 API](docs/integration-api.zh-TW.md) | 使用 Vault 或 PlaceholderAPI 開發插件整合 |
| [多語系](docs/localization.zh-TW.md) | 修改或維護伺服器語系檔 |

### 發布與技術參考

| 文件 | 適合在以下情況閱讀 |
| --- | --- |
| [AceEconomy v2.1.0 發布說明](docs/release-v2.1.0.zh-TW.md) | 查看 v2.1.0 的內容與驗證邊界 |
| [AceEconomy v2.0.0 發布說明](docs/release-v2.0.0.zh-TW.md) | 查看 v2.0.0 的內容與升級說明 |
| [資料庫概念與升級](docs/database.zh-TW.md) | 了解 v2 資料模型與升級路徑 |
| [v2 功能基線矩陣](docs/v2-capability-matrix.zh-TW.md) | 查看 v2 保留的 v1 功能基線 |
| [v2.0.0 切換說明](docs/cutover.zh-TW.md) | 了解 v2 runtime、依賴、安裝與回退 |

## 目錄

- [執行需求](#執行需求)
- [快速開始](#快速開始)
- [主要功能](#主要功能)
- [玩家指令](#玩家指令)
- [取得協助](#取得協助)

## 執行需求

| 需求 | 版本或說明 |
| --- | --- |
| Java | `25` |
| 伺服器 | Paper/Folia API `26.1.2 build 74` |
| 必要插件 | `AceLib v1.2.0` |
| 可選插件 | Vault、PlaceholderAPI |

Paper/Folia 26.1.2 是正式支援的伺服器線。Folia 26.2 僅在特定 build 上通過驗證（VERIFIED-BETA），其餘 26.2 build 尚未驗證。

## 快速開始

1. 安裝插件前先停止伺服器。
2. 將 `AceLib-1.2.0.jar` 與 `AceEconomy-2.1.0.jar` 放進伺服器的 `plugins` 資料夾。請從 <https://github.com/smile-minecraft/AceLib/releases/tag/v1.2.0> 下載 `AceLib-1.2.0.jar`，並在放入前核對其 SHA-256（`da9f196b47c2b28c6db443d102236b27c1a1bbdf7dd3e7c22470170420935278`）；實際命令見[管理員安裝手冊](docs/admin-install-runbook.zh-TW.md)。
3. 需要這些整合功能時，再安裝 Vault 或 PlaceholderAPI。
4. 啟動伺服器。AceEconomy 第一次啟動時會建立預設設定與資料儲存。
5. 依需求調整 `config.yml` 的儲存方式、語系、貨幣與整合設定。[設定指南](docs/config.zh-TW.md) 會說明各項設定。
6. 使用 `/money balance` 查詢餘額，再使用 `/bank open` 開啟銀行面板。

要升級現有安裝，請先閱讀[升級指南](docs/upgrade-from-v1.zh-TW.md)，再依照[管理員安裝手冊](docs/admin-install-runbook.zh-TW.md)完成部署。

## 主要功能

- **玩家經濟：** 查詢餘額、付款給其他玩家，或提領銀行鈔票。
- **銀行面板：** 開啟玩家可使用的帳戶與提領操作選單。
- **排行榜：** 查看指定貨幣的富豪玩家。
- **多種貨幣：** 使用設定的預設貨幣，也能在指令中指定其他貨幣。
- **彈性儲存：** 預設使用 JSON，也支援 SQLite 與 MySQL。
- **整合功能：** 可選用 Vault、PlaceholderAPI 與 Discord。
- **多語系：** 內建 `en_US`、`zh_TW` 與 `zh_CN` 語系。

## 玩家指令

| 指令 | 用途 |
| --- | --- |
| `/money balance [player] [currency]` | 查看餘額 |
| `/pay send <player> <amount> [currency]` | 付款給其他玩家 |
| `/withdraw cash <amount> [currency]` | 提領實體銀行鈔票 |
| `/baltop top [currency]` | 查看餘額排行榜 |
| `/bank open` | 開啟銀行面板 |

參數規則、權限、管理員指令與完整參考請查看[指令與權限](docs/commands.zh-TW.md)。想依情境操作時，可以從[玩家指南](docs/player-guide.zh-TW.md)開始。

## 取得協助

先閱讀符合目前任務的指南。如果問題仍未解決，請在 [AceEconomy repository](https://github.com/SmileX-AI/AceEconomy/issues) 提交 Issue，附上插件版本、伺服器軟體、相關指令或設定，以及你看到的訊息。發布前請移除密碼、Token 與 Webhook URL。

**AceEconomy** © 2024–2026 Developed by Smile
