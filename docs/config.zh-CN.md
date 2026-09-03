# 配置指南

[English](config.md) · 简体中文 · [繁體中文](config.zh-TW.md)

AceEconomy 会把 `config.yml` 当作有版本的 YAML 配置文件读取。本页说明每个配置存在的原因、何时生效，以及可以使用的值。示例中的秘密都使用預留位置，请只在服务器自己的私有副本中填入实际内容。

## 目录

- [编辑前](#编辑前)
- [存储方式](#存储方式)
- [经济规则](#经济规则)
- [货币](#货币)
- [语言与保留的指令设置](#语言与保留的指令设置)
- [排行榜](#排行榜)
- [银行界面布局](#银行界面布局)
- [Discord 与秘密边界](#discord-与秘密边界)
- [应用更改](#应用更改)

## 编辑前

文件使用 v2 配置格式：

```yaml
version: "2.0"
```

请保留 YAML 的缩进与配置键名称。如果定义了默认值，缺少该值时会使用 schema 默认值。`storage.type` 只能使用 `json`、`sqlite` 或 `mysql`；其他值会被拒绝，不会静默改用另一种存储方式。

| 用途 | 配置键 | 默认值与格式 | 生效时机 |
| --- | --- | --- | --- |
| 指定配置文件格式。 | `version` | `"2.0"`；带引号的 major/minor 文本。 | 配置加载时。 |

请不要把 `version: "2.0"` 改成持久化 schema 的 `1`；两者描述的是不同层级。

## 存储方式

存储方式决定账户、余额与交易记录存放在哪里。正式运行前就应先选定，因为切换后端不会自动转换已有数据。

### `storage.type`

| 用途 | 配置键 | 默认值与格式 | 生效时机 |
| --- | --- | --- | --- |
| 选择持久化后端。 | `storage.type` | `json`；可用值为 `json`、`sqlite`、`mysql`。 | 插件启动时读取；修改后请重启。 |

单服务器、希望减少维护时可以使用 JSON。想使用一个本地数据库文件时可以使用 SQLite。已有数据库服务，或希望把数据放在插件文件夹之外时可以使用 MySQL。MariaDB 没有独立的配置值；MariaDB 服务请通过 `mysql` 后端配置。

### JSON

JSON 是默认方式，会在插件文件夹下用一个文件保存 v2 数据，不需要连接设置。

```yaml
storage:
  type: json
```

文件名是 `data-v2.json`。备份或迁移服务器时，请把它和插件文件夹一起保留。

### SQLite

| 用途 | 配置键 | 默认值与格式 | 生效时机 |
| --- | --- | --- | --- |
| 指定 SQLite 文件名。 | `storage.sqlite.path` | `data-v2.sqlite`；相对路径会在插件文件夹下解析。 | 启动时读取；修改后请重启。 |

```yaml
storage:
  type: sqlite
  sqlite:
    path: data-v2.sqlite
```

路径必须留在插件文件夹内。`../economy.sqlite` 这样的越界路径，以及指向其他根目录的绝对路径都会被拒绝，避免配置错误时误选主机上的其他文件。

### MySQL 与 MariaDB

SQL 网络后端只有 `mysql` 这个配置值。系统会使用 `host`、`port` 与 `database` 组成 JDBC 连接，然后创建 HikariCP 连接池。

| 用途 | 配置键 | 默认值与格式 | 生效时机 |
| --- | --- | --- | --- |
| 数据库主机名。 | `storage.mysql.host` | `localhost`；文本。 | 启动时。 |
| 数据库端口。 | `storage.mysql.port` | `3306`；整数。 | 启动时。 |
| 数据库名称。 | `storage.mysql.database` | `aceconomy`；文本。 | 启动时。 |
| 数据库用户。 | `storage.mysql.username` | `root`；文本。正式服务器请使用专用账户。 | 启动时。 |
| 数据库密码。 | `storage.mysql.password` | 随附示例为空字符串；请在私有配置中填写。 | 启动时。 |
| 连接池上限。 | `storage.mysql.pool-size` | `10`；正整数。 | 启动时。 |
| 连接最长生命周期。 | `storage.mysql.max-lifetime` | `1800000`；正整数，单位为毫秒（30 分钟）。 | 启动时。 |

```yaml
storage:
  type: mysql
  mysql:
    host: "db.example.invalid"
    port: 3306
    database: "aceconomy"
    username: "<database-user>"
    password: "<set-locally>"
    pool-size: 10
    max-lifetime: 1800000
```

数据库与用户必须先能让服务器连接。插件初始化 SQL 后端时会创建 v2 数据表；v2 不使用旧版 v1 的数据表名称作为建库脚本。数据模型请参考[数据库概念与升级](database.zh-CN.md)。

## 经济规则

这些配置会影响新账户与默认货币的负债规则。它们会在经济服务创建时读取，因此修改后请重启，不要假设 reload 会重新创建正在运行的服务。

| 用途 | 配置键 | 默认值与格式 | 注意 |
| --- | --- | --- | --- |
| 允许默认货币余额低于零。 | `economy.allow-negative-balance` | `true`；布尔值。 | 设为 `false` 时停用负债规则。 |
| 设置默认负债上限。 | `economy.default-debt-limit` | `0.0`；小数金额。 | 玩家没有权限专属负债设置时使用。 |
| 设置新账户的默认金额。 | `start-balance` | `1000.0`；小数金额。 | 修改后不会重置已有账户。 |

```yaml
economy:
  allow-negative-balance: true
  default-debt-limit: 0.0
start-balance: 1000.0
```

## 货币

`currencies` 区块由服务器管理员自行定义。随附文件定义了 `dollar` 与 `token`，也可以用相同方式新增其他货币；插件会加载区块中任何合法的组合。每个项目提供稳定的 ID、显示名称、符号与小数位数。必须且只能有一个项目使用 `default: true`；该项目是一般经济流程与 Vault 集成使用的默认货币。

| 用途 | 配置键 | 默认值与格式 | 注意 |
| --- | --- | --- | --- |
| 给玩家看的名称。 | `currencies.<id>.name` | 文本；每个货币必填。 | ID 是 `currencies` 下的键，已有数据后请保持稳定。 |
| 显示符号。 | `currencies.<id>.symbol` | 文本；每个货币必填。 | 显示金额时会放在数值旁。 |
| 小数位数。 | `currencies.<id>.scale` | 非负整数；每个货币必填。 | 超过位数的金额不会自动四舍五入。 |
| 指定默认货币。 | `currencies.<id>.default` | 布尔值；整个区块只能有一个 `true`。 | 必须保持一个默认货币。 |

```yaml
currencies:
  dollar:
    name: "Gold Coin"
    symbol: "$"
    scale: 2
    default: true
  token:
    name: "Event Token"
    symbol: "ⓒ"
    scale: 0
    default: false
  gem:
    name: "Gem"
    symbol: "*"
    scale: 1
    default: false
```

每个项目都适用以下验证规则：

- 货币 ID 是 `currencies` 下的键。去除前后空格并转换为小写后，只能使用 `a-z`、`0-9` 与 `_`。只有大小写或空格差异的 ID（例如 `Dollar` 与 `dollar`）视为同一个货币，会因重复而被拒绝。
- 每个项目都必须以正确类型定义四个字段；缺少字段或类型错误（例如 `scale` 写成带引号的文本、`default` 写成 `"true"`）都会被拒绝。
- 区块至少要有一个货币，并且必须恰好有一个默认货币。

违反这些规则的配置会让插件在启动时停止，并留下指出问题的错误消息；不会出现部分应用的状态。修改名称或符号会影响显示。修改 ID、小数位数或默认货币会影响后续操作如何解释金额；应用到正式经济系统前，请先备份并安排变更。

reload 会先把候选的 `currencies` 区块分类，确认安全才会动到线上配置：

| 变更 | reload 怎么做 | 为什么 |
| --- | --- | --- |
| 没变 | reload 成功，什么都不换。 | 没有东西要套用。 |
| 只改 `name` 或 `symbol` | 直接热套用，新显示文字立刻全服生效。 | 显示文字不影响已存金额、小数位数与默认货币。 |
| 新增货币 | reload 拒绝并告知新增的 ID，服务器沿用旧组合。请重启才会生效。 | 既有账户需要批量初始化并支持回滚，目前没有存储后端能做到。 |
| 删除货币，或改 `scale`、改 `default` | reload 拒绝并告知受影响的 ID，服务器沿用旧组合。请重启才会生效。 | 这些变更会让已存余额被误读或变成孤儿。 |
| 候选区块格式无效 | reload 拒绝并告知解析原因，什么都不换。 | 读不懂的候选配置绝不能取代线上登录表。 |

被拒绝的 reload 不会动到运行中的服务器：把文件改回来，或为结构变更安排一次重启，再执行一次 `/aceeco reload`。只有纯显示变更可以在不重启的情况下更新登录表、指令、Vault 桥接与 placeholder 扩展。

## 语言与保留的指令设置

| 用途 | 配置键 | 默认值与格式 | 生效时机 |
| --- | --- | --- | --- |
| 选择消息语言。 | `settings.locale` | `zh_TW`；可用 `en_US`、`zh_TW`、`zh_CN`。 | 语言加载或 reload 时。 |
| 管理指令的额外标签。 | `settings.main-command-alias` | `aceeco`；文本，仅限 `a-z`、`0-9`、`-`、`_`。 | 只在启动时生效；修改后请重启。 |

```yaml
settings:
  locale: "en_US"
  main-command-alias: "aceeco"
```

正式主指令是 `/aceeco`。将 `settings.main-command-alias` 设置为其他值时，插件会在启动时把该标签挂为同一个管理指令在 AceEconomy 指令登录表内的额外别名。空值或空白会保留默认入口。

此设置有两个边界：

1. **拒绝冲突。** 配置值不得与插件已在 `plugin.yml` 中声明的任何指令标签（主指令：`money`、`pay`、`aceeco`、`withdraw`、`baltop`、`bank`；別名：如 `balance`、`bal`、`balancetop`、`top`、`menu`、`bankmenu`）或其他 AceEconomy 指令名称冲突。冲突会让插件在启动时以明确错误停止，而不是覆盖已有入口；这个设置永远无法抢走 `/bank` 等已有指令。
2. **Bukkit 标签是静态的。** 服务器只会转发 `plugin.yml` 中声明的指令标签，而该文件随版本固定发布。自定义别名会经过验证，并可以在 AceEconomy 的指令分派器内解析；但要在游戏内实际输入该标签并到达插件，前提是该标签也已声明在 `plugin.yml` 的主指令／别名中；v2.1.0 不会在执行期注册新的 Bukkit 指令。修改此值始终需要重启，reload 不会重新注册指令。

语言文件名为 `lang/en_US.yml`、`lang/zh_TW.yml` 与 `lang/zh_CN.yml`。请不要把密码或 webhook 网址放进语言文件。

## 排行榜

| 用途 | 配置键 | 默认值与格式 | 注意 |
| --- | --- | --- | --- |
| 启用排行榜功能。 | `leaderboard.enabled` | `true`；布尔值。 | `false` 会在启动时移除可执行的 `/baltop` 处理器；指令标签本身仍静态存在于 `plugin.yml`。修改后请重启。 |
| 控制排行榜缓存重用时间。 | `leaderboard.cache-time-seconds` | `300`；整数秒。 | 值较小会更常更新，值较大会减少更新次数。 |
| 设置每页条数。 | `leaderboard.page-size` | `10`；整数。 | 这只控制每页显示数量，不会限制账户数。 |

```yaml
leaderboard:
  enabled: true
  cache-time-seconds: 300
  page-size: 10
```

设置 `enabled: false` 时，插件不会在其登录表创建或挂上 baltop 指令规格，因此不会执行任何经济代码。由于 `plugin.yml` 仍声明静态的 `baltop` 标签，服务器只会返回普通的用法说明；要完全移除该标签，需要发布修改 `plugin.yml` 的新版本。此开关只在启动时读取；修改后请重启。

## 银行界面布局

`bank-gui` 配置段控制 `/bank` 界面：标题、大小，以及每个槽位执行哪个动作。出货默认值重现此前的固定行为（存款 slot 4、提取 100 slot 11、提取 500 slot 13、关闭 slot 15、大小 27）。

| 用途 | 配置键 | 默认值与格式 | 注意 |
| --- | --- | --- | --- |
| 开启或关闭银行界面。 | `bank-gui.enabled` | `true`；布尔值。 | `false` 时 `/bank` 不做任何事。修改后请重启。 |
| 标题语言键。 | `bank-gui.title-key` | `gui.bank-title`；非空语言键。 | 经安全组件管线渲染，绝不按 raw MiniMessage 解析。 |
| 背包大小。 | `bank-gui.size` | `27`；只能是 `9`、`18`、`27`、`36`、`45`、`54`。 | 所有按钮槽位必须在 `[0, size)` 内。 |
| 按钮槽位。 | `bank-gui.actions.<name>.slot` | 整数；每个动作必填。 | 整个配置段内不可重复。 |
| 按钮行为。 | `bank-gui.actions.<name>.type` | `deposit`、`withdraw`、`close`、`none` 四选一。 | `none` 只占位，不绑定动作。 |
| 提取面额。 | `bank-gui.actions.<name>.amount` | 正整数；`withdraw` 必填。 | 其他类型不得填写。 |
| 提取币种。 | `bank-gui.actions.<name>.currency` | 已知币种 ID；`withdraw` 必填。 | 缺省为运行时默认币种。其他类型不得填写。 |
| 按钮显示物品。 | `bank-gui.actions.<name>.material` | 合法 Bukkit 材质名；除 `none` 外必填。 | air 会被拒绝。 |
| 按钮显示文字。 | `bank-gui.actions.<name>.name-key` / `lore-keys` | 语言键；除 `none` 外 name 必填，lore 可选。 | 管理员输入绝不会被当成 MiniMessage 解析。 |

```yaml
bank-gui:
  enabled: true
  title-key: "gui.bank-title"
  size: 27
  actions:
    deposit:
      slot: 4
      type: deposit
      material: "CHEST"
      name-key: "gui.bank-deposit-name"
      lore-keys: ["gui.bank-deposit-lore"]
    withdraw100:
      slot: 11
      type: withdraw
      amount: 100
      currency: dollar
      material: "PAPER"
      name-key: "gui.bank-withdraw-name"
      lore-keys: ["gui.bank-withdraw-lore"]
    withdraw500:
      slot: 13
      type: withdraw
      amount: 500
      currency: dollar
      material: "PAPER"
      name-key: "gui.bank-withdraw-name"
      lore-keys: ["gui.bank-withdraw-lore"]
    close:
      slot: 15
      type: close
      material: "BARRIER"
      name-key: "gui.bank-close-name"
      lore-keys: []
```

违反这些规则的配置会让插件在启动时停止，并在错误中给出精确路径（例如 `bank-gui.actions.withdraw100.amount`）；不会有半套用的状态。没有 `bank-gui` 的旧配置仍可在 schema `2.0` 下加载，并沿用旧版槽位行为。

合法的 `bank-gui` 候选会随 reload 一起生效：已打开的银行界面会先被关闭，不让任何点击卡在新旧规则之间；之后新开的界面按新布局解析点击。格式无效的布局会让整笔 reload 被拒绝，旧配置原封不动。但 `bank-gui.enabled` 开关本身只在启动时生效，改了还是要重启。

## Discord 与秘密边界

| 用途 | 配置键 | 默认值与格式 | 注意 |
| --- | --- | --- | --- |
| 开启或关闭 webhook 通知。 | `discord.enabled` | `false`；布尔值。 | 尚未配置私有 endpoint 前请保持 `false`。 |
| 指定 Discord webhook 端點。 | `discord.webhook-url` | 默认为空字符串；URL 文本。 | 完整 URL 应视为凭证。 |

```yaml
discord:
  enabled: false
  webhook-url: "https://discord.com/api/webhooks/<set-locally>"
```

请勿公开真实 webhook 网址、数据库密码，或包含凭证的连接信息。也不要把它们粘贴到 issue 或共享示例中。如果秘密已经泄露，请先在服务提供商处更换，再更新服务器的私有配置。

## 应用更改

1. 修改存储路径或连接设置前先停止服务器。
2. 复制相关数据文件或创建数据库备份。
3. 编辑 `config.yml`，不要改变 YAML 结构。
4. 启动服务器，查看启动日志中是否有配置或连接错误。

管理员 reload 操作会重新加载配置与语言快照，但不会在后端之间搬移数据，也不会重新打开存储后端。在服务创建时读取的设置——存储方式、`settings.main-command-alias`、`leaderboard.enabled`——会保持启动时的值直到下次重启，reload 只会在回报中提醒重启，不会偷偷套用；reload 也不会重新注册指令。货币的显示变更（`name`、`symbol`）会热套用，包括 Vault 桥接与 placeholder 显示；结构变更会被拒绝，请重启后才会生效。
