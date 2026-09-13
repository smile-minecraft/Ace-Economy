# 配置指南

[English](config.md) · 简体中文 · [繁體中文](config.zh-TW.md)

这份指南写给第一次接手服务器的管理员。你改的是 `plugins/AceEconomy/config.yml`。看完应该知道每个配置管什么、不改会怎样、改完要不要重启、填错会看到什么。示例里的密码和网址都是假的占位文字，只填在服务器本机那份。

## 目录

- [编辑前](#编辑前)
- [存储方式](#存储方式)
- [经济规则](#经济规则)
- [货币](#货币)
- [语言与保留的指令设置](#语言与保留的指令设置)
- [排行榜](#排行榜)
- [银行界面布局](#银行界面布局)
- [提现超时](#提现超时)
- [Discord 与秘密边界](#discord-与秘密边界)
- [应用更改](#应用更改)

## 编辑前

文件使用 v2 配置格式：

```yaml
version: "2.0"
```

这是 v2 才认的格式标记。缩进和配置键名称要原样保留，打错一个字插件就读不懂。

| 用途 | 配置键 | 默认值与格式 | 生效时机 |
| --- | --- | --- | --- |
| 指定配置文件格式。 | `version` | `"2.0"`；带引号的 major/minor 文本。 | 配置加载时。 |

填错会怎样：`storage.type` 只认 `json`、`sqlite`、`mysql`。填其他值会直接被拒绝，不会帮你换成别种。缺了有默认值的键，会用 schema 默认值补上。

请不要把 `version: "2.0"` 改成持久化 schema 的 `1`；两者说的是不同层级。前者说配置文件格式，后者说数据本身的版本。

## 存储方式

存储方式决定账户、余额和交易记录放在哪里。开服前先选好。换了后端，旧数据不会自己搬过去。

### `storage.type`

这是整份配置最重要的开关，决定钱记在哪里。

| 用途 | 配置键 | 默认值与格式 | 生效时机 |
| --- | --- | --- | --- |
| 选择持久化后端。 | `storage.type` | `json`；可用值为 `json`、`sqlite`、`mysql`。 | 插件启动时读取；修改后请重启。 |

不改会怎样：默认 `json`，适合只有一台服务器、想省事的情况。想要一个本地数据库文件再选 SQLite。已经有数据库服务，或想把数据放在插件文件夹外面，才选 MySQL。MariaDB 没有自己的配置值，连 MariaDB 也是填 `mysql`。

改完要重启。只做重新加载不会换后端。

### JSON

JSON 是默认做法。数据写在插件文件夹下一个文件里，不用填连接信息。

```yaml
storage:
  type: json
```

文件名是 `data-v2.json`。备份或搬家时，把它和插件文件夹一起留好。

### SQLite

SQLite 也是放在本机，只是一个数据库文件。

| 用途 | 配置键 | 默认值与格式 | 生效时机 |
| --- | --- | --- | --- |
| 指定 SQLite 文件名。 | `storage.sqlite.path` | `data-v2.sqlite`；相对路径会在插件文件夹下解析。 | 启动时读取；修改后请重启。 |

```yaml
storage:
  type: sqlite
  sqlite:
    path: data-v2.sqlite
```

路径一定要留在插件文件夹里面。`../economy.sqlite` 这种往外跳的写法会被拒绝，指向别处的绝对路径也不行。这是为了避免打错字就选到主机上不相关的文件。改完要重启。

### MySQL 与 MariaDB

SQL 网络后端只有 `mysql` 这个配置值。插件拿 `host`、`port`、`database` 拼出 JDBC 连接，再开一个 HikariCP 连接池。

| 用途 | 配置键 | 默认值与格式 | 生效时机 |
| --- | --- | --- | --- |
| 数据库主机名。 | `storage.mysql.host` | `localhost`；文本。 | 启动时。 |
| 数据库端口。 | `storage.mysql.port` | `3306`；整数。 | 启动时。 |
| 数据库名称。 | `storage.mysql.database` | `aceeconomy`；文本。 | 启动时。 |
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
    database: "aceeconomy"
    username: "<database-user>"
    password: "<set-locally>"
    pool-size: 10
    max-lifetime: 1800000
```

数据库和账号要先能连上。插件启动时会自己建 v2 需要的数据表，不会拿旧版 v1 的表名来建。数据长什么样子，请看[数据库概念与升级](database.zh-CN.md)。这一组改完都要重启。

## 经济规则

这里管新账户一进来有多少钱，以及默认货币能不能欠钱。插件在创建经济服务时读一次，改完要重启。只做重新加载不会重建服务。

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

直白说：`start-balance` 是新玩家第一次出现时手上的钱，老账户不会被清空。`allow-negative-balance` 关掉就不能欠钱。`default-debt-limit` 是没有特殊权限的玩家最多能欠到多少。

## 货币

`currencies` 区块由服务器管理员自己定义。随附文件写了 `dollar` 和 `token`，照同样格式可以再加。插件会读这个区块里所有合法的组合。每个货币有固定的 ID、显示名称、符号和小数位数。整个区块必须有一个且只有一个 `default: true`，那就是日常流程和 Vault 集成用的默认货币。

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

每个条目都要通过这几条检查：

- 货币 ID 就是 `currencies` 下面的键。去掉前后空格并转成小写后，只能用 `a-z`、`0-9` 和 `_`。只差大小写或空格的 ID（例如 `Dollar` 和 `dollar`）算同一个，会因为重复被拒绝。
- 每个条目四个字段都要写对类型。缺字段或类型错误（例如 `scale` 加了引号变成文本、`default` 写成 `"true"`）都会被拒绝。
- 整个区块至少要有一个货币，而且默认货币刚好一个。

填错会怎样：开服时插件会停下来，并在错误消息里指出哪里错了，不会有改一半的状态。改名称或符号只影响显示。改 ID、小数位数或默认货币会改变以后金额怎么读，动正式服之前先备份并排好时间。

重新加载会先看候选的 `currencies` 属于哪一种，确认安全才会动到线上配置：

| 变更 | 重新加载怎么做 | 为什么 |
| --- | --- | --- |
| 没变 | 重新加载成功，什么都不换。 | 没有东西要套用。 |
| 只改 `name` 或 `symbol` | 直接热套用，新显示文字立刻全服生效。 | 显示文字不影响已存金额、小数位数与默认货币。 |
| 新增货币 | 重新加载拒绝并告知新增的 ID，服务器沿用旧组合。请重启才会生效。 | 既有账户需要批量初始化并支持回退，目前没有存储后端能做到。 |
| 删除货币，或改 `scale`、改 `default` | 重新加载拒绝并告知受影响的 ID，服务器沿用旧组合。请重启才会生效。 | 这些变更会让已存余额被误读或变成孤儿。 |
| 候选区块格式无效 | 重新加载拒绝并告知解析原因，什么都不换。 | 读不懂的候选配置绝不能取代线上登录表。 |

被拒绝的重新加载不会动到运行中的服务器：把文件改回来，或为结构变更安排一次重启，再执行一次 `/aceeco reload`。只有纯显示变更可以在不重启的情况下更新登录表、指令、Vault 桥接与 placeholder 显示。

## 语言与保留的指令设置

| 用途 | 配置键 | 默认值与格式 | 生效时机 |
| --- | --- | --- | --- |
| 选择消息语言。 | `settings.locale` | `zh_TW`；可用 `en_US`、`zh_TW`、`zh_CN`。 | 语言加载或重新加载时。 |
| 管理指令的额外标签。 | `settings.main-command-alias` | `aceeco`；文本，仅限 `a-z`、`0-9`、`-`、`_`。 | 只在启动时生效；修改后请重启。 |

```yaml
settings:
  locale: "en_US"
  main-command-alias: "aceeco"
```

正式主指令是 `/aceeco`。把 `settings.main-command-alias` 设成别的值时，插件会在启动时把那个标签挂为同一个管理指令在 AceEconomy 指令登录表里的额外别名。留空或只打空格会保留默认入口。

这个设置有两条红线：

1. **拒绝冲突。** 配置值不能和插件已在 `plugin.yml` 声明的任何指令标签（主指令：`money`、`pay`、`aceeco`、`withdraw`、`baltop`、`bank`；別名：如 `balance`、`bal`、`balancetop`、`top`、`menu`、`bankmenu`）或其他 AceEconomy 指令名称撞名。撞名会让插件在启动时停下来并报错，不会盖掉原来的入口；这个设置永远抢不走 `/bank` 这类已有指令。
2. **Bukkit 标签是静态的。** 服务器只会转发 `plugin.yml` 里写好的指令标签，那个文件跟着版本发布。自定义别名会先检查，也能在 AceEconomy 自己的指令分派器里认出来，但要在游戏里打了真的送到插件，那个标签必须同时写在 `plugin.yml` 的主指令或别名里；v2.1.0 不会在运行时注册新的 Bukkit 指令。改这个值一定要重启，重新加载不会重新注册指令。

语言文件是 `lang/en_US.yml`、`lang/zh_TW.yml` 和 `lang/zh_CN.yml`。不要把密码或 webhook 网址放进语言文件。

## 排行榜

| 用途 | 配置键 | 默认值与格式 | 注意 |
| --- | --- | --- | --- |
| 启用排行榜功能。 | `leaderboard.enabled` | `true`；布尔值。 | `false` 会在启动时移除可执行的 `/baltop` 处理器；指令标签本身仍静态存在于 `plugin.yml`。修改后请重启。 |
| 控制排行榜缓存重用时间。 | `leaderboard.cache-time-seconds` | `300`；整数秒。 | 值较小会更常更新，值较大减少更新次数。 |
| 设置每页条数。 | `leaderboard.page-size` | `10`；整数。 | 这只控制每页显示数量，不会限制账户数。 |

```yaml
leaderboard:
  enabled: true
  cache-time-seconds: 300
  page-size: 10
```

`enabled: false` 就是排行榜整个关掉。插件不会在自己的登录表里创建 baltop 指令，所以不会跑任何经济代码。但 `plugin.yml` 里的 `baltop` 标签还在，服务器只会回一句用法说明；要让标签彻底消失，得等改了 `plugin.yml` 的新版本。这个开关只在启动时读一次，改完要重启。

`cache-time-seconds` 是排行榜多久重算一次，默认 300 秒。`page-size` 是一页显示几条，默认 10 条。

## 银行界面布局

`bank-gui` 配置段控制 `/bank` 界面：标题、大小，以及每个格子按下去做什么。出货默认值就是以前固定的样子（存款 slot 4、提取 100 slot 11、提取 500 slot 13、关闭 slot 15、大小 27）。

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

填错会怎样：启动时插件会停下来，并在错误里写出精确路径（例如 `bank-gui.actions.withdraw100.amount`），不会有改一半的状态。没有 `bank-gui` 的旧配置在 schema `2.0` 下照样能读，会沿用旧版格子。

布局改完不用重启，做一次重新加载就会换。换的时候会先关掉大家手上开着的银行界面，避免点击卡在新旧规则中间；之后重开的界面就照新规矩。布局写错会让整笔重新加载被拒绝，旧配置原封不动。只有 `bank-gui.enabled` 这个开关要在启动时读，改了要重启。

## 提现超时

`/withdraw` 是把支票交到玩家手上。服务器会先排一个工作，等玩家所在的区域线程有空再执行。如果玩家在工作轮到他之前就下线，这个排好的工作就不会做了。

`withdraw.dispatch-timeout-seconds` 就是在问：这种等不到的工作，最多等几秒。超过就不再等，直接收掉，避免玩家钱被扣了却没拿到支票。正常提现不受影响，工作一做就结束。

| 用途 | 配置键 | 默认值与格式 | 注意 |
| --- | --- | --- | --- |
| 玩家下线、工作做不成时最多等几秒。 | `withdraw.dispatch-timeout-seconds` | `10`；正整数秒。 | 启动时读取一次；修改后请重启。 |

两种等不到的情况，插件都会站在保住玩家钱的那一边。如果是检查背包空间那一步一直没轮到，提现会在扣钱之前直接拒绝。如果是钱已经扣了、送支票那一步一直没轮到，扣掉的钱会自动退回去，指令也会回一个明确的失败，不会让玩家卡在没回应的画面，更不会扣了钱却没拿到支票。`0` 或负数会在读配置时改成 `1` 秒，填错不会让所有提现立刻失败；填正数就照你填的秒数等。

```yaml
withdraw:
  dispatch-timeout-seconds: 10
```

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

webhook 网址和数据库密码一样，都算钥匙。不要贴到 issue 或共享示例里。如果已经外流，先去服务提供商那边换掉，再回来改服务器本机的配置。

## 应用更改

1. 修改存储路径或连接设置前先停止服务器。
2. 复制相关数据文件或创建数据库备份。
3. 编辑 `config.yml`，不要改变 YAML 结构。
4. 启动服务器，查看启动日志中是否有配置或连接错误。

管理员重新加载操作会重新加载配置与语言快照，但不会在后端之间搬移数据，也不会重新打开存储后端。在服务创建时读取的设置——存储方式、`settings.main-command-alias`、`leaderboard.enabled`——会保持启动时的值直到下次重启，重新加载只会在回报中提醒重启，不会偷偷套用；重新加载也不会重新注册指令。货币的显示变更（`name`、`symbol`）会热套用，包括 Vault 桥接与 placeholder 显示；结构变更会被拒绝，请重启后才会生效。
