# Configuration guide

English · [简体中文](config.zh-CN.md) · [繁體中文](config.zh-TW.md)

AceEconomy reads `config.yml` as a versioned YAML file. This page explains what each setting is for, when it takes effect, and which values are safe to use. The examples use placeholders for secrets; replace them only in the server's private copy.

## Contents

- [Before editing](#before-editing)
- [Storage choice](#storage-choice)
- [Economy rules](#economy-rules)
- [Currencies](#currencies)
- [Locale and retained command setting](#locale-and-retained-command-setting)
- [Leaderboard](#leaderboard)
- [Bank GUI layout](#bank-gui-layout)
- [Discord and secret boundaries](#discord-and-secret-boundaries)
- [Applying changes](#applying-changes)

## Before editing

The file uses the v2 configuration format:

```yaml
version: "2.0"
```

Keep the YAML nesting and key names intact. A missing value receives the schema default where a default is defined. A storage type that is not `json`, `sqlite`, or `mysql` is rejected instead of silently selecting another backend.

| Purpose | Key | Default and format | Takes effect |
| --- | --- | --- | --- |
| Identify the configuration format. | `version` | `"2.0"`; quoted major/minor text. | When the configuration is loaded. |

Do not replace `version: "2.0"` with the persistence schema value `1`; they describe different layers.

## Storage choice

Storage determines where accounts, balances, and transaction records live. Choose it before putting the server into normal operation, because changing the backend does not itself convert existing data.

### `storage.type`

| Purpose | Key | Default and format | Takes effect |
| --- | --- | --- | --- |
| Select the persistence backend. | `storage.type` | `json`; one of `json`, `sqlite`, `mysql`. | At plugin startup. Restart after changing it. |

Use JSON for a simple single-server installation. Use SQLite when you want one local database file. Use MySQL for a server that already operates a database service or needs its data outside the plugin folder. MariaDB has no separate config value; configure a MariaDB service through the `mysql` backend.

### JSON

JSON is the default and stores the v2 model in one file under the plugin data folder. It needs no connection settings.

```yaml
storage:
  type: json
```

The file is `data-v2.json`. Keep it with the plugin's data directory when backing up or moving the server.

### SQLite

| Purpose | Key | Default and format | Takes effect |
| --- | --- | --- | --- |
| Choose the SQLite file name. | `storage.sqlite.path` | `data-v2.sqlite`; a relative path is resolved under the plugin data folder. | At startup. Restart after changing it. |

```yaml
storage:
  type: sqlite
  sqlite:
    path: data-v2.sqlite
```

The path must remain inside the plugin data folder. Paths such as `../economy.sqlite` and absolute paths outside that folder are rejected. This keeps a configuration typo from selecting an unrelated file on the host.

### MySQL and MariaDB

`mysql` is the only SQL network backend value. It builds a JDBC connection from `host`, `port`, and `database`, then creates a HikariCP connection pool.

| Purpose | Key | Default and format | Takes effect |
| --- | --- | --- | --- |
| Database server name. | `storage.mysql.host` | `localhost`; text. | Startup. |
| Database server port. | `storage.mysql.port` | `3306`; integer. | Startup. |
| Database name. | `storage.mysql.database` | `aceeconomy`; text. | Startup. |
| Database user. | `storage.mysql.username` | `root`; text. Use a dedicated account for a live server. | Startup. |
| Database password. | `storage.mysql.password` | Empty string in the shipped example; set it privately. | Startup. |
| Maximum pool size. | `storage.mysql.pool-size` | `10`; positive integer. | Startup. |
| Maximum connection lifetime. | `storage.mysql.max-lifetime` | `1800000`; positive milliseconds (30 minutes). | Startup. |

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

The database and user must already be available to the server. The plugin creates the v2 tables when it initializes the SQL backend; it does not use the old v1 table names as a v2 setup script. See [Database concepts and upgrades](database.md) for the data model.

## Economy rules

These values shape new accounts and the default currency's debt policy. They are read when the economy services are built, so restart after changing them rather than assuming a reload will rebuild live services.

| Purpose | Key | Default and format | Notes |
| --- | --- | --- | --- |
| Allow the balance of the default currency to go below zero. | `economy.allow-negative-balance` | `true`; boolean. | When `false`, the debt policy is disabled. |
| Set the default debt limit. | `economy.default-debt-limit` | `0.0`; decimal amount. | Used when the player has no permission-specific debt setting. |
| Give a new account its initial amount in the default currency. | `start-balance` | `1000.0`; decimal amount. | Existing accounts are not reset by changing this value. |

```yaml
economy:
  allow-negative-balance: true
  default-debt-limit: 0.0
start-balance: 1000.0
```

## Currencies

The `currencies` section is an operator-owned map. The shipped file defines `dollar` and `token`, and any additional currency can be added the same way; the plugin loads whatever legal map the section contains. Each entry gives the application a stable ID, a display name, a symbol, and a decimal scale. Exactly one entry must have `default: true`; that entry supplies the default currency used by the general economy flow and Vault integration.

| Purpose | Key | Default and format | Notes |
| --- | --- | --- | --- |
| Human-readable name. | `currencies.<id>.name` | Text; required for every currency. | The ID is the key below `currencies`; keep it stable once data exists. |
| Display symbol. | `currencies.<id>.symbol` | Text; required for every currency. | Used beside amounts in user-facing output. |
| Number of fractional digits. | `currencies.<id>.scale` | Non-negative integer; required for every currency. | Amounts with more fractional digits are not implicitly rounded. |
| Select the default currency. | `currencies.<id>.default` | Boolean; exactly one `true` across the section. | Keep exactly one default. |

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

Validation rules that apply to every entry:

- The currency ID is the key under `currencies`. After trimming surrounding spaces and converting to lower case it must consist only of `a-z`, `0-9`, and `_`. IDs that differ only in case or spacing (for example `Dollar` and `dollar`) count as the same currency and are rejected as duplicates.
- Every entry must define all four fields with the declared types; missing fields or values of the wrong type (a quoted number for `scale`, a quoted boolean for `default`) are rejected.
- The section must contain at least one currency and exactly one default.

A config that violates these rules stops the plugin at startup with an error naming the problem; nothing is partially applied. Changing a name or symbol affects presentation. Changing an ID, scale, or default currency affects how later operations interpret amounts, so make a backup and plan the change before applying it to a live economy.

The reload path classifies the candidate `currencies` section before touching anything live:

| Change | What reload does | Why |
| --- | --- | --- |
| Nothing changed | Reload succeeds; nothing is swapped. | There is nothing to apply. |
| Only `name` or `symbol` changed | Hot-applied: the new display text takes effect everywhere at once. | Display text never affects stored amounts, scales, or the default currency. |
| A currency was added | Reload is refused with a reason naming the new ID; the server keeps running on the old set. Restart to apply. | Existing accounts need batch initialization with rollback support, which no storage backend currently offers. |
| A currency was removed, or a `scale` or `default` flag changed | Reload is refused with a reason naming the affected ID; the server keeps running on the old set. Restart to apply. | Those changes would reinterpret or orphan stored balances. |
| The candidate section is invalid | Reload is refused with the parser reason; nothing is swapped. | An unparsable candidate must never replace the live registry. |

A refused reload leaves the running server exactly as it was: fix the file, or schedule a restart for the structural change, and run `/aceeco reload` again. Only the display-only case updates the registry, commands, Vault bridge, and placeholder expansion without a restart.

## Locale and retained command setting

| Purpose | Key | Default and format | Takes effect |
| --- | --- | --- | --- |
| Select message language. | `settings.locale` | `zh_TW`; one of `en_US`, `zh_TW`, `zh_CN`. | On language load or reload. |
| Additional label for the admin command. | `settings.main-command-alias` | `aceeco`; text matching `a-z`, `0-9`, `-`, `_`. | At startup only; restart after changing it. |

```yaml
settings:
  locale: "en_US"
  main-command-alias: "aceeco"
```

The formal root command is `/aceeco`. Setting `main-command-alias` to another value attaches that label as an additional alias of the same admin command inside AceEconomy's command registry at startup. An empty or blank value keeps the default entry point.

Two boundaries apply:

1. **Collision rejection.** The value must not collide with any command label the plugin already declares in `plugin.yml` (the roots `money`, `pay`, `aceeco`, `withdraw`, `baltop`, `bank` and their aliases such as `balance`, `bal`, `balancetop`, `top`, `menu`, `bankmenu`) nor with another AceEconomy command name. A collision stops the plugin at startup with a clear error instead of overriding the existing entry; `/bank` and the other shipped entries can never be taken over by this setting.
2. **Static Bukkit labels.** The server only delivers command labels that exist in `plugin.yml`, which ships fixed with the release. A custom alias is validated and resolvable inside AceEconomy's dispatcher, but typing it in-game reaches the plugin only after the label is also declared as a root/alias in `plugin.yml`; v2.1.0 does not register new Bukkit commands at runtime. Changing the value always requires a restart, and reload never re-registers commands.

The language files are named `lang/en_US.yml`, `lang/zh_TW.yml`, and `lang/zh_CN.yml`. Do not put passwords or webhook URLs in language files.

## Leaderboard

| Purpose | Key | Default and format | Notes |
| --- | --- | --- | --- |
| Make leaderboard features available. | `leaderboard.enabled` | `true`; boolean. | `false` removes the executable `/baltop` handler at startup; the label itself is static in `plugin.yml`. Restart after changing it. |
| Control how long a cached result is reused. | `leaderboard.cache-time-seconds` | `300`; integer seconds. | A shorter value refreshes more often; a longer value reduces refresh work. |
| Set entries per page. | `leaderboard.page-size` | `10`; integer. | This controls the page size, not the number of stored accounts. |

```yaml
leaderboard:
  enabled: true
  cache-time-seconds: 300
  page-size: 10
```

With `enabled: false` the plugin does not build or attach the baltop command spec in its registry, so no economy code runs for it. Because `plugin.yml` still declares the static `baltop` label, the server answers with the plain usage line instead; fully removing the label requires a release that changes `plugin.yml`. The toggle is read once at startup; restart after changing it.

## Bank GUI layout

The `bank-gui` section controls the `/bank` inventory: its title, size, and which slot performs which action. The shipped defaults reproduce the previous fixed behaviour (deposit on slot 4, withdraw 100 on slot 11, withdraw 500 on slot 13, close on slot 15, size 27).

| Purpose | Key | Default and format | Notes |
| --- | --- | --- | --- |
| Turn the bank interface on or off. | `bank-gui.enabled` | `true`; boolean. | `false` makes `/bank` do nothing. Restart after changing it. |
| Title language key. | `bank-gui.title-key` | `gui.bank-title`; non-blank language key. | Rendered through the safe component pipeline, never parsed as raw MiniMessage. |
| Inventory size. | `bank-gui.size` | `27`; one of `9`, `18`, `27`, `36`, `45`, `54`. | Every button slot must be inside `[0, size)`. |
| Button slot. | `bank-gui.actions.<name>.slot` | Integer; required for every action. | Slots must be unique across the section. |
| Button behaviour. | `bank-gui.actions.<name>.type` | One of `deposit`, `withdraw`, `close`, `none`. | `none` reserves a slot without an action. |
| Withdraw face value. | `bank-gui.actions.<name>.amount` | Positive integer; required for `withdraw`. | Must be absent for every other type. |
| Withdraw currency. | `bank-gui.actions.<name>.currency` | Known currency id; required for `withdraw`. | Absent means the runtime default currency. Must be absent for every other type. |
| Button display item. | `bank-gui.actions.<name>.material` | Legal Bukkit material name; required except for `none`. | Air is rejected. |
| Button display text. | `bank-gui.actions.<name>.name-key` / `lore-keys` | Language keys; name required except for `none`, lore optional. | Operator input is never parsed as MiniMessage. |

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

A config that violates these rules stops the plugin at startup with an error naming the exact path (for example `bank-gui.actions.withdraw100.amount`); nothing is partially applied. A config without `bank-gui` keeps loading under schema `2.0` and receives the legacy slot behaviour.

A valid `bank-gui` candidate is accepted as part of the reload: open bank sessions are closed first so no click can run with half old, half new rules, and sessions opened afterwards resolve clicks against the new layout. An invalid layout refuses the whole reload and leaves the previous configuration untouched. The `bank-gui.enabled` toggle itself still takes effect only at startup; restart after changing it.

## Discord and secret boundaries

| Purpose | Key | Default and format | Notes |
| --- | --- | --- | --- |
| Turn webhook notifications on or off. | `discord.enabled` | `false`; boolean. | Keep it `false` until a private endpoint is configured. |
| Identify the Discord webhook endpoint. | `discord.webhook-url` | Empty string by default; URL text. | Treat the complete URL as a credential. |

```yaml
discord:
  enabled: false
  webhook-url: "https://discord.com/api/webhooks/<set-locally>"
```

Never publish a real webhook URL, database password, or connection details with credentials. Do not paste them into issue reports or shared examples. If a secret is exposed, replace it at the provider and update the private configuration.

## Applying changes

1. Stop the server before changing storage paths or connection settings.
2. Make a copy of the relevant data file or database backup.
3. Edit `config.yml` without changing the YAML structure.
4. Start the server and check the startup log for configuration or connection errors.

The administrative reload action reloads the configuration and language snapshots. It does not move data between backends or reopen the storage backend. Settings that are captured while services are created — storage selection, `settings.main-command-alias`, and `leaderboard.enabled` — keep their startup values until the next restart and are reported as restart notes instead of being applied; a reload never re-registers commands. Currency display changes (`name`, `symbol`) are hot-applied, including the Vault bridge and placeholder expansion; structural currency changes are refused and need a restart.
