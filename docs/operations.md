# AceEconomy v2 server operations

English · [简体中文](operations.zh-CN.md) · [繁體中文](operations.zh-TW.md)

Use this checklist to start a server, change configuration safely, confirm storage, create or restore backups, and respond to incidents without deleting data first.

## Contents

- [Start-of-day checks](#start-of-day-checks)
- [Choosing and checking storage](#choosing-and-checking-storage)
- [Safe configuration changes](#safe-configuration-changes)
- [Routine commands](#routine-commands)
- [Backups and restore](#backups-and-restore)
- [Integrations](#integrations)
- [Stop, restart, and reopen](#stop-restart-and-reopen)
- [Emergency rollback](#emergency-rollback)
- [Live validation](#live-validation)
- [Handing a problem to support](#handing-a-problem-to-support)

## Start-of-day checks

After a normal start or restart, check the server console for `AceEconomy v2.2.0` and `AceLib v1.2.1`. Confirm that only one AceLib version is loaded. Then use a test account to run `/money balance` and, when appropriate, `/baltop top`.

If the server is not ready, do not open it to players. Keep the first AceEconomy, AceLib, or storage error and follow [Troubleshooting](troubleshooting.md).

## Choosing and checking storage

The v2 backend is selected by `storage.type`:

| Value | Location | Good fit |
|---|---|---|
| `json` | `plugins/AceEconomy/data-v2.json` | One server with a local file |
| `sqlite` | `storage.sqlite.path` under the plugin data folder | One server with a SQLite database |
| `mysql` | `storage.mysql.*` | A single server using a managed MySQL or MariaDB service |

Keep the SQLite path inside `plugins/AceEconomy/`. For MySQL, keep `pool-size` and `max-lifetime` under `storage.mysql`, and keep the password outside shared documentation.

The same v2 account and transaction model is used for the supported backends.

One store serves one live server. Do not point two servers at the same JSON file, SQLite file, or MySQL database: that layout is not supported and can diverge without an error. See [Persistence, backup, and restore](persistence.md#one-server-per-store).

## Safe configuration changes

Edit `plugins/AceEconomy/config.yml` while the server is running only when your normal change process protects the file. Make a copy before editing, keep `version: "2.0"`, and validate the YAML shape before applying it.

Use the console command for ordinary configuration or language changes:

```text
/aceeco reload
```

The reload reports `AceEconomy reloaded` on success and keeps the last valid in-memory snapshot if the new configuration or language files cannot be loaded. After changing `storage.type`, the SQLite path, MySQL connection values, plugin JARs, AceLib, or optional plugins, stop and restart the full server instead.

Reload runs as a single transaction: the configuration, language, currency, and bank-GUI candidates are each validated first, and the plugin swaps them in together only when all of them pass. If any candidate fails, nothing is swapped and the server keeps running on the previous configuration.

Currency edits are sorted before the swap:

| Change | What reload does | Why |
| --- | --- | --- |
| Nothing changed | Reload succeeds; nothing is swapped. | There is nothing to apply. |
| Only `name` or `symbol` changed | Hot-applied: the new display text takes effect everywhere at once. | Display text never affects stored amounts, scales, or the default currency. |
| A currency was added | Reload is refused with a reason naming the new ID; the server keeps running on the old set. Restart to apply. | Existing accounts need batch initialization with rollback support, which no storage backend currently offers. |
| A currency was removed, or a `scale` or `default` flag changed | Reload is refused with a reason naming the affected ID; the server keeps running on the old set. Restart to apply. | Those changes would reinterpret or orphan stored balances. |
| The candidate section is invalid | Reload is refused with the parser reason; nothing is swapped. | An unparsable candidate must never replace the live registry. |

Three settings are restart-only and are never applied by reload. If `settings.main-command-alias`, `storage.type`, or `leaderboard.enabled` differs from the running value, the reload still succeeds but the reply carries a restart note naming it; the live value stays unchanged until the next full restart.

The reply tells you what happened. A failure shows the refusal reason — for example which currency ID was added or which scale changed — so you know whether to fix the file or schedule a restart. A success appends notes: what was hot-applied (display text, layout, configuration and language) and what still needs a restart.

A successful reload rebuilds every open bank view with the new layout and reopens it in place, so no click can run with half old, half new rules; players do not need to run `/bank` again. A view whose rebuild or reopen fails is closed and its player is told, both on a best-effort basis. An open racing the reload waits out the swap and builds from the current layout either way.

The same reload drops the whole synchronous balance cache. That is accepted behaviour, not a bug: Vault reads never block on storage, so until the next persisted read or successful write re-primes an entry, a balance query falls back to the safe default `0.0`. There is no synchronous refill, because refilling on the calling thread would reintroduce the blocking I/O the cache exists to avoid.

Do not use Bukkit `/reload` as a maintenance or upgrade shortcut.

## Routine commands

Use these forms when checking a live server:

```text
/money balance [player] [currency]
/pay send <player> <amount> [currency]
/withdraw cash <amount> [currency]
/baltop top [currency]
/bank open
/aceeco give <player> <amount> [currency]
/aceeco take <player> <amount> [currency]
/aceeco set <player> <amount> [currency]
```

Run a small, reversible check with a test account rather than changing a real player's balance. Record administrator balance changes in the server's normal administration notes.

## Backups and restore

Managed logical snapshots and manual disaster-recovery copies are different procedures. Do not use the instructions for one as a substitute for the other.

### Manual file and database disaster recovery

Before directly copying a JSON or SQLite data file, stop the server. Keep that manual copy outside the live plugin folder, use a date and purpose in its name, and do not replace the only known-good copy. This is a file-level disaster-recovery copy, not the managed `/aceeco backup` command.

For MySQL or MariaDB native/physical backups, follow the database administrator's normal backup process and keep the result with the matching server configuration backup. Do not treat a native or physical database backup as the managed v2 logical snapshot. Normal operation on the live MySQL/Folia combination has been exercised (see [Live validation](#live-validation)); native/physical disaster-recovery workflows have not been validated here, so verify them in a controlled drill before relying on them in production.

### Managed commands

The console and authorized administrators can create a managed logical snapshot:

```text
/aceeco backup [label]
```

This command can run while the server is running. It writes a credential-free v2 JSON logical snapshot under the plugin-controlled `<plugin data folder>/backups/` directory using a verified secure directory handle. It creates `<backup-id>.json` with handle-relative `CREATE_NEW`, writes and forces the complete content, then creates `<backup-id>.ready` with handle-relative `CREATE_NEW`. The ready file contains a SHA-256 digest and is the application-level logical commit point; restore requires the marker and a matching, fully validated JSON snapshot. Existing target or marker names are never replaced.

The optional label accepts only letters, digits, `.`, `_` and `-`. The snapshot contains accounts, balances, transactions including reverted markers, consumed nonces, and managed banknote issuance progress, never storage passwords or webhook URLs. For MySQL it reads through the live connection; it is a logical snapshot, not a `mysqldump`/`mariabackup` native or physical backup.

Snapshot publication requires a filesystem that supports secure directory handles, no-follow attribute checks, regular-file checks, and forced file channels. The protocol is an application-level commit-marker protocol; it does not claim an OS atomic rename or hard-link publication. On an unsupported filesystem, or after a partial target/marker failure, the command fails closed instead of falling back to an unsafe write. Keep the `.json` and matching `.ready` files together when moving a snapshot; a bare JSON file is not a committed backup. An unmarked orphan may remain and restore will reject it.

Restoring is destructive and strictly gated:

```text
/aceeco restore <backup-id> confirm
```

- Console only; requires `aceeconomy.admin` plus `aceeconomy.admin.restore`, and the literal word `confirm` exactly.
- Rejected while any player is online; ask everyone to leave first.
- Before touching live data it validates the snapshot and writes a safety backup of the current state. If the safety backup fails, nothing is restored.
- The managed issuance guard requires every live managed record to appear identically in the candidate. A pre-upgrade candidate without issuance progress may only replace live progress that cannot leave a redeemable note behind (deduction-free records, or redeemed or aborted records). A live delivered, possibly-delivered, refunded-voided or undecidable record refuses the empty candidate, so the balance cannot revert while a physical note stays or becomes redeemable again.
- A live delivered or possibly-delivered record is unblocked only by a genuine redemption that marks the managed record redeemed. Refunded-voided and quarantined (undecidable) records have no formal unblock path and need separate administrator handling (for example under the incident procedure), so settling the physical note alone does not guarantee a rollback. If the redeem observation fails after the credit committed (logged loudly), the record stays delivered and keeps refusing the empty candidate. A candidate newcomer carrying debit evidence must reference its authoritative durable debit in the same snapshot. Plain nonces are not covered by this guard.
- The guard never promises that a nonce cannot be presented again: a restore rolls balances and plain nonces back together, and physical notes live in player inventories outside the economy snapshot. The guard promises only that a restore never leaves an already-delivered, possibly-delivered or refunded-voided managed note redeemable after the balance is rolled back, and never invents a refund. A refunded or voided note is disabled on the ledger because its nonce is consumed, but the physical note may still be kept and presented again — that is why refunded-voided records refuse the empty candidate just like delivered ones. Restoring a pre-upgrade backup over safe progress drops that progress with the swap, so no refund can later be issued from a dropped record.
- Known delivery window: the note is placed into the inventory before the delivered mark is stored, so stopping the process after the mark is stored but before the world and player data are saved leaves the player charged with neither note nor refund; always stop cleanly and wait for world and plugin saving to finish.
- While startup recovery is incomplete, new managed issuance and managed redemption stay blocked with a loud diagnosis; queries and legacy v2 notes stay allowed.
- On success the old state's safety backup ID is reported and the leaderboard cache is cleared. Restart the server before letting players back in; open sessions and GUIs are not hot-refreshed by design.

There are no separate `/backup` or `/restore` root commands; these operations exist only as `/aceeco` admin subcommands.

The managed restore does not require a separate manual shutdown or file copy before running the command. It is still best scheduled in a maintenance window. The no-online-player gate, preflight, and pre-restore safety backup protect the managed operation; a malformed or incompatible snapshot must not be used as a reason to delete live data.

## Safety control: freeze and read-only

Two levels stop money without stopping queries. Freezing one account refuses every initiating balance change that touches it: Vault deposit and withdraw, both legs of a transfer, ticket issue and redeem, administrative `give`/`take`/`set` with no override, an import apply or rollback touching the frozen account, and a restore whose live-plus-candidate union touches a frozen account (refused as a whole inside the same exclusive window that publishes, so a snapshot that omits a live frozen account cannot delete it). Global read-only refuses every initiating balance change on every account. Balance, history, and leaderboard queries, dry-run previews, and backups never consult the gate. A refused transfer deducts nothing; a refused redeem keeps its note. A failed mint that observably never exposed a note refunds through a single-use trusted authorization (nonce, account, currency, amount, purpose) exempt from the gate; anything unknown, undeducted, already redeemed, tampered, replayed, already spent, uncertain, cross-purpose, or presented without a bound ledger is refused, and ordinary deposits never ride that exemption. The purpose is bound at authorize time (failed mint versus uncertain delivery) and checked together with the four fields on every claim and retry; a cross-purpose claim is refused with no side effects and never consumes the correct authorization. The claim settles exactly once: a committed refund settles it as refunded, an unproven outcome as uncertain (retained, never re-authorized), and only a definitely-uncommitted failure releases the same claim with the same purpose for retry. An uncertain handover (the region callback threw, so the note may or may not have landed) never takes that plain path: a delivery-uncertain authorization voids the nonce and refunds in one storage transaction instead, still exempt from the gate, so a redeemable note and a refund never exist side by side.

State lives in `<plugin data folder>/security-control.json` beside the data folder, never inside an economy snapshot. State and its control audits publish together atomically (temporary file plus atomic move, then durability sync); a failure before the replacement applies neither. A filesystem without atomic-move support refuses the publish outright instead of replacing non-atomically. A sync failure after the replacement landed adopts the new state instead of claiming nothing was applied. A first start without the file opens writable. Every field the publisher writes (format, version, globalReadonly, frozen, audits with their ids, operations, actors, reasons, timestamps and targets) must be present, non-null and exactly typed with canonical UUIDs; any missing, null, mistyped, negative or fractional version, unknown format, bad or duplicate UUID, or illegal audit entry fails closed the same way as a corrupt file, while unknown extra fields stay compatible. A corrupt, incomplete or unreadable file fails closed: all balance-changing operations are refused with a loud fixed diagnosis that never echoes file content, and the load path never overwrites or deletes the file. Control mutations (freeze, unfreeze, read-only) run on the shared IO executor after player lookup; the region thread never holds the control write lock nor fsyncs, the handler returns immediately, and the final reply fires once after completion through the region dispatch. That IO placement covers only the control commands: ordinary synchronous money operations admitted through a mutation permit still wait while a control write holds the lock for its file sync. That wait is the accepted linearisation cost; slow-disk wait measurement is still open (normal Folia/MySQL/restart operation has since been exercised — see [Live validation](#live-validation)). A missing or rejecting executor is a typed refusal with no state change. Switching read-only off cannot clear a load failure; repair the file and restart. Reload never touches this control. Ordinary backups never contain it, and a restore never replaces it: back it up separately while the server is stopped, or through a controlled snapshot export. A downgrade to a version that does not know this file loses the protection; that is accepted and documented, not silently bridged.

Every refused initiating action leaves exactly one minimal evidence line in `<plugin data folder>/security-control-attempts.jsonl` (operation, target account when there is one, fixed refusal category, control version, trusted operator, timestamp). A multi-account refusal still leaves one line naming the frozen target (none for a global refusal). The full command line, network identity, and raw reason text are never stored. Appends run serially on the IO executor, never on a region thread; a journal failure is logged loudly, the in-memory record is kept, and the mutation stays refused. Memory keeps only the latest 1024 records as a recent view; the file journal appends every record completely. Neither the control audits nor the journal have a deletion or retention policy yet: they grow until an operator archives them. Attempt records are read-only evidence and can never reconstruct control state.

Live verification on 2026-10-13 (Folia with MySQL): freezing an account refused `/aceeco take` with `ACELIB-CMD-ACCOUNT-FROZEN` and left the balance unchanged; the freeze audit went to the control file and the refusal line went to `security-control-attempts.jsonl` with category `ACCOUNT_FROZEN`; unfreezing restored normal deductions. Global read-only refused `give` with `ACELIB-CMD-GLOBAL-READ-ONLY` and left balances unchanged until switched off. A control file with a missing field failed closed after restart: every initiating operation was refused with `ACELIB-CMD-CONTROL-UNAVAILABLE` and the fixed diagnosis, balances stayed unchanged, and refusal lines were filed with category `CONTROL_UNAVAILABLE` and control version `-1`; the load path never overwrote the damaged file, and repairing the file plus restarting restored normal operation. The SQLite and JSON legs repeated the freeze, read-only, and restore-guard checks with the same refusal codes and unchanged data. Control edits made while the server is running do not take effect until restart — control state is loaded at startup. Manufacturing a trusted already-deducted compensation under read-only has not been done live; automated tests cover that path.

The trusted-refund ledger is in-memory and single-server: a restart clears pending authorizations fail-closed (no double spend). A mint failure whose refund cannot be proven stays pending with its claim (nonce, player, amount, stage) for manual reconciliation and is never retried blindly.

## Integrations

Vault and PlaceholderAPI are optional. If either is absent, the core economy service can still run. Vault uses the configured default currency. PlaceholderAPI uses the `aceeco` namespace:

```text
%aceeco_balance%
%aceeco_balance_formatted%
%aceeco_balance_<currency>%
%aceeco_balance_<currency>_formatted%
```

Discord is configured under `discord.enabled` and `discord.webhook-url`. Store the real webhook only on the server. Delivery is asynchronous and best-effort: a notification problem must be handled separately from the already completed economy transaction.

## Stop, restart, and reopen

Use the normal service control or the server console command:

```text
stop
```

Wait for world and plugin saving to finish. After a restart, repeat the start-of-day checks before letting players back in. If a restart follows a failed reload, restore the last known-good config first so the server does not repeatedly boot with the same bad edit.

## Emergency rollback

To reverse a single recorded transaction, use `/aceeco rollback <transaction-id>` from the console. It is console-only, requires `aceeconomy.admin` plus `aceeconomy.admin.rollback`, and reports each outcome explicitly: success lists the reversal audit record IDs, an already reverted transaction is a safe no-op, and a marker persist failure means the effect may exist without durable bookkeeping. Inspect storage and reconcile manually before retrying. See [Commands and permissions](commands.md) for the full outcome table.

The rollback command is wired into the production command surface and covered by automated contract tests. Controlled drills have since run on Folia with the MySQL, SQLite, and JSON backends (see [Live validation](#live-validation)): bridge execution, live storage writes, and checks with test data. Until that release gate closes, keep using it only in controlled drills with backups taken beforehand.

## Live validation

Live drills on 2026-10-13 (Folia 26.2 build 7; the first leg used a dedicated MySQL database, then the SQLite and JSON backends in sequence). Safety-control results sit next to the rules in [Safety control](#safety-control-freeze-and-read-only); storage results sit in [Persistence](persistence.md#live-validation). Player-facing results:

- **Banknote round trip.** Right-click redeem and the bank-GUI deposit button both moved the issuance record from `DELIVERED` to `REDEEMED`, consumed the nonce, and restored the balance correctly.
- **Java GUI.** `/bank open` showed the 27-slot container with the default title and button positions (deposit chest slot 4, withdraw paper slots 11 and 13, close barrier slot 15); withdraw and deposit clicks posted correct ledger results.
- **Full inventory.** Withdrawing with a full inventory was refused ("Inventory full! Cannot withdraw banknote."), and issuance counts and balances did not move.
- **Disconnect.** Withdrawing then disconnecting left the issuance `DELIVERED`, the balance unchanged, and no ghost transaction; after reconnecting the note was still in the inventory and redeeming it marked the record `REDEEMED`. See the delivery-window observations in [Persistence](persistence.md#restore-sequence).
- **Coexistence.** AceLib ran alongside several other consumers with `/lprobe status` reporting `apiReady=true lifecycleStatus=READY … problems=none`.
- **Import.** A dry run wrote nothing; `apply confirm` wrote a pre-import safety backup first, then applied (a test balance moved 1000 to 999 with an `import:CMI` transaction). A repeat run reported skipped records instead of staying silent. Duplicate detection is keyed by source plus player, so a previously imported player resubmitted from a new file with a different balance is still skipped — with an explicit skipped report, which is accepted behaviour, not a silent drop.

The SQLite and JSON legs (same day, same build, `storage.type` switched with the server stopped) re-exercised the same player-facing paths with the same outcomes: give/take/set and bot transfers applied, rollback reversed the deposit, banknote withdraw left the note in the inventory and right-click redeem restored the balance, the import dry run wrote nothing while `apply confirm` wrote a pre-import safety backup first and applied 1 record, and the freeze, read-only, and restore-guard refusals behaved as on MySQL with data unchanged. Restart checks read the stopped-server store directly (SQLite: seven tables; JSON: `accounts`/`transactions`/`nonces`/`issuances`/`schemaVersion` sections); after restart `/money balance` checked all five accounts one by one on the SQLite leg, reporting the same values as the stopped-server read; on the JSON leg it confirmed three of the five accounts with the stopped-server read covering the other two, and a later query of all five accounts under the JSON configuration matched the data file. Test-server end state and recovery sit in [Persistence](persistence.md#live-validation).

Still pending live: closing the rollback release gate above, slow-disk wait measurement, and trusted already-deducted compensation under read-only (covered by automated tests).

## Handing a problem to support

Use [Troubleshooting](troubleshooting.md) and provide the first relevant error, the versions, the active storage type, the exact sanitized command, and the time of the incident. Keep data files, backups, passwords, tokens, and webhook URLs private.
