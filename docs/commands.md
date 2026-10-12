# Commands and permissions

English · [简体中文](commands.zh-CN.md) · [繁體中文](commands.zh-TW.md)

AceEconomy v2 commands always name their subcommand — `/money balance`, not `/money`. This reference covers the player and administrator command surfaces with their syntax, sender rules, permissions, and aliases.

## Contents

- [Quick reference](#quick-reference)
- [For players](#for-players)
- [For administrators](#for-administrators)
- [Common command errors](#common-command-errors)

## Quick reference

| Root command | Subcommand | Usage | Sender | Permission | Alias |
|---|---|---|---|---|---|
| `/money` | `balance` | `[player] [currency]` | Player or console; a console sender must provide `player` | `aceeconomy.command.money` | `/balance` |
| `/money` | `history` | `[currency] [page]` | Player only | `aceeconomy.command.history` | `/balance` |
| `/pay` | `send` | `<player> <amount> [currency]` | Player only | `aceeconomy.command.pay` | None |
| `/withdraw` | `cash` | `<amount> [currency]` | Player only | `aceeconomy.command.withdraw` | None |
| `/baltop` | `top` | `[currency]` | Player or console | `aceeconomy.command.baltop` | None |
| `/bank` | `open` | no arguments | Player only | `aceeconomy.command.bank` | None |
| `/aceeco` | `give`, `take`, `set`, `history`, `reload`, `rollback`, `backup`, `restore`, `import` | See the administrator reference | `reload`, `rollback`, `restore`, and `import`: console only; `backup` and other subcommands: player or console | `aceeconomy.admin` plus the subcommand node | None |

`<required>` values must be supplied. `[optional]` values may be omitted. If `currency` is omitted, the configured default currency is used. Currency IDs are matched without regard to letter case.

Amounts must be valid numbers, greater than zero, within the currency's decimal scale, and no greater than `1,000,000,000,000,000`.

The only v2 root alias listed by the command specification is `/balance` for `/money`. It uses the same `balance` subcommand: `/balance balance [player] [currency]`. The same alias works for history: `/balance history [currency] [page]`. There are no separate `/backup` or `/restore` root commands; use the `/aceeco` subcommands shown below.

## For players

### Check a balance: `/money balance`

Query the balance of your own account or another player's account. Players can omit the player argument to check their own balance; console senders have no account and must provide a target player name.

| Item | Details |
|---|---|
| Usage | `/money balance [player] [currency]` |
| Sender | Player or console; a player may omit `player`, while console must provide it |
| Permission | `aceeconomy.command.money` |
| Alias | `/balance` for the root command; keep the `balance` subcommand |

Examples:

```text
/money balance
/money balance Alex <currency>
/balance balance Alex <currency>
```

### Check your transaction history: `/money history`

View paginated transaction history for the executing player. Restricted to players and only displays the executor's account; additional arguments are rejected with typed errors.

| Item | Details |
|---|---|
| Usage | `/money history [currency] [page]` |
| Sender | Player only; the console has no player account of its own |
| Permission | `aceeconomy.command.history` (with the root `aceeconomy.command.money`) |
| Alias | `/balance` for the root command; keep the `history` subcommand |

Omitting `currency` uses the configured default currency. `page` is 0-based and each page shows 10 entries, newest first — the same paging, ordering, and fields as `/aceeco history`. An empty page gets an explicit reply. An unknown currency and an invalid page are rejected with typed errors.

Examples:

```text
/money history
/money history <currency>
/money history <currency> 2
```

### Send money: `/pay send`

Transfer money to another online player. Restricted to players.

| Item | Details |
|---|---|
| Usage | `/pay send <player> <amount> [currency]` |
| Sender | Player only |
| Permission | `aceeconomy.command.pay` |
| Alias | None listed by the v2 command specification |

Example: `/pay send Alex 25 <currency>`

### Withdraw a banknote: `/withdraw cash`

Withdraw funds from your balance into a physical banknote item. Restricted to players; ensure at least one inventory slot is free before executing.

| Item | Details |
|---|---|
| Usage | `/withdraw cash <amount> [currency]` |
| Sender | Player only |
| Permission | `aceeconomy.command.withdraw` |
| Alias | None listed by the v2 command specification |

Example: `/withdraw cash 100 <currency>`

### View the leaderboard: `/baltop top`

Query the highest balance leaderboard. Can be run by players or console; specify a currency to query non-default balances.

| Item | Details |
|---|---|
| Usage | `/baltop top [currency]` |
| Sender | Player or console |
| Permission | `aceeconomy.command.baltop` |
| Alias | None listed by the v2 command specification |

Examples:

```text
/baltop top
/baltop top <currency>
```

### Open the bank: `/bank open`

Open the bank interface. Takes no arguments and is restricted to players.

| Item | Details |
|---|---|
| Usage | `/bank open` |
| Sender | Player only |
| Permission | `aceeconomy.command.bank` |
| Alias | None listed by the v2 command specification |

Java players open the chest menu. The current GUI action contract is:

- `DEPOSIT`: slot `4` (the upper-middle cell).
- `WITHDRAW`: the existing slots `11` and `13` (the `100` and `500` withdrawal buttons).
- `CLOSE`: slot `15`.

Bedrock players (Geyser + Floodgate) open a native form instead: a home screen with the balance plus deposit, withdraw, and close buttons; withdraw takes an amount and a currency, then requires a confirm step. Closing a form, entering an invalid value, going offline, or a reload/disconnect before answering never executes a transaction. Both interfaces share the same deposit/withdraw path, so nonce, anti-replay, and region guarantees hold on either side. When Floodgate is absent, every player stays on the chest menu.

For a valid v2 banknote, durable replay protection and credit complete before the banknote is removed or its stack is reduced. Invalid, replayed, or credit-failed banknotes remain in the player's inventory. Right-clicking while holding a banknote redeems it through the same atomic path as the bank dashboard deposit button. If the credit commits but the item cannot be removed, the credit stands, and the server audit log records the note id, player, and credited value so the case stays traceable. A multi-note stack is decremented on a copy and written back with a single slot write, so a failed write leaves the full stack in hand: keep it and contact an administrator. Clearing a single note is one slot write whose outcome cannot be verified from the outside, so no promise is made about the slot: keep the note stub and contact an administrator without retrying (the credit is already counted and a replay is rejected). The administrator looks up the note id in the audit log, removes or voids the duplicate note first, and then compensates (for example with `/aceeco give`).

## For administrators

The administrator root is `/aceeco`, with no alias listed by the v2 command specification. The root permission is `aceeconomy.admin`; each operation also declares its own permission node. The mutation commands accept the same player, amount, and optional currency pattern as the player commands. `history` reads recorded transactions instead of changing balances. `reload`, `rollback`, `restore`, and `import` are console-only; `rollback` takes a transaction id, `restore` takes a backup id plus the exact confirmation word `confirm`, and `import` takes a source plus a gate-relative path (writes only with the exact `apply confirm` pair). The six balance-changing operations (`give`, `take`, `set`, `rollback`, `restore`, and `import`) accept an optional trailing `reason` for the audit record; their permissions are unchanged.

| Subcommand | Usage | Sender | Subcommand permission | Alias |
|---|---|---|---|---|
| `give` | `/aceeco give <player> <amount> [currency] [reason]` | Player or console | `aceeconomy.admin.give` | None |
| `take` | `/aceeco take <player> <amount> [currency] [reason]` | Player or console | `aceeconomy.admin.take` | None |
| `set` | `/aceeco set <player> <amount> [currency] [reason]` | Player or console | `aceeconomy.admin.set` | None |
| `history` | `/aceeco history [player] [currency] [page]` | Player or console | `aceeconomy.admin.history` | None |
| `reload` | `/aceeco reload` | Console only | `aceeconomy.admin.reload` | None |
| `rollback` | `/aceeco rollback <transaction-id> [reason]` | Console only | `aceeconomy.admin.rollback` | None |
| `backup` | `/aceeco backup [label]` | Player or console | `aceeconomy.admin.backup` | None |
| `restore` | `/aceeco restore <backup-id> confirm [reason]` | Console only | `aceeconomy.admin.restore` | None |
| `import` | `/aceeco import <essentials\|cmi> <path> [currency] [apply confirm] [reason]` | Console only | `aceeconomy.admin.import` | None |

The declared defaults are `true` for the player command permissions and `op` for the administrator permissions. The plugin also declares `aceeconomy.bypass.debt` with an `op` default for debt-limit bypass access.

### Administrative audit: operator and reason

The audit rule for the six operations above (`give`, `take`, `set`, `rollback`, `restore`, `import`) is: every successful run records who did it and why. The operator is taken from the command sender automatically and is never an argument: a player sender records its UUID with its display name at the time, while the console records a fixed console marker, so one operator cannot pose as another. The optional trailing `reason` is a single token (no spaces); an empty or blank reason is rejected, a reason longer than 256 characters is rejected, and a value that looks like sensitive material (for example an IPv4 or IPv6 address in compressed, full, bracketed, mapped, or zoned form, a URL, or a token) is refused without repeating the value back and without any network lookup. A full-form IPv6 address embedded in a longer token (for example after an `ip=` prefix) is refused the same way, while ordinary single-colon tokens stay allowed. When a restore snapshot carries a corrupt operation record, the refusal names only the record position and the field, never the stored value, and live data is left untouched. That screen is only a best-effort filter and does not claim to catch every sensitive string. When `reason` is omitted, the record keeps the explicit default `unspecified` instead of a blank. When both currency and reason are given, currency comes first and reason comes last. Audit fields are stored together with the transaction in the same atomic commit, and only on success: failed or rolled-back runs leave no orphan audit record. A `restore` run keeps a separate operation record holding both the source and safety-backup ids, committed together with the restore and without creating a zero-value deposit; that record is kept internally and no new public command queries it. Reasons on ordinary operations travel with their transactions and stay reachable through the existing `reasonContains` filter on `/aceeco history`; the separate restore record is not shown as a history entry.

### Add to a balance: `/aceeco give`

Use `give` when an administrator needs to add an amount to a player's balance. An optional trailing reason may be added for the audit record; see the audit rule above.

Example: `/aceeco give Alex 500 <currency>`. With a reason: `/aceeco give Alex 500 <currency> monthly-reward`.

### Remove from a balance: `/aceeco take`

Use `take` when an administrator needs to subtract an amount from a player's balance. An optional trailing reason may be added for the audit record; see the audit rule above.

Example: `/aceeco take Alex 125 <currency>`. With a reason: `/aceeco take Alex 125 <currency> penalty-case`.

### Set a balance: `/aceeco set`

Use `set` when an administrator needs to assign a player's balance to a specific amount. An optional trailing reason may be added for the audit record; see the audit rule above.

Example: `/aceeco set Alex 1000 <currency>`. With a reason: `/aceeco set Alex 1000 <currency> balance-fix`.

### Query the transaction history: `/aceeco history`

Use `history` when an administrator needs to review recorded balance changes. It is read-only: it never changes balances or audit records. Omitting `player` lists transactions for every account; omitting `currency` uses the configured default currency; `page` is 0-based and each page shows 10 entries. An empty page, an unknown player, and a page number below zero each get an explicit reply.

| Item | Details |
|---|---|
| Usage | `/aceeco history [player] [currency] [page]` |
| Sender | Player or console |
| Permission | `aceeconomy.admin.history` |
| Ordering | Newest first, with a stable tie-break so repeated queries list rows in the same order |

Examples:

```text
/aceeco history
/aceeco history Alex
/aceeco history Alex <currency>
/aceeco history Alex <currency> 2
```

### Reload the economy configuration: `/aceeco reload`

Run this from the console after changing economy configuration. It takes no arguments and cannot be run by a player.

Example: `/aceeco reload`

### Roll back a transaction: `/aceeco rollback`

Use `rollback` when an administrator needs to reverse a recorded transaction by its id. This is a destructive administrative action, so it is console-only and requires both `aceeconomy.admin` and `aceeconomy.admin.rollback`. The transaction id is the UUID shown for the transaction; it must be a valid UUID or the command is rejected before anything is touched. A rollback follows the same debt policy as ordinary transactions: when `economy.allow-negative-balance` is `false`, a reversal that would drive a balance negative is refused and nothing is changed; when `true`, the reversal may go negative within the configured debt limit. An optional trailing reason may be added; it is recorded with the reversal (see the audit rule above).

Outcomes are reported explicitly:

| Outcome | Reply |
|---|---|
| Success | Names the rolled back transaction and lists the reversal audit record ids. |
| Already reverted | States the transaction was already reverted and no changes were made; re-running the same id is a safe no-op and never duplicates balance effects or audit records. |
| Unknown transaction | Typed error: no transaction with that id exists. |
| Invalid id | Typed error: the argument is not a valid UUID; nothing is looked up. |
| Missing transfer counterpart | Typed error: one leg of a transfer could not be located, so a safe reversal is impossible. |
| Debt refused | Typed error: the reversal would drive a balance negative while `economy.allow-negative-balance` is `false` (or past the debt limit); nothing was changed and the transaction stays retryable. |
| Execution failed | Typed error: the reversal was not applied and the transaction stays retryable. |
| Marker persist failed | Typed error stating the reversal may already be applied while the reverted marker is missing; inspect storage and reconcile manually before any retry. |

Example: `/aceeco rollback 0b5f8a2e-1c3d-4e5f-6a7b-8c9d0e1f2a3b`. With a reason: `/aceeco rollback 0b5f8a2e-1c3d-4e5f-6a7b-8c9d0e1f2a3b duplicate-payout`.

The rollback command is wired into the production command surface and covered by automated contract tests, but it has **not** been verified on a live server yet: Folia/Bukkit bridge execution, live MySQL storage, and fault-injection drills with real data are still open release gates. Treat the outcomes above as the designed contract until that validation is done.

### Create a logical backup: `/aceeco backup`

Use this to create a v2 logical JSON snapshot while the server is running. The optional label is written into the generated backup id and is restricted to safe filename characters.

| Item | Details |
|---|---|
| Usage | `/aceeco backup [label]` |
| Sender | Player or console |
| Permission | `aceeconomy.admin.backup` (with the root `aceeconomy.admin`) |
| Storage | Plugin-controlled `<plugin data folder>/backups` directory |
| Output | Atomic, never-overwriting snapshot; the command reports its backup id |

The snapshot is a logical v2 JSON model. It includes accounts, balances, transactions, reverted markers, and consumed nonces, but not database passwords or webhook URLs. There is no separate root `/backup` command.

### Restore a logical backup: `/aceeco restore`

Restore replaces the live economy state, so it is console-only and requires both the root and child permissions. The confirmation token is case-sensitive: only lowercase `confirm` is accepted. An optional trailing reason may be added; the run keeps a separate operation record as described in the audit rule above.

| Item | Details |
|---|---|
| Usage | `/aceeco restore <backup-id> confirm [reason]` |
| Sender | Console only; restore is rejected while any player is online |
| Permission | `aceeconomy.admin.restore` (with the root `aceeconomy.admin`) |
| Preflight | JSON shape, schema version, records (including the restore operation records), and configured-currency compatibility are checked before live data is touched; a corrupt snapshot leaves the live state unchanged |
| Safety | A safety backup of the current state is created first; if it fails, restore is aborted |
| Success boundary | The leaderboard cache is cleared, but sessions and GUIs are not hot-refreshed. Restart the server before players return. |

Example: `/aceeco restore 20260824T093000-aaaa1111 confirm`. With a reason: `/aceeco restore 20260824T093000-aaaa1111 confirm wrong-payout`.

### Import balances: `/aceeco import`

Use `import` when balances from an EssentialsX or CMI server must be carried into v2. The command is console-only and requires both `aceeconomy.admin` and `aceeconomy.admin.import`. Without the exact `apply confirm` pair it is a dry-run preview: nothing is written, no backup is taken, and no idempotency state is consumed. An optional trailing reason may follow in either the preview or the apply form; on apply it is recorded with the run (see the audit rule above).

| Item | Details |
|---|---|
| Usage | `/aceeco import <essentials\|cmi> <path> [currency] [apply confirm] [reason]` |
| Sender | Console only |
| Permission | `aceeconomy.admin.import` (with the root `aceeconomy.admin`) |
| Source input | Essentials: a `<uuid>.yml` userdata file or a directory of them (`money:` balance, optional `last-account-name:`). Supported: EssentialsX 2.x userdata. CMI: an operator-prepared UTF-8 balance sheet (`uuid,name,balance` per line, header optional, `.csv`/`.txt`). The raw `cmi.sqlite.db` binary is not supported and is rejected. |
| Path | Relative to the plugin-controlled `<plugin data folder>/import` directory. Absolute paths, `..`, symlinks, missing entries, oversized files, sensitive names, and per-source unsupported extensions are rejected before anything is read. |
| Currency | Omitting it uses the configured default currency. An unknown id aborts the run before any backup is taken. |
| Apply | Only `apply confirm` (exact lowercase `confirm`) writes. A `pre-import` safety backup is taken first; if it fails, nothing is applied. Re-running the same source is idempotent: already-applied records are reported as skipped. |
| Report | `applied` / `skipped` / `failed` counts plus a failure summary. Any failure means the run is not fully successful. |

Examples:

```text
/aceeco import essentials userdata
/aceeco import essentials userdata coin apply confirm
/aceeco import cmi balances.csv
/aceeco import cmi balances.csv coin apply confirm
/aceeco import cmi balances.csv coin apply confirm migration-topup
```

Copy the Essentials `plugins/Essentials/userdata/` files (or the prepared CMI sheet) into `plugins/AceEconomy/import/` first; the command never reads outside that directory. See the `import` row in the administrator table above.

## Common command errors

| What happened | What to check |
|---|---|
| Permission denied | The sender does not have the permission shown for the root or subcommand. |
| Wrong sender | Run player-only commands in game. Run `/aceeco reload`, `/aceeco rollback`, `/aceeco restore`, and `/aceeco import` from the console; `restore` also requires no players to be online. |
| Missing or extra arguments | Use the exact subcommand and usage line. For example, `/baltop` needs `top`; it does not take a page number. |
| Unknown player | Check the player name and try again. |
| Unknown currency | Use a configured currency ID. Omitting it uses the configured default. |
| Invalid amount | Use a number that is positive, within the currency scale, and within the command limit. |
| Economy operation rejected | Read the returned error and correct the account or economy condition, such as insufficient funds or a debt limit. |
| Rollback rejected | Check the typed error: a valid transaction UUID is required, an already reverted transaction is a no-op, and a marker failure needs manual reconciliation before retrying. |
| Restore confirmation rejected | Use the exact lowercase word `confirm`: `/aceeco restore <backup-id> confirm [reason]`. `CONFIRM`, `Confirm`, and other spellings are rejected. |
| Restore rejected because players are online | Run restore from the console after all players have left. |
| Restore safety or snapshot check failed | Keep the live data unchanged, inspect the typed error, and do not delete the current store to force a restore. |
| Import confirmation rejected | Without the exact pair `apply confirm` the command only previews. Re-run as `/aceeco import <essentials\|cmi> <path> [currency] apply confirm [reason]`. |
| Import path rejected | The path must be relative to `<plugin data folder>/import`. Absolute paths, `..`, symlinks, sensitive names, and unsupported extensions are refused before anything is read. |
| Audit reason rejected | Use a single token (`reason`) of at most 256 characters with no spaces, or omit it; values that look like sensitive material are refused without repeating them back. |
| Import reports failures | Read the failure summary: unknown formats, invalid numbers, and negative balances never become silent zeroes. Re-running after a fix only applies what is still missing. |
