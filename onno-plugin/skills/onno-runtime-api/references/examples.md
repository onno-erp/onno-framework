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
jar=/tmp/onno-cookies.txt

curl -sS -c "$jar" "$base/api/config" >/dev/null
curl -sS -b "$jar" -c "$jar" -X POST "$base/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin","remember":true}'

xsrf=$(awk '$6=="XSRF-TOKEN"{print $7}' "$jar" | tail -1)
```

For non-browser clients that cannot read cookies, call `GET /api/auth/csrf` and use the returned
token.

## Catalog CRUD

```bash
curl -sS -b "$jar" "$base/api/catalogs/Products"

curl -sS -b "$jar" -H "X-XSRF-TOKEN: $xsrf" -H 'Content-Type: application/json' \
  -X POST "$base/api/catalogs/Products" \
  -d '{"description":"Widget","name":"Widget","price":12.50}'
```

Names in the URL are annotation display names (`@Catalog(name = "Products")`). If a name has spaces,
use the exact route segment the UI uses or URL-encode it.

## Document Posting

```bash
order_id=00000000-0000-0000-0000-000000000000

curl -sS -b "$jar" "$base/api/documents/Sales%20Orders/$order_id/posting-preview"

curl -sS -b "$jar" -H "X-XSRF-TOKEN: $xsrf" \
  -X POST "$base/api/documents/Sales%20Orders/$order_id/post"

curl -sS -b "$jar" -H "X-XSRF-TOKEN: $xsrf" \
  -X POST "$base/api/documents/Sales%20Orders/$order_id/unpost"
```

## Dry-Run Validation (live form feedback)

Runs the full write lifecycle — declarative constraints, `onFilling`/`beforeWrite` hooks,
`Validated` business rules — without persisting. Always HTTP 200; the verdict is the payload.
Works on catalogs and documents alike; omit the id to validate a create, include it to overlay
changes on the stored record like a `PUT`.

```bash
curl -sS -b "$jar" -H "X-XSRF-TOKEN: $xsrf" -H "Content-Type: application/json" \
  -d '{"note": "half-filled form"}' \
  -X POST "$base/api/documents/Sales%20Orders/validate"
# {"valid":false,"fieldErrors":{"customer":["Customer is required"]},"formErrors":["Choose a customer"]}
```

Posting writes register movements and marks `_posted=true`. Preview should be used before destructive
or high-value tests.

## Register Reads

```bash
curl -sS -b "$jar" "$base/api/registers/Stock"
curl -sS -b "$jar" "$base/api/registers/Stock/balance?product=$product_id"
curl -sS -b "$jar" "$base/api/registers/Sales/turnover?from=2026-07-01T00:00:00&to=2026-08-01T00:00:00"
```

Check the owning README and architecture docs for exact endpoint variants if a route changes; code
wins over stale docs.

## Response Shape

List/get responses include storage values and display companions:

```json
{
  "id": "5d3...",
  "customer": "9a1...",
  "customer_display": "Acme LLC",
  "customer_ref": {
    "type": "Customers",
    "id": "9a1..."
  },
  "status": "CONFIRMED",
  "status_display": "Confirmed",
  "status_color": "#2563EB",
  "apiKey": "__SECRET_SET__"
}
```

Use raw values for writes and filters. Use `*_display` and `*_color` for headless UI rendering.

## Smoke Test Script

```bash
set -euo pipefail
base=${BASE_URL:-http://localhost:8080}
jar=$(mktemp)

curl -fsS -c "$jar" "$base/api/config" >/dev/null
curl -fsS -b "$jar" -c "$jar" -X POST "$base/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"${ONNO_USER:-admin}\",\"password\":\"${ONNO_PASSWORD:-admin}\"}" >/dev/null

curl -fsS -b "$jar" "$base/api/catalogs/Products" | jq .
curl -fsS -b "$jar" "$base/api/documents/Sales%20Orders" | jq .
```

Use `curl -f` so HTML fallback and 401/403 failures fail the script instead of looking successful.
