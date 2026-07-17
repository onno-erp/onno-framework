# Frontend design system

The conventions and canonical components of the onno SPA. **Rule zero: reuse a canonical component
before writing a new one.** Every view/mode switcher is the same `Segmented`, every overlay is the
same `FacetSheet`, every ref picker is the same `RefSelect` — a second lookalike implementation is a
bug, not a variation. If a primitive is missing a feature, extend it in place.

Paths below are relative to `src/`.

> **Keep this current.** New reusable component, token, or convention → add it here in the same PR.

## Tokens (`index.css`, exposed via Tailwind)

- **Radius, three tiers** — `rounded-control` (9999px pill: chips, badges, segmented, buttons),
  `rounded-field` (0.625rem: inputs, search, tab strips), `rounded-card` (0.9rem: cards, toolbar
  island, popovers, menus, dialogs). Never hand-pick a `rounded-*` outside these.
- **Colors** — semantic vars (light + dark) plus `--chart-1..8`. Overridable at runtime through
  `onno.ui.theme.*` → `/api/theme` → CSS vars (`providers/theme-provider.tsx`). Never hardcode a
  hex; widgets use `hsl(var(--primary))` etc.
- The DivKit-rendered chrome mirrors these radii server-side (`su/onno/ui/divkit/Radii.java`) —
  change them in both places.

## Islands

Surfaces are **islands**: `rounded-card` + `border` + `bg-card`, **no shadows**. Shadows belong
only to transient overlays (popover, tooltip) and the Segmented active pill. Each island contains
its own failures (`lib/island-error-boundary.tsx`) and — for route surfaces — owns its scroll: the
island scrolls internally (virtualized), the page body does not scroll horizontally or double-scroll.

## Lists

`components/entity-list-widget.tsx` is *the* list island: framework-owned toolbar (search, filters,
sort, group-by), keyset infinite scroll by default (`feedMode: "paged"` is the opt-out), virtual
windowing, context menu, batch actions. Server search spans every non-secret column — scalars as
text, `Ref<>` by target display value, enums by label (`Searching.java`). Custom bodies go through
`registerListRenderer` — the toolbar and feed stay framework-owned, the renderer only draws rows.

Avatars: dicebear `notionists-neutral` is the default (`presence-avatars.tsx#notionistsAvatar`);
avatars in lists and face-piles carry a thin `border border-border`.

Colored pills: a cell renders as a pill whenever the row carries `{col}_color` (`enumPillStyle`,
`utils.ts`) — emitted for `@EnumLabel(color)` enums AND catalog refs whose target has a `color`
attribute (column-name convention, like `avatar_url`). The ref picker (`ref-select.tsx#RefRow`)
shows the same color as a `size-2.5` dot before the option label (avatar wins when both exist).

Cell menus: a column whose descriptor carries `cellMenu` (from `ListSpec.cellMenu`) opens ONLY that
row-action submenu, flat, on cell right-click (`rowMenu.only` in `entity-list-widget.tsx`); the rest
of the row keeps the full context menu. Flat table view only.

## Charts & dashboards

`components/chart-widget.tsx` + `lib/time-range.ts` + `lib/widget-data.ts`:

- Auto-granularity picks the coarsest unit yielding **≥10 points** (`MIN_POINTS`).
- Date-bucketed axes are **zero-filled** server-side; pies drop zero slices.
- One grafana-style time picker (`TimeRangeWidget`, presets `15m…1y,all`, default 30d) drives all
  charts on a board (`providers/time-range-provider.tsx`, persisted).
- Axis labels are real dates per granularity ("HH:mm", "MMM d", "MMM yyyy") — never "Wk 1".
- Legend entries toggle series (hidden ones stay greyed in the legend).
- Stat tiles compare **vs the previous period of equal span** (`stat-widget.tsx`), disabled for
  unbounded ranges.

## Canonical component inventory

| Component | Path | Use for |
| --- | --- | --- |
| `Segmented` | `components/ui/segmented.tsx` | Every mutually-exclusive view/mode switcher. Documented exceptions: tool palettes, server-emitted DivKit form tab strips. |
| `FacetSheet` / `useFacetOverlay` | `components/ui/facet-sheet.tsx` | Responsive overlay: bottom sheet (phone) / modal (tablet) / popover (desktop). |
| `Popover`, `Tooltip`, `HintIcon` | `components/ui/*` | Anchored overlays; `HintIcon` is the authored "?" help glyph. |
| `Button`, `Input`, `Textarea`, `Checkbox`, `Switch`, `Label`, `Badge` | `components/ui/*` | Form controls & pills. |
| `Select` | `components/ui/select.tsx` | No-search dropdown; auto-drawer on touch. |
| `RefSelect` | `components/ref-select.tsx` | Searchable ref picker: server typeahead, avatars, cascading `refFilter`, quick-create. |
| `Card` | `components/ui/card.tsx` | Island surface (no shadow). |
| `Avatar` / `PresenceAvatars` | `components/ui/avatar.tsx`, `components/presence-avatars.tsx` | Avatar primitive; face-pile + dicebear default. |
| `Calendar`/`RangeCalendar`, `DateInput`, `DatePicker`, `DateRangeFacet`/`TimeRangeFacet` | `components/ui/calendar.tsx`, `ui/date-input.tsx`, `date-picker.tsx`, `date-range-facet.tsx` | The single date/time-picking system, from form field to filter chip. |
| `ContextMenu` | `components/ui/context-menu.tsx` | Right-click menus (list rows). |
| `Attachment` | `components/ui/attachment.tsx` | File chips. |
| `EntityListWidget` | `components/entity-list-widget.tsx` | The list island (see above). |
| `ChartWidget` (+`TimeRangeWidget`), `StatWidget`, `SparklineWidget`, `GaugeWidget` | `components/*.tsx` | All chart kinds, KPI/trend/gauge tiles. |
| `KanbanWidget`, `CalendarWidget`, `ListWidget`, `MapWidget` | `components/*.tsx` | Board / calendar / compact list / map surfaces. |
| `ActionFormDialog` | `components/action-form-dialog.tsx` | Server-declared action forms. |
| `IslandErrorBoundary` | `lib/island-error-boundary.tsx` | Per-island error containment. |
| `registerWidget` / `resolveWidget` | `lib/widget-bridge.tsx` | Widget-type registry (extension point). |

Utilities to reach for instead of reinventing: `lib/time-range.ts`, `lib/widget-data.ts` (bucket
shaping/labels), `lib/chart-colors.ts`, `lib/format.ts` + `lib/cell-format.ts`, `lib/utils.ts`
(`cn`), `lib/messages.ts` (chrome strings — mirror of `UiMessages.DEFAULTS`, change both in one PR).

## Misc conventions

- Search bars sit right-aligned in the island toolbar; search covers all columns.
- Esc closes the topmost layer only (overlay before page).
- Keyboard shortcuts must work under non-Latin layouts (match on key position, not character).
- No hardcoded English in chrome — every string goes through the `UiMessages` key set.
- Custom widgets import host primitives from `@onno/widget-sdk` (`Button`, `Segmented`,
  `Select`, …) — never rebuild lookalikes inside a widget.
