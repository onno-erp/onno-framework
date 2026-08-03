# Runtime API Examples

## Table Of Contents

- Login And CSRF
- Catalog CRUD
- Document Posting
- Register Reads
- Response Shape
- Smoke Test Script

## Login And CSRF

```bash
base=http://localhost:8080
jar=$(mktemp)

curl -fsS -c "$jar" "$base/api/auth/csrf" >/dev/null
curl -fsS -b "$jar" -c "$jar" -X POST "$base/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$ONNO_USER\",\"password\":\"$ONNO_PASSWORD\",\"remember\":true}" \
  | jq -e '.authenticated == true'

xsrf=$(curl -fsS -b "$jar" -c "$jar" "$base/api/auth/csrf" | jq -er .token)
```

In-memory and OIDC modes use sessions plus CSRF. OIDC does not use the JSON password login. In
resource-server mode, send `Authorization: Bearer $access_token` on every request; it is stateless,
CSRF is disabled, and `/api/auth/login` returns `409`.

## Catalog CRUD

```bash
curl -fsS -b "$jar" "$base/api/list/catalogs/Products?limit=25" | jq -e '.rows'

product_id=$(curl -fsS -b "$jar" "$base/api/list/catalogs/Products?limit=1" | jq -er '.rows[0].id')
curl -fsS -b "$jar" "$base/api/catalogs/Products/$product_id" | jq -e '.id'

created=$(curl -fsS -b "$jar" -H "X-XSRF-TOKEN: $xsrf" -H 'Content-Type: application/json' \
  -X POST "$base/api/catalogs/Products" \
  -d '{"description":"Widget","name":"Widget","price":12.50}')
created_id=$(jq -r .id <<<"$created")
version=$(jq -r .version <<<"$created")

curl -fsS -b "$jar" -H "X-XSRF-TOKEN: $xsrf" -H 'Content-Type: application/json' \
  -X PUT "$base/api/catalogs/Products/$created_id" \
  -d "{\"price\":13.50,\"version\":$version}" | jq -e .

curl -fsS -b "$jar" -H "X-XSRF-TOKEN: $xsrf" \
  -X DELETE "$base/api/catalogs/Products/$created_id" -o /dev/null
```

Names in the URL are annotation logical names (`@Catalog(name = "Products")`). If a name has spaces,
use the exact route segment the UI uses or URL-encode it.

## Document Posting

```bash
curl -fsS -b "$jar" "$base/api/list/documents/SalesOrders?limit=25" | jq -e '.rows'
order_id=$(curl -fsS -b "$jar" "$base/api/list/documents/SalesOrders?limit=1" | jq -er '.rows[0].id')

curl -fsS -b "$jar" "$base/api/documents/SalesOrders/$order_id" | jq -e '.id'
curl -fsS -b "$jar" "$base/api/documents/SalesOrders/$order_id/posting-preview" | jq -e '.registers'

curl -fsS -b "$jar" -H "X-XSRF-TOKEN: $xsrf" \
  -X POST "$base/api/documents/SalesOrders/$order_id/post" | jq -e .

curl -fsS -b "$jar" -H "X-XSRF-TOKEN: $xsrf" \
  -X POST "$base/api/documents/SalesOrders/$order_id/unpost" | jq -e .
```

Document create/update/delete use the same command shapes:

```bash
order=$(curl -fsS -b "$jar" -H "X-XSRF-TOKEN: $xsrf" -H 'Content-Type: application/json' \
  -d "{\"customer\":\"$customer_id\",\"items\":[{\"product\":\"$product_id\",\"quantity\":1}]}" \
  "$base/api/documents/SalesOrders")
new_order_id=$(jq -r .id <<<"$order")
order_version=$(jq -r .version <<<"$order")

curl -fsS -b "$jar" -H "X-XSRF-TOKEN: $xsrf" -H 'Content-Type: application/json' \
  -X PUT -d "{\"comment\":\"checked\",\"version\":$order_version}" \
  "$base/api/documents/SalesOrders/$new_order_id" | jq -e .
curl -fsS -b "$jar" -H "X-XSRF-TOKEN: $xsrf" -X DELETE \
  "$base/api/documents/SalesOrders/$new_order_id" -o /dev/null
```

A single document read includes line sections; the keyset list envelope omits them. Replay the
opaque `nextCursor` as the `cursor` query parameter instead of inventing offsets.

## Dry-Run Validation (live form feedback)

Runs the full write lifecycle — declarative constraints, `onFilling`/`beforeWrite` hooks,
`Validated` business rules — without persisting. Always HTTP 200; the verdict is the payload.
For a successfully executed dry run, business validation returns HTTP 200 with the verdict payload.
Authentication, RBAC, malformed paths, and missing update targets still fail normally. Works on
catalogs and documents alike; omit the id to validate a create, include it to overlay
changes on the stored record like a `PUT`.

```bash
curl -sS -b "$jar" -H "X-XSRF-TOKEN: $xsrf" -H "Content-Type: application/json" \
  -d '{"note": "half-filled form"}' \
  -X POST "$base/api/documents/SalesOrders/validate"
# {"valid":false,"fieldErrors":{"customer":["Customer is required"]},"formErrors":["Choose a customer"]}
```

Posting writes register movements and returns `posted=true`. Preview should be used before destructive
or high-value tests.

## Register Reads

```bash
curl -fsS -b "$jar" "$base/api/registers/Stock/movements" | jq -e .
curl -fsS -b "$jar" "$base/api/registers/Stock/balance?product=$product_id" | jq -e .
curl -fsS -b "$jar" \
  "$base/api/registers/Sales/turnover?from=2026-07-01T00:00:00&to=2026-08-01T00:00:00" | jq -e .
```

Check the owning README and architecture docs for exact endpoint variants if a route changes; code
wins over stale docs.

## Response Shape

Catalog/document list/get responses default to logical values and display companions:

```json
{
  "id": "5d3...",
  "customer": "9a1...",
  "customerDisplay": "Acme LLC",
  "customerRef": {
    "type": "Customers",
    "id": "9a1..."
  },
  "status": "7f4...",
  "statusDisplay": "Confirmed",
  "statusColor": "#2563EB",
  "apiKey": "__SECRET_SET__"
}
```

Use raw values for writes and `*Display` / `*Color` for headless UI rendering. Writable values from
the default response can be submitted to `PUT` without renaming. Add `?representation=storage` for
the legacy `_description` / `customer_display` response; storage aliases are also accepted on
writes, and conflicting logical/storage values return `400`.

Temporals are ISO wall-clock strings. `LocalDate` is `2026-06-04`; `LocalDateTime` reads canonically
as offset-free `2026-06-04T10:00:00`. An offset-bearing write such as
`2026-06-04T10:00:00+03:00` is accepted as the same local wall time (no timezone shift), but prefer
the offset-free canonical representation:

```bash
curl -sS -b "$jar" -H "X-XSRF-TOKEN: $xsrf" -H 'Content-Type: application/json' \
  -X PUT "$base/api/documents/Events/$event_id" \
  -d '{"startsAt":"2026-06-04T10:00:00"}'
```

## Smoke Test Script

```bash
set -euo pipefail
base=${BASE_URL:-http://localhost:8080}
jar=$(mktemp)

curl -fsS -c "$jar" "$base/api/auth/csrf" >/dev/null
curl -fsS -b "$jar" -c "$jar" -X POST "$base/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$ONNO_USER\",\"password\":\"$ONNO_PASSWORD\"}" | jq -e '.authenticated'

curl -fsS -b "$jar" "$base/api/list/catalogs/Products?limit=25" | jq -e '.rows'
curl -fsS -b "$jar" "$base/api/list/documents/SalesOrders?limit=25" | jq -e '.rows'
```

`curl -f` catches HTTP errors but not a 200 HTML fallback. Parse with `jq -e` or assert
`Content-Type: application/json` and expected fields.
