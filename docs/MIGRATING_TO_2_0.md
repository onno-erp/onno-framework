# Migrating an application to onno 2.0

onno 2.0 removes the compatibility readers and aliases that previously let old data, metadata,
routes, and response shapes keep working. Migrate the application before switching its framework
dependency.

## Run the one-time data conversion

Register an `AppMigration` bean. The framework runs it once, after schema upgrade, in the same
transaction as its schema-history record:

```java
@Component
final class Onno2UiValues implements AppMigration {
    private final MediaStorage mediaStorage;

    Onno2UiValues(MediaStorage mediaStorage) {
        this.mediaStorage = mediaStorage;
    }

    @Override
    public String version() {
        return "2.0.001";
    }

    @Override
    public void migrate(MigrationContext context) throws Exception {
        var ui = new Ui2MigrationTool(context, mediaStorage);
        ui.migrateEnumNames("document_orders", "_id", "status", OrderStatus.class);
        ui.migrateDataUrlImages("catalog_employees", "_id", "avatar_url");
        ui.migrateGeoPoints("catalog_sites", "_id", "location");
    }
}
```

`Ui2MigrationTool` converts:

- stored Java enum constant names to the enum value's deterministic UUID;
- base64 `data:image/...` values to URLs stored by the configured `MediaStorage`;
- `"lat,lng"` point strings to GeoJSON.

Each helper validates SQL identifiers, reports the failing row, and aborts on unknown or malformed
values. Already-canonical values are unchanged. Run the migration on a backup or staging copy
first, especially when external media storage has non-transactional side effects.

## Update authored Java metadata

The source helpers make mechanical replacements explicit:

```java
Ui2MigrationTool.canonicalCurrencyFormat("currency", "EUR"); // currency:EUR
Ui2MigrationTool.canonicalIcon("bar-chart-3");                // chart-column
Ui2MigrationTool.canonicalWidget("photo");                    // image
Ui2MigrationTool.canonicalMapConfigKey("geoField");           // geoJsonField
```

Apply the resulting values to source/configuration:

| Before 2.0 | 2.0 |
| --- | --- |
| `widget("photo")` / `widget("photos")` | `widget("image")` / `widget("images")` |
| `widget("map")`, `"geo"`, or `"geolocation"` | `widget("geojson")` |
| `map().field(...)` / `config("geoField", ...)` | `map().geoJson(...)` / `config("geoJsonField", ...)` |
| bare `format("currency")` | `format("currency:ISO")`, for example `currency:EUR` |
| old Lucide aliases such as `home` or `bar-chart-3` | current Lucide names such as `house` or `chart-column` |
| related-list `joinCatalog` metadata | `sourceName` plus `sourceKind` |

## Update clients and routes

- Page every catalog/document collection through
  `/api/list/{catalogs|documents}/{name}?cursor=&limit=`. The unbounded
  `/api/catalogs/{name}` and `/api/documents/{name}` collection reads no longer exist.
- Treat `nextCursor` as opaque. Offset pagination and the `{total, offset, rows}` envelope were
  removed.
- Link records to `/{kind}/{name}/{id}`. The `/{id}/edit` aliases were removed.
- Handle successful action results as `{refresh, feedback}`. Text belongs in an `ActionToast` or
  `ActionDialog`; handler-directed navigation fields were removed. Static action navigation remains
  `action.navigate(url)`.

## Schema ownership

`SchemaGenerator` only bootstraps an empty database. `SchemaUpgrader` is the sole runtime path for
metadata-driven schema evolution, while application-specific data reshaping belongs in
`AppMigration`. The former additive `SchemaMigrator` API no longer exists.
