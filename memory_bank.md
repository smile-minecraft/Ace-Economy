# AceEconomy Project Memory Bank

## Project Overview

**Name**: AceEconomy  
**Type**: Minecraft Economy Plugin  
**Platform**: Paper/Folia 1.21+  
**Language**: Java 21  
**Build**: Gradle (Kotlin DSL)  
**Version**: 1.4.0  

## Tech Stack

- **Framework**: Bukkit/Paper API (Folia-compatible)
- **Database**: SQLite (default), MySQL, JSON (fallback)
- **Connections**: HikariCP connection pool
- **Dependencies**: 
  - Vault API (economy integration)
  - PlaceholderAPI (expansion support)
- **Testing**: JUnit 5 + Mockito

## Core Architecture

### Key Packages
```
com.smile.aceeconomy/
├── api/              # EconomyProvider (public API)
├── commands/         # All command handlers
├── data/             # Data models (Account, Currency, etc.)
├── event/            # Bukkit events
├── exception/        # Custom exceptions
├── gui/              # BankMenu GUI
├── hook/             # Vault, PAPI integrations
├── listener/         # Event listeners
├── manager/          # Business logic managers
├── migration/        # Data migrators
├── service/          # Discord webhook
├── storage/          # Storage layer (SQL/JSON)
└── utils/            # Utilities
```

### Key Managers
- **CurrencyManager**: Thread-safe in-memory cache with RW locks
- **ConfigManager**: Hot-reload config, multi-currency support
- **MessageManager**: MiniMessage i18n (en_US, zh_TW, zh_CN)
- **LogManager**: Transaction logging & rollback
- **LeaderboardManager**: Cached leaderboard with async refresh
- **UserCacheManager**: Offline player UUID lookup
- **PermissionManager**: Permission-based debt limits
- **MigrationManager**: Cross-plugin data migration

### Storage Layer
- **StorageHandler**: Legacy interface for Account
- **StorageProvider**: New async interface with fine-grained queries
- **Implementations**: 
  - SQLiteStorageAdapter (wraps StorageProvider)
  - MySQLImplementation
  - SQLiteImplementation
  - JsonStorageHandler (fallback)

## Implemented Features

### Core Features ✅
- [x] Balance check (`/money`, `/bal`, `/balance`)
- [x] Player transfers (`/pay <player> <amount>`)
- [x] Admin operations (`/aceeco give/take/set`)
- [x] Banknotes (`/withdraw <amount>`)
- [x] Leaderboard (`/baltop`)
- [x] Transaction history & rollback
- [x] Multi-currency support
- [x] Debt/negative balance system
- [x] Discord webhook integration
- [x] Vault API integration
- [x] PlaceholderAPI expansion
- [x] Folia regionized threading
- [x] GUI bank menu (`/bank`)

### Storage Features ✅
- [x] SQLite (default)
- [x] MySQL/MariaDB
- [x] JSON (fallback)
- [x] HikariCP pool
- [x] Auto schema migration

### API Features ✅
- [x] EconomyProvider (async API)
- [x] VaultImpl (sync API for compatibility)
- [x] EconomyTransactionEvent (Bukkit event)
- [x] PlaceholderAPI placeholders

## Missing Features (Priority)

### High Priority (Core Gaps)
- [ ] **Interest System** - Automatic periodic balance increase
- [ ] **Transaction Fees** - Configurable fee on transfers
- [ ] **Tax System** - Transaction tax / income tax
- [ ] **Statistics Dashboard** - Economic data visualization
- [ ] **Bank Accounts** - Separate bank entity (not player balance)
- [ ] **Auto Backup** - Automated backup system
- [ ] **Batch Operations** - Bulk admin commands

### Medium Priority (Enhancement)
- [ ] **Large Transaction Alerts** - Threshold-based notifications
- [ ] **REST API** - Web API for external access
- [ ] **Event API Extensions** - More economy events
- [ ] **Transaction Limits** - Min/max amounts, cooldowns
- [ ] **Cross-Server Sync** - BungeeCord/Velocity support

### Low Priority (Nice to Have)
- [ ] **Economic Security** - Insurance/recovery features
- [ ] **Balance Monitoring** - Alert on suspicious activity
- [ ] **Exchange Rate System** - Dynamic currency conversion
- [ ] **Quest Integration** - Daily quests with rewards

## Test Coverage

### Current Tests
- `PayCommandTest` - Transfer logic tests
- `AdminCommandTest` - Admin command tests
- `CurrencyManagerTest` - Currency logic tests
- `CurrencyTest` - Data model tests
- `LanguageIntegrityTest` - Localization tests

### Test Gaps
- [ ] WithdrawCommand tests
- [ ] BalanceCommand tests
- [ ] BaltopCommand tests
- [ ] BankMenu tests
- [ ] Storage layer tests
- [ ] VaultImpl integration tests
- [ ] EconomyProvider async tests
- [ ] LogManager tests

## Configuration Schema

```yaml
config-version: 1.4
storage:
  type: sqlite | mysql
  mysql: { host, port, database, username, password }
  pool-size: 10
settings:
  locale: zh_TW
  main-command-alias: aceeco
economy:
  allow-negative-balance: true
  default-debt-limit: 0.0
currencies:
  dollar:
    name: 金幣
    symbol: $
    format: "#,##0.00"
    default: true
start-balance: 1000.0
discord:
  enabled: false
  webhook-url: ""
  min-amount: 0.0
leaderboard:
  enabled: true
  cache-time-seconds: 300
  page-size: 10
```

## Commands Reference

| Command | Description | Permission |
|---------|-------------|------------|
| `/money` | Check balance | `aceeconomy.use` |
| `/pay <player> <amount> [currency]` | Transfer money | `aceeconomy.pay` |
| `/withdraw <amount>` | Create banknote | `aceeconomy.withdraw` |
| `/baltop [page]` | View leaderboard | `aceeconomy.command.baltop` |
| `/bank` | Open menu | `aceeconomy.command.bank` |
| `/aceeco give/take/set <player> <amount>` | Admin ops | `aceeconomy.admin` |
| `/aceeco history <player>` | View history | `aceeconomy.admin.history` |
| `/aceeco rollback <player> <tx_id>` | Rollback | `aceeconomy.admin.rollback` |
| `/aceeco reload` | Reload config | `aceeconomy.command.reload` |
| `/aceeco migrate <plugin>` | Data migration | `aceeconomy.admin` |

## PlaceholderAPI Placeholders

| Placeholder | Description |
|-------------|-------------|
| `%aceeco_balance%` | Player's balance (default currency) |
| `%aceeco_balance_formatted%` | Formatted balance |
| `%aceeco_balance_<currency>%` | Balance of specific currency |
| `%aceeco_top_name_<rank>%` | Top N player name |
| `%aceeco_top_balance_<rank>%` | Top N player balance |

## Known Issues / Technical Debt

1. **Storage Layer Duplication**: Dual interfaces (StorageHandler vs StorageProvider)
   - Solution: Migrate to StorageProvider only

2. **Synchronous Vault API**: VaultImpl blocks on async operations
   - Solution: Already uses `.join()`, acceptable for Vault compatibility

3. **No Offline Player Operations**: PayCommand checks but some operations fail offline
   - Solution: Use UserCacheManager for offline lookups

4. **Limited Test Coverage**: Core business logic needs more tests
   - Solution: Add integration tests with MockBukkit

## Development Conventions

### Threading Model (Folia)
- Use `Bukkit.getAsyncScheduler()` for I/O operations
- Use `player.getScheduler()` for entity-specific operations
- Use `Bukkit.getGlobalRegionScheduler()` for global tasks
- Never call blocking I/O on main thread

### Async Pattern
```java
// ✅ Correct
storageHandler.loadAccount(uuid)
    .thenAccept(account -> {
        // async context
    });

// ❌ Wrong (blocks main thread)
Account account = storageHandler.loadAccount(uuid).join();
```

### Memory Management
- Use `WeakReference` for player caches if needed
- Clear caches on player quit
- Use `ConcurrentHashMap` for thread-safe maps

### Database Best Practices
- Always use prepared statements
- Use transactions for multi-row operations
- Close connections in try-with-resources
- Use HikariCP for connection pooling

## Session History

### 2025-01-13 - Initial Analysis
- Completed deep dive into AceEconomy codebase
- Identified 78% feature completeness
- Documented missing features by priority
- Created memory bank and task state files