# Schema And Migration Examples

## Table Of Contents

- Rename Without Data Loss
- AppMigration Backfill
- Required Column Rollout
- Configuration
- Verification Flow
- Common Mistakes

## Rename Without Data Loss

```java
@Catalog(name = "Counterparties", title = "Counterparties", previousNames = "Suppliers")
public class Counterparty extends CatalogObject {

    @Attribute(displayName = "Phone number", previousNames = "phone", length = 50)
    private String phoneNumber;
}
```

Without `previousNames`, the schema engine sees a drop plus add. With `previousNames`, it can keep the
existing table/column data while moving to the new metadata name.

For documents, `previousNames` also covers tabular section table renames.

## AppMigration Backfill

```java
package com.acme.migrations;

import org.springframework.stereotype.Component;
import su.onno.migration.AppMigration;
import su.onno.migration.MigrationContext;

@Component
public class BackfillProductSearchName implements AppMigration {
    @Override
    public String version() {
        return "2026.07.14.1";
    }

    @Override
    public String description() {
        return "Backfill Products.search_name from description and article";
    }

    @Override
    public void migrate(MigrationContext context) {
        context.handle().createUpdate("""
                update catalog_products
                   set search_name = lower(coalesce(description, '') || ' ' || coalesce(article, ''))
                 where search_name is null
                """).execute();
    }
}
```

`AppMigration` versions are compared segment-wise and run once per database in order, inside a
transaction. Do not create/drop/rename framework-owned structural objects manually. Constraint
finalization may be necessary where the schema upgrader explicitly cannot choose a safe value.

## Required Column Rollout

Schema reconciliation runs before `AppMigration`s. For a new required String/number/boolean column,
the upgrader may insert a neutral placeholder and enforce `NOT NULL` before the migration. Backfill
both null and placeholder values:

```java
context.handle().createUpdate("""
        update catalog_customers
           set region = 'UNASSIGNED'
         where region is null or region = ''
        """).execute();
```

UUID/date/Ref types may remain nullable when no safe neutral value exists. Backfill them, then use a
small `AppMigration` `ALTER ... SET NOT NULL` only when the upgrader's plan explicitly requires
constraint finalization. Test the exact dialect on H2 and PostgreSQL.

## Configuration

```yaml
onno:
  schema:
    mode: plan       # apply | plan | validate | off
    allow-destructive: false
```

Use `plan` during risky refactors. Use `apply` for normal development. Use `validate` when startup
must fail on drift. `plan`/`validate` do not apply model changes or `AppMigration`s, but may create
the `onno_schema_history` table. Keep destructive changes gated and intentional.

## Verification Flow

1. Add `previousNames` for renames and keep `allow-destructive=false`.
2. Start with `mode=plan`; inspect every safe and destructive entry.
3. Apply safe rename/add/backfill work while the obsolete column remains.
4. Verify row count, renamed values, no placeholder/null required values, physical nullability, and
   exactly one migration-history row; restart to prove idempotency.
5. Back up. Approve the exact destructive targets and ensure no unrelated drop is present.
6. Enable `allow-destructive=true` for one controlled apply, immediately restore `false`, and run
   `validate` plus an idempotent restart.
7. Run the same lifecycle on H2 and Testcontainers PostgreSQL, then narrow tests, `clean check`, and
   `publishToMavenLocal`.

## Common Mistakes

- Editing generated `docs/CONFIGURATION.md` by hand. Change property Javadoc or additional metadata
  and run `./gradlew generateConfigDocs`.
- Renaming an annotation `name` without `previousNames`.
- Using `AppMigration` to create framework-owned tables.
- Enabling the global destructive gate without approving the complete plan; unmatched columns in a
  managed table are drop candidates.
- Forgetting docs when changing annotation elements or config properties.
