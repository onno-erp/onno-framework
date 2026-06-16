# Community registry

This directory holds the **source of truth** for the community integrations catalog.

| File | Role |
| --- | --- |
| [`registry.json`](registry.json) | The list of community integrations. Edit this to add or update an entry. |
| [`registry.schema.json`](registry.schema.json) | JSON Schema for `registry.json`. Most editors validate against it automatically via the `$schema` key. |

The human-readable catalog at [`../INTEGRATIONS.md`](../INTEGRATIONS.md) is **generated** from
`registry.json` — do not edit it by hand.

## Adding or updating an entry

1. Build a real, public integration first — see [docs/EXTENDING.md](../docs/EXTENDING.md).
2. Add an object to the `integrations` array in [`registry.json`](registry.json). Required fields:

   ```json
   {
     "name": "onec-shopify-starter",
     "description": "Sync Shopify orders and products into onec catalogs and documents.",
     "author": "your-github-handle",
     "repository": "https://github.com/your-handle/onec-shopify-starter",
     "category": "connector",
     "coordinates": "io.github.your-handle:onec-shopify-starter",
     "onecVersion": "0.10.x",
     "license": "Apache-2.0",
     "status": "active"
   }
   ```

   `homepage` and `tags` are optional. `coordinates` may be omitted for skills/plugins not
   published to Maven. See [`registry.schema.json`](registry.schema.json) for the full contract.

3. Regenerate the catalog:

   ```bash
   ./gradlew generateIntegrationsDoc
   ```

4. Commit **both** `registry.json` and the regenerated `INTEGRATIONS.md`, then open a PR. See
   [CONTRIBUTING.md](../CONTRIBUTING.md#listing-a-community-integration) for the listing criteria.

## Scope

This registry is for **third-party / community** integrations. Official modules
(`io.github.onec-erp:*`) are listed in the [README](../README.md#modules); commercial connectors
live in the separate [onec-enterprise](https://github.com/onec-erp/onec-enterprise) repo. Listed
projects are maintained by their authors, not by the onec-framework team.
