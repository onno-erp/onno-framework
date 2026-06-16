# Contributing to onec-framework

Thanks for helping build onec-framework. There are two ways to contribute, and they have different
bars:

- **[Contribute code](#contributing-code)** to the framework, a starter, or the example app.
- **[List a community integration](#listing-a-community-integration)** you built on top of the
  framework — your own published project, added to the catalog.

By contributing you agree your contributions are licensed under the repository's
[Apache License 2.0](LICENSE).

## Contributing code

### Develop & verify

```bash
./gradlew clean check          # compile + test everything, incl. the example app and frontend
./gradlew publishToMavenLocal  # verify the artifacts are consumable (sources, javadoc, POM)
```

Narrower loops: `./gradlew :onec-framework:test` for core changes,
`./gradlew :onec-ui-starter:buildFrontend` for frontend-only changes. Java 21 is required (the Gradle
toolchain pins it).

### Keep docs in sync — required

Docs are part of the public surface; update them in the **same change** as the code. The mapping
from "what you changed" to "what to update" is the table in
[AGENTS.md → Keeping Docs In Sync](AGENTS.md#keeping-docs-in-sync). When a doc and the code disagree,
the code wins — fix the doc.

### Respect the open-core boundary

This repository is the **open-core** (Apache-2.0, Maven group `io.github.onec-erp`). Commercial
vertical connectors live in the separate, commercially licensed
[onec-enterprise](https://github.com/onec-erp/onec-enterprise) repo (`com.onec.enterprise`). Don't
add commercial or proprietary code here. See the [License section](README.md#license).

### Pull requests

- Branch from `main`, keep the change focused, and fill in the PR template.
- Make sure `./gradlew clean check` passes and docs are updated.
- New auto-configured beans need a `META-INF/spring/...AutoConfiguration.imports` entry and tests.

### Reporting bugs

Use the issue tracker. For a framework bug, reduce it to the smallest repro (a minimal
catalog/document/posting that triggers it), state the version, what you did, expected vs. actual.
Check for duplicates first.

## Listing a community integration

Built a connector, SPI implementation, UI extension, or skill on the framework? Get it into the
[community catalog](INTEGRATIONS.md). First build it well — see
[docs/EXTENDING.md](docs/EXTENDING.md) for the starter shape, naming/namespace conventions, and the
"definition of done".

### Listing criteria

To be listed, a project must:

- Be **public** and have a **README** describing what it does and how to configure it.
- Have a **declared license** (an SPDX id).
- **Build against a supported onec-framework version** (state which one).
- Use its **own Maven group and Java package** — not `io.github.onec-erp` or `com.onec.*` (reserved).
- For a starter: be gated by an `onec.<name>.enabled` flag with beans that are
  `@ConditionalOnMissingBean`.

Listed projects are maintained by their authors and are **not endorsed** by the onec-framework team.
Maintainers may decline or remove entries that are abandoned, broken, or misrepresented.

### How to get listed

1. Add an entry to [`community/registry.json`](community/registry.json) — the source of truth. It
   validates against [`community/registry.schema.json`](community/registry.schema.json); see
   [community/README.md](community/README.md) for the field reference and an example.
2. Regenerate the catalog (never edit `INTEGRATIONS.md` by hand):

   ```bash
   ./gradlew generateIntegrationsDoc
   ```

3. Open a PR including **both** `community/registry.json` and the regenerated `INTEGRATIONS.md`.

Prefer not to open a PR? File a
[community integration submission](https://github.com/onec-erp/onec-framework/issues/new?template=integration-submission.yml)
issue with the same details and a maintainer will add it.
