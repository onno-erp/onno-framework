# onno-import-starter

Generic CSV import endpoints for onno applications.

The first slice imports catalog and document records from uploaded CSV files. It deliberately
writes through `CatalogCommandService` and `DocumentCommandService`, so imports use the same
authorization, validation, secret handling, number generation, optimistic locking, posting, and
change events as the generated REST UI.

## Usage

```kotlin
dependencies {
    implementation("su.onno:onno-import-starter:0.1.0")
}
```

The starter auto-configures when `MetadataRegistry` and `onno-ui-starter` services are present.

## Endpoints

All endpoints live under `/api/import/**`, so the regular API authentication and CSRF rules apply.

### Preview Catalog CSV

```text
POST /api/import/catalogs/{name}/csv/preview
multipart/form-data:
  file: CSV file
  charset: optional, defaults to UTF-8
```

Returns the headers, up to `onno.import.preview-rows` sample rows, and total row count.

### Import Catalog CSV

```text
POST /api/import/catalogs/{name}/csv
multipart/form-data:
  file: CSV file
  mapping: JSON object of entity field name -> CSV header
  mode: CREATE_ONLY | UPSERT_BY_CODE
  dryRun: true | false
  charset: optional, defaults to UTF-8
```

Example mapping:

```json
{
  "code": "Code",
  "description": "Name",
  "name": "Name",
  "taxId": "Tax ID"
}
```

`UPSERT_BY_CODE` updates a live catalog row when the mapped `code` already exists; otherwise it
creates a new row.

### Preview Document CSV

```text
POST /api/import/documents/{name}/csv/preview
multipart/form-data:
  file: CSV file
  charset: optional, defaults to UTF-8
```

### Import Document CSV

```text
POST /api/import/documents/{name}/csv
multipart/form-data:
  file: CSV file
  mapping: JSON object of document field name -> CSV header
  mode: CREATE_ONLY | UPSERT_BY_NUMBER
  dryRun: true | false
  postAfterImport: true | false
  groupBy: optional CSV column that groups rows into documents
  charset: optional, defaults to UTF-8
```

Example mapping:

```json
{
  "number": "Number",
  "date": "Date",
  "customer": "Customer ID",
  "comment": "Comment"
}
```

`UPSERT_BY_NUMBER` updates a live document row when the mapped `number` already exists; otherwise
it creates a new document.

### Tabular sections

Document line-items live in a single denormalised CSV. Header attributes use plain mapping keys;
tabular-section fields use a dotted `section.field` key. Pass `groupBy` to name the CSV column whose
shared value collapses several rows into one document — header fields are taken from the group's
first row, and each row contributes one line to every mapped tabular section.

```text
Number,Date,Customer ID,Product SKU,Qty,Unit Price
SO-001,2026-06-04,CUST-1,WIDGET,2,9.99
SO-001,2026-06-04,CUST-1,GADGET,3,4.50
SO-002,2026-06-04,CUST-2,WIDGET,1,9.99
```

```json
{
  "number": "Number",
  "date": "Date",
  "customer": "Customer ID",
  "lines.product": "Product SKU",
  "lines.quantity": "Qty",
  "lines.price": "Unit Price"
}
```

With `groupBy=Number`, that file imports as **two** documents: `SO-001` with two `lines` and
`SO-002` with one. Rows whose mapped tabular cells are all blank are skipped, so a trailing empty
line under a parent does not create an empty row. Without `groupBy`, each CSV row becomes its own
document with a single line per mapped section. Imports still flow through `DocumentCommandService`,
so the same validation, number generation, posting, and change events apply.

## Configuration

| Property | Default | Description |
| --- | --- | --- |
| `onno.import.enabled` | `true` | Master switch. |
| `onno.import.max-file-bytes` | `5242880` | Max uploaded CSV file size. |
| `onno.import.preview-rows` | `20` | Max sample rows returned by preview. |
| `onno.import.max-rows` | `10000` | Max rows processed by one import request. |
