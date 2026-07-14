---
name: onno-ui-layout-pages
description: >-
  Author onno-framework Layout and Page beans. Use when creating sidebar navigation, sections,
  persona/profile layouts, role-scoped layouts, shell branding, logo/favicon/theme, dashboard pages,
  settings pages, custom routes, default route overrides, PageBuilder widgets/lists/actions/rows,
  right-rail aside layouts, or deciding why an EntityView does not appear in nav.
---

# onno UI Layouts And Pages

`Layout` defines the shell and navigation. `Page` defines route content. Entity list/form details
belong in `EntityView`, not here.

## Key Rules

- Nav is curated. A catalog/document/register appears in the sidebar only if a layout section lists
  it.
- A `Page` can live at `/`, `/settings`, any custom route, or a default entity route.
- A custom page route appears in nav only when a layout section links it with `.page(...)`.
- Persona selection uses `profile()`, `roles`, `viewport()`, and priority.
- Keep branding/static assets under `classpath:/static/ui/...`.

Read [references/examples.md](references/examples.md) before writing layout/page code.
