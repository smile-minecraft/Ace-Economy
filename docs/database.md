# Database concepts and upgrades

English · [简体中文](database.zh-CN.md) · [繁體中文](database.zh-TW.md)

This page explains the current v2 data model so an administrator can see which data must be kept when upgrading. It is not a manual SQL creation script. Choosing `storage.type: mysql` or `storage.type: sqlite` lets AceEconomy build the schema for the selected database type; JSON stores the same logical model in a file.

## Contents

- [Two version values with different meanings](#two-version-values-with-different-meanings)
- [The v2 model](#the-v2-model)
  - [`ace_v2_schema`](#ace_v2_schema)
  - [`ace_v2_accounts`](#ace_v2_accounts)
  - [`ace_v2_balances`](#ace_v2_balances)
  - [`ace_v2_transactions`](#ace_v2_transactions)
- [JSON and SQL represent the same data](#json-and-sql-represent-the-same-data)
- [How a new SQL store is initialized](#how-a-new-sql-store-is-initialized)
- [Upgrade path](#upgrade-path)
- [Backups and recovery](#backups-and-recovery)
- [What changed from the old document](#what-changed-from-the-old-document)

## Two version values with different meanings

There are two separate version concepts:

| Version | Where it appears | Meaning |
| --- | --- | --- |
| Configuration `2.0` | `config.yml` → `version` | The shape of the v2 configuration file. |
| Persistence `1` | SQL table `ace_v2_schema`, or JSON `schemaVersion` | The current v2 persistence model understood by the backend. |

Do not use the persistence value as the configuration value, and do not treat an old v1 table layout as a v2 schema. The names below are the v2 names.

## The v2 model

The model has one schema marker, one account relation, one balance relation, and one transaction relation. The four SQL tables are:

### `ace_v2_schema`

This table records the persistence model version. It contains `version` and `updated_at`; the initialized v2 store writes version `1`. The row tells the backend which model it is opening. It does not contain player balances.

### `ace_v2_accounts`

This table maps an account owner ID to the last stored owner name:

| Column | Meaning |
| --- | --- |
| `owner` | The account owner's UUID. |
| `owner_name` | The stored display name for that owner. |

The UUID is the durable identity. The name is a stored name for display, not the account key.

### `ace_v2_balances`

This table stores one amount for each `(owner, currency_id)` pair:

| Column | Meaning |
| --- | --- |
| `owner` | The account owner UUID. |
| `currency_id` | The configured currency ID, such as `dollar` or `token`. |
| `amount` | The exact decimal amount, stored as text. |

The pair `(owner, currency_id)` is the key. That is why changing a currency ID is a data change, not just a display change.

### `ace_v2_transactions`

This table records the information needed to understand a financial event and its later state:

| Column | Meaning |
| --- | --- |
| `id` | The transaction UUID. |
| `account_id` | The account affected by the record. |
| `counterparty` | The other account when the operation has one; it may be empty. |
| `currency_id` | The currency used by the operation. |
| `amount` | The exact decimal amount as text. |
| `type` | The domain transaction type. |
| `balance_before` | The balance before the event, when available. |
| `balance_after` | The balance after the event, when available. |
| `timestamp` | The event time. |
| `reason` | An optional reason associated with the event. |
| `reverted` | Whether this record has been marked reverted. |

Amounts and balance snapshots are stored as decimal text in both SQL dialects. MySQL uses its v2 `VARCHAR` representation and a boolean reverted flag; SQLite uses text and its integer boolean representation. The logical fields are the same.

## JSON and SQL represent the same data

The JSON backend keeps the same concepts in one document:

```json
{
  "schemaVersion": 1,
  "accounts": {
    "<owner-uuid>": {
      "owner": "<owner-uuid>",
      "ownerName": "<display-name>",
      "balances": {
        "dollar": "1000.00"
      }
    }
  },
  "transactions": []
}
```

This example is deliberately small. A real snapshot can contain many accounts and transactions. `accounts` carries balances, while `transactions` carries the event history and the `reverted` state.

## How a new SQL store is initialized

When an SQL backend starts with no v2 tables, it creates:

1. `ace_v2_schema` with the v2 persistence version row.
2. `ace_v2_accounts` for account identity and stored name.
3. `ace_v2_balances` for one amount per owner and currency.
4. `ace_v2_transactions` for event records and revert state.

Creating the tables is safe to repeat for an existing compatible store. The plugin should initialize the tables; administrators do not need to copy an old v1 DDL script into a new v2 database.

## Upgrade path

The v2 configuration and persistence model are clean boundaries. An old v1 configuration or old v1 tables are not read as v2 data. Plan the upgrade as a new v2 store:

1. Keep a backup of the old installation before changing files or database settings.
2. Choose JSON, SQLite, or MySQL in the v2 `config.yml`.
3. Start with the selected location and let the plugin create the v2 model.
4. If you have a v2 JSON snapshot, restore that snapshot into the new backend.
5. Check account balances and configured currency IDs before normal operation.

This model does not describe any automatic conversion from old v1 rows into v2 accounts, balances, and transactions. A v2 snapshot is portable between the JSON and SQL backends because it carries the shared logical model; that portability does not make an old v1 backup compatible.

## Backups and recovery

Use the persistence document for the operational sequence in [Persistence, backup, and restore](persistence.md). The important data boundary is the complete v2 model: accounts, balances, transactions, and the schema version.

Before replacing a live store, stop the server and retain the current backup. A restore first parses the incoming v2 JSON snapshot and checks its schema version. If parsing or version validation fails, the existing data is left in place. A successful restore replaces the current accounts, balances, and transactions with the snapshot inside the backend's restore operation.

Do not start troubleshooting by deleting the schema marker or dropping the v2 tables. If the store is incompatible, preserve a backup and treat recreation as a data-destructive operation that starts empty.

## What changed from the old document

The old table names `ace_balances`, `ace_users`, and `ace_transaction_logs` are not the v2 creation guide. In v2, account identity and balances are separated into `ace_v2_accounts` and `ace_v2_balances`, while transaction history is represented by `ace_v2_transactions`. The v2 schema also stores exact decimal values as text and includes a schema marker.

If an existing database contains only those old table names, do not point a v2 backend at it and assume the rows will be adopted. Keep the old backup, create or select a v2 location, and handle any data conversion as a separate migration decision.
