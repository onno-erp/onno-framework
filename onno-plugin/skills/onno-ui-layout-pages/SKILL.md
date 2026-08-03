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
- `Layout.profile()` names the persona; call `spec.roles(...).priority(...)` inside `configure` to
  select it. Named profiles replace the default navigation, so share/repeat all desired sections.
- `Page.profile()` must match the persona for profile-only reachability. A page with no profile is
  available to every profile; linking it controls nav presence, not authorization.
- Page actions have no entity gate and must declare `.roles(...)` when restricted. Global constant
  settings use the ADMIN-only settings API.
- Canonical entity routes use lowercase snake case, for example `/catalogs/product_groups`.
- Keep branding/static assets under `classpath:/static/ui/...`.

Read [references/examples.md](references/examples.md) before writing layout/page code.
