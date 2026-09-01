# AceEconomy 本地化

[English](localization.md) · 简体中文 · [繁體中文](localization.zh-TW.md)

当你需要切换服务器语言、修改翻译、保持 v2 的键命名空间，或者重新加载结果时，请使用本指南。AceEconomy v2 使用 `lang/<locale>.yml`；内置示例是 `en_US`、`zh_TW` 和 `zh_CN`。

## 目录

- [文件位置与语言](#文件位置与语言)
- [v2 消息的写法](#v2-消息的写法)
- [内置键对照](#内置键对照)
- [切换当前语言](#切换当前语言)
- [安全地修改翻译](#安全地修改翻译)
- [重新加载与恢复](#重新加载与恢复)
- [相关指南](#相关指南)

## 文件位置与语言

首次启动之后，请编辑插件数据目录里的文件：

```text
plugins/AceEconomy/lang/en_US.yml
plugins/AceEconomy/lang/zh_TW.yml
plugins/AceEconomy/lang/zh_CN.yml
```

在 `plugins/AceEconomy/config.yml` 里选择语言：

```yaml
settings:
  locale: zh_TW
```

v2 的文件使用 `lang/<locale>.yml`。旧版的 `messages_<locale>.yml` 属于上一代的文件配置，已标记为停用（仅供参考），v2 消息管线不会读取这些文件；编辑 v2 消息时请只修改 `lang/<locale>.yml`。

首次启动时，适配器会通过 `JavaPlugin.saveResource("lang/" + fileName, false)` 置备三份 canonical `lang/<locale>.yml` 资源。任一 canonical 资源的置备失败（抛出 `IOException`/权限/`RuntimeException`）为 fail-fast：适配器会发出脱敏的 `WARNING`（`Failed to ensure lang resource {0}: {1}`，敏感值以 `[redacted sensitive value]` 替代，绝不回显原始消息），并以非敏感的 `IllegalStateException` 中止初次 `load()`；不会继续到 `ConfigManager`/`LangManager` 加载，也不会回退到默认语言。已存在的文件（`saveResource(..., false)` 不抛异常）不视为失败。

## v2 消息的写法

语言文件使用三种语法：

- **键的命名空间：** 像 `general.invalid-amount` 和 `economy.payment-sent` 这样带点的 YAML 路径。保留命名空间和键名，只翻译值。
- **变量占位符：** 变量写成 `{placeholder}`，例如 `{amount}`、`{player}`、`{balance}`、`{currency_name}`、`{issuer}` 和 `{status}`。保留大括号和占位符名称。动态值必须使用 `{name}`，不要使用 `<currency_name>`、`<amount>` 或 `<issuer>` 这类尖括号形式（旧的 `<...>` 动态形式会被资源合约测试拒绝）。
- **MiniMessage：** 用 `<red>`、`<yellow>`、`<aqua>`、`<green>`、和 `</red>` 这类标签来处理显示。标签会在变量替换之后解析，不要改成旧式的颜色代码。
- **命令字面量：** 当 help 或 usage 行需要展示示例参数时，把尖括号写成转义字面量 `\<player>` 与 `\<amount>`，MiniMessage 会渲染为字面 `<player>` 括号。示例（单引号 YAML 会保留反斜杠）：

```yaml
admin:
  help-pay: '<white>/pay \<player> \<amount></white> <gray>- 转账给其他玩家</gray>'
  help-withdraw: '<white>/withdraw \<amount></white> <gray>- 将余额提取为支票</gray>'
```

动态值如 `economy.balance-check-currency` 与 `economy.withdraw-redeem` 使用 `{currency_name}` 与 `{issuer}`：

```yaml
economy:
  balance-check: "Your balance: <yellow>{balance}</yellow>"
  balance-check-currency: "Your {currency_name} balance: <yellow>{balance}</yellow>"
  payment-sent: "<green>Sent <yellow>{amount}</yellow> to <aqua>{player}</aqua>!</green>"
  withdraw-redeem: "<green>Redeemed banknote: <yellow>{amount}</yellow> <gray>(Issuer: {issuer})</gray></green>"
```

### 内置键对照

| 命名空间 | 用途 | 键示例 |
| --- | --- | --- |
| `message` | 消息共用的前缀 | `message.prefix` |
| `general` | 一般错误与状态 | `general.no-permission`、`general.status` |
| `economy` | 余额与付款 | `economy.balance-check`、`economy.payment-received` |
| `admin` | 管理员操作反馈 | `admin.give` |
| `command` | 命令用法与错误 | `command.usage-pay`、`command.invalid-uuid` |
| `error` | 系统级错误诊断 | `error.missing-key`、`error.injection-detected` |
| `gui` | 银行 GUI 标签与提示 | `gui.bank-title`、`gui.input-request` |
| `banknote` | 支票物品文字 | `banknote.name`、`banknote.redeem-success` |

## 切换当前语言

1. 打开 `plugins/AceEconomy/config.yml`。
2. 把完整路径 `settings.locale`（不是裸的 `locale`）设成对应的 canonical 文件名，例如 `en_US`、`zh_TW` 或 `zh_CN`。仅这三个值受支持；其他值会被拒绝并保留上一份有效语言。
3. 保存文件。
4. 从服务器控制台执行 `/aceeco reload`，或者重启服务器。

重新加载会在同一个 adapter 锁内再次读取配置与语言快照。有效修改会在下一条用到该键的消息中以新语言显示。当前语言由 `config.yml` 经 `ConfigLangAdapter` 与 `LangManager` 解析；构造时的默认值（`zh_TW`）仅在首次加载前 `settings.locale` 缺失时作为回退——若值无效（非 `en_US`/`zh_TW`/`zh_CN`），首次 `load()` 会直接失败、不会 fallback，`reload()` 则保留上一份有效快照。

## 安全地修改翻译

请从要编辑的语言所对应的 v2 文件开始，只修改 YAML 的值：

```yaml
general:
  invalid-amount: "<red>Invalid amount: <white>{amount}</white></red>"
  player-not-found: "<red>Player not found: <white>{player}</white></red>"
```

翻译时请注意：

- 保持缩进，并确保 YAML 的引号有效；
- 保留原消息需要的每一个 `{placeholder}`；
- MiniMessage 的开始和结束标签必须成对；
- `invalid-amount` 这类键名保持英文，即使值已经翻译。

维护某种语言时，请保留内置资源的结构。内置资源集是所需命名空间和占位符名称的参考来源。

## 重新加载与恢复

从服务器控制台执行 `/aceeco reload`，会通过候选管理器原子地重新加载 v2 配置与选中的语言资源。适配器会先用 `new ConfigManager(...).load()` 验证 `config.yml`，对每个已声明的 v2 字段采用严格类型规则并在交换前完成：整数类型（`storage.mysql.port` 1–65535、`storage.mysql.pool-size` 1–1000、`leaderboard.cache-time-seconds` 1–86400、`leaderboard.page-size` 1–100、`storage.mysql.max-lifetime` ≥1）必须为有限整数——小数如 `3306.5`、非有限 `NaN`/`Infinity` 与数字字符串如 `"3306"` 会被拒绝且不截断；布尔类型（`economy.allow-negative-balance`、`discord.enabled`、`leaderboard.enabled`）必须为 YAML Boolean（字符串 `"true"`/`"false"` 会被拒绝）；字符串类型（`settings.locale` 仅 `en_US`/`zh_TW`/`zh_CN`、`settings.main-command-alias`、`storage.sqlite.path`、`storage.mysql.host`/`database`/`username`/`password`、`discord.webhook-url`）必须为 String 类型（空 `password`/`webhook-url` 仍合法，`storage.sqlite.path` 越界到 data folder 外会被拒绝，并检查 storage/MySQL 跨字段规则）。诊断永远不回显原始配置、用户或异常消息——校验失败仅用固定 `invalid <path>: must be …` 或 `must be one of …`，加载/IO 失败仅用异常类别；不支持的 `settings.locale` 会发出固定 `WARNING`，不回显原始 code。接着对选中的 `lang/<locale>.yml` 先做预检（必须存在、为 regular 文件且非空；缺失、空文件、非 regular/目录或无法读取均视为失败，不会静默回退到默认语言），再用 `new LangManager(...).load(locale)` 验证内容；仅当两个候选都成功时，才在同一把锁内一次性交换 `ConfigManager`/`LangManager`/`MessageService`。任何失败（YAML 损坏、类型/范围违规如 `storage.mysql.port: not-a-number` 或 `3306.5`、不支持的 `settings.locale` 如 `ja_JP`、或选中的 `lang/<locale>.yml` 缺失/空文件/非 regular/损坏）都会保留上一份完整快照，`getConfig("settings.locale")`、当前语言与所有渲染输出均不变——不会出现半套用状态，且文件保留/还原失败会并入诊断。

失败会返回 `ReloadResult{config=failed/lang=failed, configError/langError}`，携带非敏感诊断（固定 `invalid <path>: …` 或异常类别；密码、webhook 网址与任意用户值永不回显）并通过插件 logger 发出 `WARNING`；`diagnostics()` 始终包含失败侧的原因。成功则返回 `config=ok, lang=ok`。错误的修改永远不会覆盖当前内存中的语言。

缺键会产生非空回退 `Missing translation: <key>`（不泄露用户提供的值）并记录诊断 `WARNING`。用户提供的值中若包含 MiniMessage 标签（如 `<red>`、`<bold>`、`<click:...>`、`<hover:...>`、`<insertion>`、`<font>`），会在解析前被转义，因此在组件与纯文本投影中均以字面文本出现，不会产生颜色、装饰、点击/悬停、insertion 或 font 注入。

请修正报告里指出的 YAML 问题（检查有效的 `{placeholder}` 名称、成对的 MiniMessage 标签、命令示例的转义 `\<literal>` 以及正确的 `settings.locale`）、保存文件，再执行一次 `/aceeco reload`。如果还是失败，就还原到最后一份有效的副本，并检查键的缩进、引号和 MiniMessage 标签。

## 相关指南

- [配置指南](config.zh-CN.md) — 完整的 `config.yml` 参考。
- [整合](integrations.zh-CN.md) — Vault、PlaceholderAPI、Discord 和 AceLib 的设置。
- [整合 API](integration-api.zh-CN.md) — 给插件开发者的占位符与货币细节。
