<!-- Thanks for contributing to onno-framework! -->

## What & why

<!-- What does this change do, and why? Link any related issue. -->

## Type

- [ ] Code change (framework / starter / example)
- [ ] Docs only
- [ ] Community integration listing

## Verification artifacts

<!--
Show that the change works in a running app, not only that it compiles. Delete lines that don't apply.
AI agents: use the onno-verify skill (onno-plugin/skills/onno-verify) — run `onno-drive features`
first and name what you exercised by its feature id (e.g. `documents/orders#cancel`).
-->

- **Commands run:** <!-- e.g. ./gradlew :onno-framework:test, ./gradlew clean check -->
- **UI changes:** <!-- screenshots or a short video (before/after) -->
- **Behaviour changes:** <!-- `onno-drive` output: feature id, before/after JSON (trimmed), and for
  posting/register changes the `preview-posting` output plus balance/movements before and after -->

## Checklist

- [ ] `./gradlew clean check` passes locally.
- [ ] Docs are updated in the same change (see the
      [docs-in-sync rule](https://github.com/onno-erp/onno-framework/blob/main/AGENTS.md#keeping-docs-in-sync)).
- [ ] No commercial code added to this repo (open-core boundary — see
      [README](https://github.com/onno-erp/onno-framework/blob/main/README.md#license)).

### If listing a community integration

- [ ] Edited [`community/registry.json`](https://github.com/onno-erp/onno-framework/blob/main/community/registry.json) (it validates against `registry.schema.json`).
- [ ] Ran `./gradlew generateIntegrationsDoc` and committed the regenerated `INTEGRATIONS.md`.
- [ ] The project uses its own Maven group / Java package and meets the
      [listing criteria](https://github.com/onno-erp/onno-framework/blob/main/CONTRIBUTING.md#listing-a-community-integration).
