# Localization

AceEconomy supports multiple languages for server operators worldwide.

---

## Supported Locales

| Locale | Language | Status |
|--------|----------|--------|
| `en_US` | English | Default |
| `zh_TW` | Traditional Chinese | Built-in |
| `zh_CN` | Simplified Chinese | Built-in |

The language files are located in: `plugins/AceEconomy/lang/`

---

## Changing the Language

1. Open `config.yml` in your server's `plugins/AceEconomy/` folder
2. Find or add the `settings.locale` option:
   ```yaml
   settings:
     locale: "zh_TW"
   ```
3. Run `/aceeco reload` or restart your server

---

## Creating a New Translation

### Step 1: Copy an Existing File

Copy `messages_en_US.yml` and rename it to your target locale (e.g., `messages_fr_FR.yml`).

### Step 2: Edit the Values

Edit the values using **MiniMessage** formatting (supported since Minecraft 1.21).

**Example:**
```yaml
prefix: "<gradient:#FF5555:#55FF55>AceEconomy</gradient> <gray>» "
balance: "<green>Your balance: <yellow><amount>"
pay-success: "<green>Successfully paid <yellow><amount> <green>to <aqua><player>."
```

### Step 3: Activate Your Language

Update `config.yml`:

```yaml
settings:
  locale: "fr_FR"
```

### Step 4: Reload

Run `/aceeco reload` to apply changes.

---

## MiniMessage Formatting

MiniMessage allows rich text formatting with tags. Here are commonly used tags:

| Tag | Description | Example |
|-----|-------------|---------|
| `<green>` | Green text | `<green>Success` |
| `<red>` | Red text | `<red>Error` |
| `<yellow>` | Yellow text | `<yellow>Warning` |
| `<aqua>` | Aqua text | `<aqua>Player` |
| `<gray>` | Gray text | `<gray>Info` |
| `<bold>` | Bold text | `<bold>Important` |
| `<italic>` | Italic text | `<italic>Note` |
| `<hover:show_text:"...">` | Hover tooltip | `<hover:show_text:"Click me">Button` |
| `<click:run_command:"...">` | Click action | `<click:run_command:"/money">Click</click>` |
| `<gradient:color1:color2>` | Gradient text | `<gradient:#FF5555:#55FF55>Rainbow</gradient>` |

---

## Contributing Translations

We welcome community translations to help AceEconomy reach more players!

### How to Contribute

1. **Fork** the repository on GitHub
2. Add your new language file to `src/main/resources/lang/`
3. Ensure the filename follows the pattern: `messages_<LOCALE>.yml`
4. Create a **Pull Request**

### Translation File Template

```yaml
# AceEconomy Language File
# Locale: Your Locale (e.g., fr_FR)

prefix: "<gray>AceEconomy <dark_gray>» "

# Commands
command:
  money: "Check your balance"
  pay: "Pay another player"
  baltop: "View richest players"

# Messages
messages:
  balance: "<green>Your balance: <yellow><amount>"
  pay-success: "<green>Paid <yellow><amount> <green>to <aqua><player>"
  pay-received: "<green>Received <yellow><amount> <green>from <aqua><player>"
  pay-failed: "<red>Insufficient balance"

# Errors
errors:
  player-not-found: "<red>Player not found"
  invalid-amount: "<red>Invalid amount"
  same-player: "<red>Cannot pay yourself"
```

---

---

# 在地化

AceEconomy 支援多種語言，方便全球伺服器運營者使用。

---

## 支援的語系

| 語系 | 語言 | 狀態 |
|------|------|------|
| `en_US` | 英文 | 預設 |
| `zh_TW` | 正體中文 | 內建 |
| `zh_CN` | 簡體中文 | 內建 |

語言檔案位於：`plugins/AceEconomy/lang/`

---

## 變更語言

1. 開啟伺服器 `plugins/AceEconomy/` 資料夾中的 `config.yml`
2. 找到或新增 `settings.locale` 選項：
   ```yaml
   settings:
     locale: "zh_TW"
   ```
3. 執行 `/aceeco reload` 或重啟伺服器

---

## 建立新翻譯

### 步驟 1：複製現有檔案

複製 `messages_en_US.yml` 並重新命名為您的目標語系（如 `messages_fr_FR.yml`）。

### 步驟 2：編輯內容

使用 **MiniMessage** 格式編輯內容（Minecraft 1.21 起支援）。

**範例：**
```yaml
prefix: "<gradient:#FF5555:#55FF55>AceEconomy</gradient> <gray>» "
balance: "<green>Your balance: <yellow><amount>"
pay-success: "<green>Successfully paid <yellow><amount> <green>to <aqua><player>."
```

### 步驟 3：啟用您的語言

更新 `config.yml`：

```yaml
settings:
  locale: "fr_FR"
```

### 步驟 4：重新載入

執行 `/aceeco reload` 套用變更。

---

## MiniMessage 格式

MiniMessage 允許使用標籤進行豐富的文字格式設定。以下是常用標籤：

| 標籤 | 說明 | 範例 |
|------|------|------|
| `<green>` | 綠色文字 | `<green>成功` |
| `<red>` | 紅色文字 | `<red>錯誤` |
| `<yellow>` | 黃色文字 | `<yellow>警告` |
| `<aqua>` | 淺藍色文字 | `<aqua>玩家` |
| `<gray>` | 灰色文字 | `<gray>資訊` |
| `<bold>` | 粗體文字 | `<bold>重要` |
| `<italic>` | 斜體文字 | `<italic>備註` |
| `<hover:show_text:"...">` | 懸停提示 | `<hover:show_text:"點擊我">按鈕` |
| `<click:run_command:"...">` | 點擊動作 | `<click:run_command:"/money">點擊</click>` |
| `<gradient:color1:color2>` | 漸層文字 | `<gradient:#FF5555:#55FF55>彩虹</gradient>` |

---

## 貢獻翻譯

我們歡迎社群翻譯，幫助 AceEconomy 觸及更多玩家！

### 如何貢獻

1. 在 GitHub 上 **Fork** 此專案
2. 將您的新語言檔案加入 `src/main/resources/lang/`
3. 確保檔案名稱遵循格式：`messages_<語系>.yml`
4. 建立 **Pull Request**

### 翻譯檔案範本

```yaml
# AceEconomy Language File
# Locale: 您的語系（例如 fr_FR）

prefix: "<gray>AceEconomy <dark_gray>» "

# 指令
command:
  money: "查看餘額"
  pay: "轉帳給其他玩家"
  baltop: "查看富豪榜"

# 訊息
messages:
  balance: "<green>您的餘額：<yellow><amount>"
  pay-success: "<green>已轉帳 <yellow><amount> <green>給 <aqua><player>"
  pay-received: "<green>收到 <yellow><amount> <green>來自 <aqua><player>"
  pay-failed: "<red>餘額不足"

# 錯誤
errors:
  player-not-found: "<red>找不到玩家"
  invalid-amount: "<red>金額無效"
  same-player: "<red>無法轉帳給自己"
```
