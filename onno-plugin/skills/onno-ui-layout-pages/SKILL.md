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
- Desktop `NavStyle.SIDEBAR` is two-tier: each `section(...)` becomes an icon in the collapsible app
  rail, and the section's routes appear in its nested drawer. Group sections by user job/workspace,
  not Java entity type. Keep workflow-local controls inside the page/widget; do not duplicate the
  shell. Mixed `.page(...)`, `.catalog(...)`, `.document(...)`, and `.register(...)` calls retain
  declaration order inside the drawer.
- A `Page` can live at `/`, `/settings`, any custom route, or a default entity route.
- **Home is automatic.** An authored `Page.route()` of `/` (or dashboard widgets for the active layout/profile) makes onno add one leading home navigation item. Set its label with `PageBuilder.title("Overview")`. Do not also add `.page("/", ...)` to a section or `spec.link("/", ...)`: those create a second entry for the same destination. With neither a root page nor dashboard widgets, no home item is added.
- For other custom routes, use `spec.link("/inbox", "Inbox", "inbox")` for a direct top-level entry, or `spec.section("Operations").page("/reports", "Reports", "chart")` for a nested entry. Author route paths without the browser's `/ui` prefix.
- For an entity-backed fullscreen workspace, use `page.list(Entity.class, view -> view.fill())`.
  `page.bare()` only removes the authored page header; it does not allocate height or remove page
  padding. Read [the full-height recipe](../onno-ui-widgets/references/full-height-workspaces.md)
  before implementing or verifying this layout.
- `Layout.profile()` names the persona; call `spec.roles(...).priority(...)` inside `configure` to
  select it. Named profiles replace the default navigation, so share/repeat all desired sections.
- `Page.profile()` must match the persona for profile-only reachability. A page with no profile is
  available to every profile; linking it controls nav presence, not authorization.
- Page actions have no entity gate and must declare `.roles(...)` when restricted. Global constant
  settings use the ADMIN-only settings API.
- Canonical entity routes use lowercase snake case, for example `/catalogs/product_groups`.
- Keep branding/static assets under `classpath:/static/ui/...`.
- For the two-tier desktop shell, author both a horizontal `.logo(light, dark)` and a compact square
  `.mark(light, dark)`. The rail falls back to the favicon, then the logo, but a dedicated mark keeps
  a wordmark from being squeezed into the 24px home affordance. Marks receive a shell border by
  default; add `.markFrame(false)` when the artwork already contains its own frame or enclosing shape.

Read [references/examples.md](references/examples.md) before writing layout/page code.

### Reusable page views

`PageView` composes reusable content through `PageBuilder.view(view)`, including in rows and
asides. Configure view instances at startup; `ListView` and the CRM views freeze on first placement
and throw `IllegalStateException` if mutated afterwards.
`ListView<E>` places the existing entity list and accepts `.defaults(d -> d.fill().sort("date", true))`.
`EntityView<E>` still owns entity columns, fields and actions; permissions and data resolution use
the existing renderer. A Page owns the route and header; a PageView does not register navigation.
CRM provides `su.onno.crm.ui.CrmInboxView` (final; declare it as a `@Bean`, inject into the Page)
for typed inbox presentation: one method per surface plus an optional breakpoint, e.g.
`header(h -> ...)`, `header(Breakpoint.NARROW|MOBILE, h -> ...)`, `listHeader(MOBILE, ...)`,
`composer(MOBILE, ...)`. Each configured toolbar fully replaces renderer defaults (unset regions are
empty; an item may sit in one region only). Workspace beans still own the authorized feed and
declared filters; `CrmInboxViewValidator` fails startup if a `CrmInboxView` bean names an unknown
workspace and fails on filter keys the workspace bean does not declare (facets with runtime options use `CrmInboxWorkspace.FacetOptions`). `CrmConnectionsView` types the `crmConnections` connections widget (`crmSettings` remains an alias).
Existing `page.list(...)` and widget config remain valid.
