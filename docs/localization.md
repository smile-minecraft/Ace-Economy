# 🌐 Localization / 在地化 (翻譯)

AceEconomy supports multiple languages.
AceEconomy 支援多種語言。

The language files are located in: `plugins/AceEconomy/lang/`
語言檔案位於：`plugins/AceEconomy/lang/`

## 📂 Supported Locales / 支援的語系

- `en_US`: English (Default / 預設)
- `zh_TW`: Traditional Chinese (正體中文)
- `zh_CN`: Simplified Chinese (简体中文)

---

## ✏️ Creating a New Translation / 建立新翻譯

1. **Copy** an existing file (e.g., `messages_en_US.yml`).
   **複製** 一個現有的檔案（例如 `messages_en_US.yml`）。
2. **Rename** it to your target locale (e.g., `messages_fr_FR.yml`).
   **重新命名** 為您的目標語系（例如 `messages_fr_FR.yml`）。
3. **Edit** the values. You can use **MiniMessage** formatting (supported since 1.21).
   **編輯** 內容。您可以使用 **MiniMessage** 格式（自 1.21 起支援）。

**Example**:
```yaml
prefix: "<gradient:#FF5555:#55FF55>AceEconomy</gradient> <gray>» "
balance: "<green>Your balance: <yellow><amount>"
pay-success: "<green>Successfully paid <yellow><amount> <green>to <aqua><player>."
```

4. **Change** `config.yml` to use your new locale:
   **更改** `config.yml` 使用您的新語系：

```yaml
locale: "fr_FR"
```

5. **Run** `/aceeco reload`.
   **執行** `/aceeco reload`。

---

## 🤝 Contributing Translations / 貢獻翻譯

We welcome community translations!
我們歡迎社群貢獻翻譯！

1. **Fork** the repository on GitHub.
   在 GitHub 上 **Fork** 此專案。
2. Add your new language file to `src/main/resources/lang/`.
   將您的新語言檔案加入 `src/main/resources/lang/`。
3. Create a **Pull Request**.
   建立 **Pull Request (PR)**。

Thank you for helping us reach more users!
感謝您幫助我們觸及更多使用者！
