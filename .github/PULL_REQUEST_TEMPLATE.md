<!-- Thanks for contributing to onec-framework! -->

## What & why

<!-- What does this change do, and why? Link any related issue. -->

## Type

- [ ] Code change (framework / starter / example)
- [ ] Docs only
- [ ] Community integration listing

## Checklist

- [ ] `./gradlew clean check` passes locally.
- [ ] Docs are updated in the same change (see the
      [docs-in-sync rule](https://github.com/onec-erp/onec-framework/blob/main/AGENTS.md#keeping-docs-in-sync)).
- [ ] No commercial code added to this repo (open-core boundary — see
      [README](https://github.com/onec-erp/onec-framework/blob/main/README.md#license)).

### If listing a community integration

- [ ] Edited [`community/registry.json`](https://github.com/onec-erp/onec-framework/blob/main/community/registry.json) (it validates against `registry.schema.json`).
- [ ] Ran `./gradlew generateIntegrationsDoc` and committed the regenerated `INTEGRATIONS.md`.
- [ ] The project uses its own Maven group / Java package and meets the
      [listing criteria](https://github.com/onec-erp/onec-framework/blob/main/CONTRIBUTING.md#listing-a-community-integration).
