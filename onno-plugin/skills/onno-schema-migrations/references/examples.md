# Schema And Migration Examples

## Table Of Contents

- Rename Without Data Loss
- AppMigration Backfill
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
                update products
                   set search_name = lower(coalesce(description, '') || ' ' || coalesce(article, ''))
                 where search_name is null
                """).execute();
    }
}
```

`AppMigration` versions are compared segment-wise and run once per database in order, inside a
transaction. Use them for data, not metadata DDL.

## Configuration

```yaml
onno:
  schema:
    mode: plan       # apply | plan | validate | off
    allow-destructive: false
```

Use `plan` during risky refactors. Use `apply` for normal development. Use `validate` when startup
must fail on drift. Keep destructive changes gated and intentional.

## Verification Flow

1. Add `previousNames` for renames.
2. Start locally with `onno.schema.mode=plan` and inspect the logged plan.
3. Switch back to `apply`.
4. Add or update tests around metadata scanning or schema behavior.
5. Run the narrow module test, then `./gradlew clean check` if the change is public.
6. For release readiness, also run `./gradlew publishToMavenLocal`.

## Common Mistakes

- Editing generated `docs/CONFIGURATION.md` by hand. Change property Javadoc or additional metadata
  and run `./gradlew generateConfigDocs`.
- Renaming an annotation `name` without `previousNames`.
- Using `AppMigration` to create framework-owned tables.
- Forgetting docs when changing annotation elements or config properties.
