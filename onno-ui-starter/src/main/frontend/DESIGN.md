# Frontend design system

The conventions and canonical components of the onno SPA. **Rule zero: reuse a canonical component
before writing a new one.** Every view/mode switcher is the same `Segmented`, every overlay is the
same `FacetSheet`, every ref picker is the same `RefSelect` — a second lookalike implementation is a
bug, not a variation. If a primitive is missing a feature, extend it in place.

Paths below are relative to `src/`.

> **Keep this current.** New reusable component, token, or convention → add it here in the same PR.

## Tokens (`index.css`, exposed via Tailwind)

- **Radius, three tiers** — `rounded-pill` (9999px: compact actions, chips, badges, segmented
  triggers), `rounded-field` (0.75rem: inputs, selects, rows, compact event blocks), and
  `rounded-panel` (1rem: cards, toolbar islands, popovers, menus, dialogs). Compatibility aliases
  `rounded-control` = `rounded-pill` and `rounded-card` = `rounded-panel` remain, but prefer the
  self-explanatory names in new code. Never use the pill tier for a panel, table/grid viewport,
  calendar lane/event rectangle, generic row, error/empty-state box, or skeleton bar.
- **Typography** — SF Pro Text/Display (system fallback), regular 400 and medium 500 only, with
  `-0.15px` tracking. Use only the 12px, 13px, 14px, and 24px scale steps.
- **Text hierarchy** — primary `#292929`, secondary `#5D5D5D`, tertiary `#9E9E9E` in light mode;
  dark mode reverses the luminance order while keeping the same semantic levels.
- **Icons** — 14px in navigation and 20px as the standard card/dialog icon.
- **Colors** — semantic vars (light + dark) plus `--chart-1..8`. Overridable at runtime through
  `onno.ui.theme.*` → `/api/theme` → CSS vars (`providers/theme-provider.tsx`). Never hardcode a
  hex; widgets use `hsl(var(--primary))` etc.
- The DivKit-rendered chrome mirrors these radii server-side (`su/onno/ui/divkit/Radii.java`) —
  change them in both places.

## Islands

Surfaces are **islands**: `rounded-panel` + `border` + `bg-card`, **no shadows**. Shadows belong
only to transient overlays (popover, tooltip) and the Segmented active pill. Each island contains
its own failures (`lib/island-error-boundary.tsx`) and — for route surfaces — owns its scroll: the
island scrolls internally (virtualized), the page body does not scroll horizontally or double-scroll.

## Lists

`components/entity-list-widget.tsx` is *the* list island: framework-owned toolbar (search, filters,
sort, group-by), keyset infinite scroll, virtual
windowing, context menu, batch actions. Server search spans every non-secret column — scalars as
text, `Ref<>` by target display value, enums by label (`Searching.java`). Custom bodies go through
`registerListRenderer` — the toolbar and feed stay framework-owned, the renderer only draws rows.
Selections larger than the server's 500-id per-request safety cap are split sequentially by the
shared API client and folded into one batch summary; screens must not implement their own chunking.
The island owns padding only on standalone entity routes. When embedded with `PageBuilder.list`,
it has no outer spacing; the surrounding page region owns sibling gaps and content insets.

Avatars: DiceBear 10 `glass` is the default (`presence-avatars.tsx#glassAvatar`);
avatars in lists and face-piles carry a thin `border border-border`.

Colored pills: a cell renders as a pill whenever the logical row carries `FieldColor`
(`enumPillStyle`, `utils.ts`) — emitted for `@EnumLabel(color)` enums AND catalog refs whose target has a `color`
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
| `AnimatedNumber` | `components/ui/animated-number.tsx` | Formatted KPI/count/stat values that should replay the shared character pop-in when replaced. |
| `NotificationBadgeMotion` | `components/ui/notification-badge-motion.tsx` | Unread dots/count pills that slide and pop when the unread count increases. |
| `Toaster` / `toast` | `components/ui/toast.tsx` | The single shadcn-style Base UI toast host and global manager: large themed surfaces, typed title/message/detail hierarchy, semantic icon wells, actions, swipe dismissal, and an animated stack. |
| `Drawer` | `components/ui/drawer.tsx` | The canonical shadcn-style Base UI drawer composition (portal, backdrop, viewport, popup, content); use it for notification and edge panels instead of hand-rolled fixed overlays. |
| `FacetSheet` / `useFacetOverlay` | `components/ui/facet-sheet.tsx` | Responsive overlay: bottom sheet (phone) / modal (tablet) / popover (desktop). |
| `Popover`, `Tooltip`, `HintIcon` | `components/ui/*` | Anchored overlays; `HintIcon` is the authored "?" help glyph. |
| `Button`, `Input`, `Textarea`, `Checkbox`, `Switch`, `Label`, `Badge` | `components/ui/*` | Form controls & pills. |
| `ColorPicker` | `components/color-picker.tsx` | Hex color fields: canonical text input plus `react-colorful` popover. |
| `Select` | `components/ui/select.tsx` | No-search dropdown; auto-drawer on touch. |
| `RefSelect` | `components/ref-select.tsx` | Searchable ref picker: server typeahead, avatars, cascading `refFilter`, quick-create, and an accessible clear choice for nullable fields. |
| `Card` | `components/ui/card.tsx` | Island surface (no shadow). |
| `Avatar` / `PresenceAvatars` | `components/ui/avatar.tsx`, `components/presence-avatars.tsx` | Avatar primitive; face-pile + dicebear default. |
| `Calendar`/`RangeCalendar`, `DateInput`, `DatePicker`, `DateRangeFacet`/`TimeRangeFacet` | `components/ui/calendar.tsx`, `ui/date-input.tsx`, `date-picker.tsx`, `date-range-facet.tsx` | The single date/time-picking system, from form field to filter chip. |
| `ContextMenu` | `components/ui/context-menu.tsx` | Right-click menus (list rows). |
| `Attachment` | `components/ui/attachment.tsx` | File chips. |
| `EntityListWidget` | `components/entity-list-widget.tsx` | The list island (see above). |
| `ChartWidget` (+`TimeRangeWidget`), `StatWidget`, `SparklineWidget`, `GaugeWidget` | `components/*.tsx` | All chart kinds, KPI/trend/gauge tiles. |
| `KanbanWidget`, `CalendarWidget`, `ListWidget`, `MapWidget` | `components/*.tsx` | Board / calendar / compact list / map surfaces. |
| `DialogShell` | `components/ui/dialog-shell.tsx` | Canonical focus-managed shell for action forms, confirmations, and typed action feedback. |
| `ActionFormDialog` | `components/action-form-dialog.tsx` | Server-declared action forms inside `DialogShell`; retains input and renders rejection errors. |
| `IslandErrorBoundary` | `lib/island-error-boundary.tsx` | Per-island error containment. |
| `registerWidget` / `resolveWidget` | `lib/widget-bridge.tsx` | Widget-type registry (extension point). |

Utilities to reach for instead of reinventing: `lib/time-range.ts`, `lib/widget-data.ts` (bucket
shaping/labels), `lib/chart-colors.ts`, `lib/format.ts` + `lib/cell-format.ts`, `lib/utils.ts`
(`cn`), `lib/messages.ts` (chrome strings — mirror of `UiMessages.DEFAULTS`, change both in one PR).

### Radius mapping

| Structure | Class |
| --- | --- |
| Pill action, compact badge/chip, segmented trigger | `rounded-pill` |
| Input/select, generic row, compact calendar event | `rounded-field` |
| Card, bounded panel, dialog/popover/menu | `rounded-panel` |
| Large grid/table/scroll viewport | `rounded-field` or no radius |

Anti-pattern: `rounded-control`/`rounded-pill` on a schedule viewport or event block creates a
9999px capsule that can obscure the grid. The word “control” in the compatibility alias does not
mean “any interactive container.”

## Misc conventions

- Search bars sit right-aligned in the island toolbar; search covers all columns.
- Workspace tab reordering keeps native cross-pane drag semantics, but supplies a lifted custom drag
  image, leaves the source as a real-width placeholder, and shifts neighboring tabs with FLIP using
  `--duration-fast` / `--ease-smooth-out`. Preserve its reduced-motion treatment.
- Workspace tabs distinguish visibility from command focus: the active tab in the focused island
  uses the brand accent; an active tab in another island uses the neutral `muted` surface; and a
  merely open, inactive tab stays unfilled until hover. Exactly one tab is the command target for
  global actions such as Escape. The fixed tab strip scrolls horizontally when needed and must
  always hide vertical overflow.
- Desktop rail highlighting follows the focused tab's authored Layout section. When the focused
  pane is empty or its route is outside authored navigation, the selected drawer section becomes
  the single highlighted fallback; never paint both the routed and selected sections as active.
  Opening a rail section transfers command focus to navigation without closing pane tabs: their
  active tabs remain visible with the muted pane-active treatment, and global commands such as
  Escape do not target a tab until a drawer destination or pane is focused again. Pointer/touch
  interaction anywhere in a pane, or keyboard focus entering one of its controls, restores that
  pane as the command target.
- `Segmented` uses one measured sliding pill with the same motion tokens; first paint and geometry
  changes snap, value changes tween. KPI value cards and `StatWidget` use `AnimatedNumber`; both
  primitives must retain their reduced-motion guards.
- Notification triggers and server-emitted notification indicator islands share
  `NotificationBadgeMotion`; keep the trigger stationary and animate only its dot/count badge.
- Toast calls use the local `toast` API and render through the one Base UI `Toaster`; do not mount
  another host. Base UI owns the manager, focus, swipe state, measurements, and lifecycle
  attributes; `index.css` composes those attributes into the onno stack motion. Keep the surface
  monochrome and borderless (the dark theme uses the raised `secondary` surface); semantic status
  colour belongs to the icon well and detail markers. Actual actions use the filled button;
  acknowledgement/dismiss controls use the quiet button. Prefer the server-side
  `ActionResult.toast(ActionToast…)` builder over a bare message when an outcome benefits from a
  title, explanation, or details.
- The shell logo's empty `onno://` action is a semantic home intent. Resolve it through
  `shell.home`; only profiles that actually own a dashboard should land on `/`.
- Esc closes the topmost layer only (overlay before page).
- Keyboard shortcuts must work under non-Latin layouts (match on key position, not character).
- No hardcoded English in chrome — every string goes through the `UiMessages` key set.
- Custom widgets import host primitives from `@onno/widget-sdk` (`Button`, `Segmented`,
  `Select`, …) — never rebuild lookalikes inside a widget.
