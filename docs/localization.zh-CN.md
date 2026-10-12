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

首次启动时，配置转接器会通过 `JavaPlugin.saveResource("lang/" + fileName, false)` 置备三份标准 `lang/<locale>.yml` 资源。任一标准资源的置备失败（抛出 `IOException`/权限/`RuntimeException`）会直接失败：配置转接器会发出脱敏的 `WARNING`（`Failed to ensure lang resource {0}: {1}`，敏感值以 `[redacted sensitive value]` 替代，绝不回显原始消息），并以非敏感的 `IllegalStateException` 中止初次 `load()`；不会继续到 `ConfigManager`/`LangManager` 加载，也不会回退到默认语言。已存在的文件（`saveResource(..., false)` 不抛异常）不视为失败。

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
2. 把完整路径 `settings.locale`（不是裸的 `locale`）设成对应的标准文件名，例如 `en_US`、`zh_TW` 或 `zh_CN`。仅这三个值受支持；其他值会被拒绝并保留上一份有效语言。
3. 保存文件。
4. 从服务器控制台执行 `/aceeco reload`，或者重启服务器。

重新加载会在同一个配置转接器的锁内再次读取配置与语言快照。有效修改会在下一条用到该键的消息中以新语言显示。当前语言由 `config.yml` 经 `ConfigLangAdapter` 与 `LangManager` 解析；构造时的默认值（`zh_TW`）仅在首次加载前 `settings.locale` 缺失时作为回退——若值无效（非 `en_US`/`zh_TW`/`zh_CN`），首次 `load()` 会直接失败，不会退回默认语言；`reload()` 则保留上一份有效快照。

所有渲染都使用这个全局语系。配置转接器经由单个插件作用域的消息范围渲染，其解析器固定为当前语系——即使玩家的客户端语系不同，看到的仍是同一种语言；刻意不跟随个别玩家的语系。若渲染时指定了明确语系，`message.prefix` 取自该语系（该语系没有前缀时退回默认语系）；缺键仍返回 `Missing translation: <key>` 并记录 `WARNING`。

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

在控制台执行 `/aceeco reload` 时，插件会原子化加载配置与语言资源。整个过程分为三个验证阶段，任一阶段失败均保留原快照，绝不应用残缺配置：

### 1. 配置文件验证（`config.yml`）
适配器通过 `ConfigManager.load()` 执行严格类型检查：
- **整数字段：** 必须为有限整数。包括 `storage.mysql.port`（1–65535）、`storage.mysql.pool-size`（1–1000）、`leaderboard.cache-time-seconds`（1–86400）、`leaderboard.page-size`（1–100）及 `storage.mysql.max-lifetime`（≥1）。浮点数（如 `3306.5`）、`NaN`/`Infinity` 或数字字符串（`"3306"`）均直接拒绝。
- **布尔字段：** 必须为原生 YAML Boolean（`economy.allow-negative-balance`、`discord.enabled`、`leaderboard.enabled`），字符串 `"true"`/`"false"` 会被拒绝。
- **字符串字段：** 包括 `settings.locale`（仅限 `en_US`、`zh_TW`、`zh_CN`）、路径与凭据。`storage.sqlite.path` 若超出插件数据目录会被阻止。
- **脱敏防护：** 诊断日志绝不回显密码、Webhook 或用户原始输入，仅输出固定格式消息（如 `invalid <path>: must be …`）或异常类别。

### 2. 语言文件预检与加载
适配器会检查选定的 `lang/<locale>.yml`：
- 目标文件必须存在、为普通文件且非空文件。若缺失、为目录或不可读取，立即视为失败，绝不静默回退。
- 通过文件预检后，由 `LangManager.load(locale)` 解析内容与 MiniMessage 标签。

### 3. 原子交换与失败防护
仅当配置与语言两者均通过验证，才会在单把锁保护下原子交换 `ConfigManager`、`LangManager`、`MessageService` 与渲染范围。退役的旧范围随即关闭，避免出现半应用或撕裂状态。

若加载失败，系统返回包含非敏感原因的 `ReloadResult` 并输出 `WARNING`。内存中维持原配置与语言输出不变。修正文件问题后重新执行 `/aceeco reload` 即可。

缺键时回退显示 `Missing translation: <key>`，并记录 `WARNING` 日志。所有传入变量中的 MiniMessage 标签（如 `<red>`、`<click>`）均会在渲染前转义，防范颜色与点击事件注入。

## 相关指南

- [配置指南](config.zh-CN.md) — 完整的 `config.yml` 参考。
- [整合](integrations.zh-CN.md) — Vault、PlaceholderAPI、Discord 和 AceLib 的设置。
- [整合 API](integration-api.zh-CN.md) — 给插件开发者的占位符与货币细节。
