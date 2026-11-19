# Frontend design system

The conventions and canonical components of the onno SPA. **Rule zero: reuse a canonical component
before writing a new one.** Every view/mode switcher is the same `Segmented`, every overlay is the
same `FacetSheet`, every ref picker is the same `RefSelect` — a second lookalike implementation is a
bug, not a variation. If a primitive is missing a feature, extend it in place.

Paths below are relative to `src/`.

> **Keep this current.** New reusable component, token, or convention → add it here in the same PR.

## Component organization

Components in `src/components/` are grouped by responsibility. Keep helpers and styles beside
their owning component, and keep shared primitives independent of business components.

| Directory | Owns |
| --- | --- |
| `ui/` | Shared visual primitives and overlays. |
| `fields/` | Business field editors and reference displays. |
| `entities/` | Record forms and tags. |
| `lists/` | Entity/register lists, grouping, selection and filter helpers. |
| `maps/` | Map rendering, editing and list/map adapters. |
| `widgets/` | Charts, calendars, boards, settings and task surfaces. |
| `shell/` | Navigation, account controls, mobile layout and updates. |
| `collaboration/` | Comments, presence and notifications. |
| `auth/` | Login, passwords, SSO and user administration. |
| `actions/` | Record actions and command input dialogs. |
| `pages/` | Composable page blocks and their error boundary. |

Import public components through `@onno/ui`; these directories are internal implementation details.
The exports in `src/index.ts` remain the stable entry point for applications.

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
- **Light/dark mode** — read it from `useTheme().resolvedTheme` (`providers/theme-provider.tsx`),
  which follows an OS change while the theme is `system`. Never query `prefers-color-scheme`
  yourself: a private read misses the change.
- **Breakpoints** — one device class for the whole UI: `useViewport()` (`lib/viewport.ts`) is
  `mobile` below 768px, `tablet` from 768px, `desktop` from 1024px — Tailwind's `md` and `lg`.
  The shell layout, page `Row`s, the calendar, facet overlays and windows all switch on it; never
  classify the screen with your own width media query or a fixed screen-width comparison
  (`innerWidth < 600`; measuring geometry against the screen edge is fine). CSS that changes with
  the device class uses `md:`/`lg:` (or `max-md:`/`max-lg:`; a hand-written `@media` uses the same
  `not all and (min-width: 768px)` form), which switch at the same widths. `sm:` (640px) is not a
  device-class boundary: keep it to fitting content to the available width (padding, columns,
  dialog widths), never to switching between phone and tablet behaviour.

## Islands

Surfaces are **islands**: `rounded-panel` + `border` + `bg-card`, **no shadows**. Shadows belong
only to transient overlays (popover, tooltip) and the Segmented active pill. Each island contains
its own failures (`lib/error-boundary.tsx`) and — for route surfaces — owns its scroll: the
island scrolls internally (virtualized), the page body does not scroll horizontally or double-scroll.

## Lists

`components/lists/entity-list.tsx` is *the* list island: framework-owned toolbar (search, filters,
sort, group-by), keyset infinite scroll, virtual
windowing, context menu, batch actions. Server search spans every non-secret column — scalars as
text, `Ref<>` by target display value, enums by label (`Searching.java`). Custom bodies are a list's
`custom` view component (or `EntityListView`'s `renderer`) — the toolbar and feed stay framework-owned,
the renderer only draws rows.

Composed workspaces may pass `EntityListView.initialPage` to reuse a server bootstrap response.
It contains the feed URL, the effective query (sort/search/filters, without cursor/limit/count),
rows, total, and next cursor. The host consumes it once only when the feed and query match; changed
filters and live refreshes still fetch normally. Do not render unverified bootstrap rows or treat
this as a cache shared between users or workspaces.

An app's own components render only inside the authenticated shell — app code never runs data
reads on the login screen; protected reads belong inside the component lifecycle.

Custom list renderers receive optional `hasMore`, `loadingMore`, `loadMoreFailed` and `loadMore()`
props. A renderer with its own scrolling pane must call `loadMore()` near that pane's bottom and
provide an accessible load-more/retry button (disabled while loading). Stop automatic retries after
`loadMoreFailed`; an explicit retry uses the same cursor. The host retains the scoped feed, filters,
query generation, row deduplication and loading guard. Do not fetch the entire catalog in a renderer.
The host shows a "Loading more…" status below the renderer while a next window loads; a renderer
that draws that feedback inside its own pane opts out with
a static `ownsLoadingMore = true` on the view component (`ListRendererComponent`).

`EntityListView` props beyond `list`/`renderer`/`toolbarPlacement` — `initialPage`, `toolbarFooter`,
`toolbarContent`, `headerExtra`, `queryParams`, `refreshKey` and `toolbarOptions` (including
`filterSidebar`, the docked filter rail beside a custom workspace) — are **host-internal**: they serve
first-party composed workspaces (the CRM inbox). They may change in any release; app components
should not rely on them.

Selections larger than the server's 500-id per-request safety cap are split sequentially by the
shared API client and folded into one batch summary; screens must not implement their own chunking.
The island owns padding only on standalone entity routes. When embedded in a page with `EntityList`,
it has no outer spacing; the surrounding page region owns sibling gaps and content insets.

Avatars: DiceBear 10 `glass` is the default (`presence-avatars.tsx#glassAvatar`);
avatars in lists and face-piles carry a thin `border border-border`.

Colored pills: a cell renders as a pill whenever the logical row carries `FieldColor`
(`enumPillStyle`, `utils.ts`) — emitted for `@EnumLabel(color)` enums AND catalog refs whose target has a `color`
attribute (column-name convention, like `avatar_url`). The ref picker (`ref-select.tsx#RefRow`)
shows the same color as a `size-2.5` dot before the option label (avatar wins when both exist).

Cell menus: a column whose field customization names a `commandGroup` opens ONLY that
command group, flat, on cell right-click (`rowMenu.only` in `entity-list.tsx`); the rest
of the row keeps the full context menu. Flat table view only.

## Charts & dashboards

`components/widgets/chart.tsx` + `lib/time-range.ts` + `lib/aggregate-data.ts`:

- Auto-granularity uses minute buckets through 6h, hour through 2d, day through 70d, week through
  300d, then month. The shared time-range footer displays the resolved interval and lets the viewer
  override it for every auto-bucketed chart; authored fixed buckets stay fixed.
- Date-bucketed axes are **zero-filled** server-side; pies drop zero slices.
- One grafana-style time picker (`TimeRange`, presets `15m…1y,all`, default 30d) drives all
  charts on a board (`providers/time-range-provider.tsx`, persisted).
- Axis labels are real dates per granularity ("HH:mm", "MMM d", "MMM yyyy") — never "Wk 1".
- Legend entries toggle series (hidden ones stay greyed in the legend).
- Chart headlines show additive period totals for grouped `count`/`sum`; grouped
  `avg`/`min`/`max` omit the figure rather than summing non-additive buckets.
- Stat tiles compare **vs the previous period of equal span** (`components/widgets/stat.tsx`), disabled for
  unbounded ranges.

## Canonical component inventory

| Component | Path | Use for |
| --- | --- | --- |
| `Segmented` | `components/ui/segmented.tsx` | Every mutually-exclusive view/mode switcher. Documented exception: tool palettes. |
| `AnimatedNumber` | `components/ui/animated-number.tsx` | Formatted KPI/count/stat values that should replay the shared character pop-in when replaced. |
| `NotificationBadgeMotion` | `components/ui/notification-badge-motion.tsx` | Unread dots/count pills that slide and pop when the unread count increases. |
| `Toaster` / `toast` | `components/ui/toast.tsx` | The single shadcn-style Base UI toast host and global manager: large themed surfaces, typed title/message/detail hierarchy, semantic icon wells, actions, swipe dismissal, and an animated stack. |
| `Drawer` | `components/ui/drawer.tsx` | The canonical shadcn-style Base UI drawer composition (portal, backdrop, viewport, popup, content); use it for notification and edge panels instead of hand-rolled fixed overlays. |
| `useViewport` | `lib/viewport.ts` | The shared device class (`mobile` / `tablet` / `desktop`): the shell layout, page `Row`s, the calendar, facet overlays and windows switch on it. |
| `FacetSheet` / `useFacetOverlay` | `components/ui/facet-sheet.tsx` | Responsive overlay: bottom sheet (phone) / modal (tablet) / popover (desktop), from `useViewport`. |
| `Popover`, `Tooltip`, `HintIcon` | `components/ui/*` | Anchored overlays; `HintIcon` is the authored "?" help glyph. |
| `Button`, `Input`, `Textarea`, `Checkbox`, `Switch`, `Label`, `Badge` | `components/ui/*` | Form controls & pills. |
| `ColorPicker` | `components/fields/color-picker.tsx` | Hex color fields: canonical text input plus `react-colorful` popover, or a compact swatch (`compact`) with `onCommit` for dense rows; exported to applications. |
| `Select` | `components/ui/select.tsx` | No-search dropdown: bottom sheet on phones, centred list over a backdrop on tablets, anchored popover on desktop. |
| `RefSelect` | `components/fields/ref-select.tsx` | Searchable ref picker: server typeahead, avatars, cascading `filterBy`, quick-create, and an accessible clear choice for nullable fields. A server-flagged `_recent` head of the options sits under a muted "Recent" label, ruled off from the rest. Entity forms add inline icon buttons (`size-6`, muted, `hover:bg-accent`) left of the chevron: open the selected record (`ExternalLink`, `onno://` hand-off) and clear (`X`); they sit beside the trigger, never inside it (#409). Exported from `@onno/ui` with `allowCreate`, `disabled`, `placeholder`, `valueLabel`, `id`/`aria-*` and a trigger `className`. |
| `Card` | `components/ui/card.tsx` | Island surface (no shadow). |
| `Avatar` / `PresenceAvatars` | `components/ui/avatar.tsx`, `components/collaboration/presence-avatars.tsx` | Avatar primitive; face-pile + dicebear default. |
| `Calendar`/`RangeCalendar`, `DateInput`, `DatePicker`, `DateTimePicker`, `DateRangeFacet`/`TimeRangeFacet` | `components/ui/calendar.tsx`, `components/ui/date-input.tsx`, `components/fields/date-picker.tsx`, `components/fields/date-time-picker.tsx`, `components/lists/date-range-facet.tsx` | The single date/time-picking system, from form field to filter chip. |
| `ContextMenu` | `components/ui/context-menu.tsx` | Right-click menus (list rows). |
| `Attachment` | `components/ui/attachment.tsx` | File chips. |
| `EntityListView` | `components/lists/entity-list.tsx` | The list island (see above). |
| `Chart` (+`TimeRange`), `Stat`, `Sparkline`, `Gauge` | `components/widgets/chart.tsx`, `components/widgets/stat.tsx`, `components/widgets/sparkline.tsx`, `components/widgets/gauge.tsx` | All chart kinds, KPI/trend/gauge tiles. |
| `Kanban`, `Calendar`, `RecentRecords`, `EntityMap` | `components/widgets/kanban.tsx`, `components/widgets/calendar.tsx`, `components/widgets/recent-records.tsx`, `components/maps/entity-map.tsx` | Board / calendar / compact list / map surfaces. |
| `DialogShell` | `components/ui/dialog-shell.tsx` | Canonical focus-managed shell for action forms, confirmations, and typed action feedback. Selects and popovers inside it portal into the dialog; while one is open, a press outside it closes only that popup, the next one closes the dialog (`useOutsidePressGuard`, also used by the ref picker's "Show all…" dialog). |
| `FloatingWindow` / `windows` | `components/ui/window-manager.tsx` | Non-modal application windows: docked (bottom-right row), floating (drag + 8-edge resize), minimized (title bar, content stays mounted), maximized; full screen on phones. One shell layer (`WindowLayer`, `z-[45]`: above the page, below popovers `z-50` and modals `z-[70]`). Use it for work beside the page (compose, previews, tools); use `DialogShell` for blocking decisions. |
| `CommandFormDialog` | `components/actions/command-form-dialog.tsx` | Server-declared command input forms inside `DialogShell`; retains input and renders rejection errors. |
| `ErrorBoundary` | `lib/error-boundary.tsx` | Per-component error containment (`Boundary` in `components/pages/boundary.tsx` wraps a data component in it). |

Utilities to reach for instead of reinventing: `lib/time-range.ts`, `lib/aggregate-data.ts` (bucket
shaping/labels), `lib/chart-colors.ts`, `lib/format.ts` + `lib/cell-format.ts`, `lib/utils.ts`
(`cn`), `lib/messages.ts` (chrome strings — mirror of `UiMessages.DEFAULTS`, change both in one PR),
`lib/notify-error.ts` (`notifyError`: report a caught request failure exactly once).

List selection actions appear inline in the scrolling facet rail, using shared `Button`
`size="toolbar"` (32px, compact text) and `variant="subtle"`: a solid neutral pill shared with filter triggers.
Connection-card secondary actions share this variant and size, with leading icons. Keep title/count and primary actions
visible; selection must not change toolbar height. Batch delete retains two-step confirmation.

Checkboxes follow the shadcn/Radix primitive: 16px square, 4px corners, a neutral input border
and subtle dark fill while unchecked, and primary fill for checked or mixed states. Use the shared
`Checkbox` for forms and list selection; do not add accent borders to unchecked instances.

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

Semantic radii are mandatory on themeable form boundaries. Every text input, textarea, search field,
date input, and select/combobox/listbox trigger—including `RefSelect` and calendar month/year
triggers—uses `rounded-field`. Do not use raw Tailwind radius utilities such as `rounded-sm`,
`rounded-md`, `rounded-lg`, `rounded-xl`, or `rounded-2xl` there; they bypass `onno.ui.theme`
branding. Closed enum and reference pickers share `selectTriggerClassName` from
`components/ui/select.tsx`; change that primitive instead of styling `RefSelect` separately.
Intrinsic shapes such as checkboxes, avatars, and calendar selection geometry are explicit
exceptions. Keep component tests for every custom picker trigger.

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
- Desktop rail highlighting follows the focused tab's navigation section. When the focused
  pane is empty or its route is outside authored navigation, the selected drawer section becomes
  the single highlighted fallback only while its submenu is visible; never paint both the routed
  and selected sections as active. Collapsing a submenu preserves the focused page’s rail highlight.
  Direct links highlight only for a focused page, and become inactive when its last tab closes.
  An untitled section with one item is a direct rail destination: clicking it navigates immediately
  and leaves the nested drawer closed because there is no submenu to reveal.
  While that direct destination is selected, its rail footer shows the signed-in user's avatar in
  place of the unavailable collapse arrow; it opens the identity, Appearance, profile, and Sign out
  menu. Sections with submenus retain the collapse arrow and the full account dock below the drawer.
  Opening a rail section transfers command focus to navigation without closing pane tabs: their
  active tabs remain visible with the muted pane-active treatment, and global commands such as
  Escape do not target a tab until a drawer destination or pane is focused again. Pointer/touch
  interaction anywhere in a pane, or keyboard focus entering one of its controls, restores that
  pane as the command target.
- `Segmented` uses one measured sliding pill with the same motion tokens; first paint and geometry
  changes snap, value changes tween. KPI value cards and `Stat` use `AnimatedNumber`; both
  primitives must retain their reduced-motion guards.
- Notification triggers and server-emitted notification indicator islands share
  `NotificationBadgeMotion`; keep the trigger stationary and animate only its dot/count badge.
- Keep notification rows quiet and single-purpose: one avatar/type icon, one title/body hierarchy,
  and a lightweight destination affordance rather than a repeated type chip. The notification bell
  owns unread state; do not repeat it across workspace tabs or navigation.
- Toast calls use the local `toast` API and render through the one Base UI `Toaster`; do not mount
  another host. Base UI owns the manager, focus, swipe state, measurements, and lifecycle
  attributes; `index.css` composes those attributes into the onno stack motion. Keep the surface
  monochrome and borderless (the dark theme uses the raised `secondary` surface); semantic status
  colour belongs to the icon well and detail markers. Actual actions use the filled button;
  acknowledgement/dismiss controls use the quiet button. Prefer a server-side
  `CommandResult` with `.title(...)`/`.detail(...)` over a bare message when an outcome benefits from a
  title, explanation, or details.
- One failed request, one toast (#505). `fetchJson` toasts a failure by default and marks the
  `ApiError` `notified`. A caller with its own wording passes `{ silent: true }`; a catch block that
  ends a user action reports through `notifyError` instead of calling `toast.error`, so it adds a
  toast only where none was shown — including what the api layer leaves to its caller (network
  errors, field errors without an inline form, typed feedback, 401).
- The shell logo's empty `onno://` action is a semantic home intent. Resolve it through
  `shell.home`; only apps that declare a `/` page land on `/`.
- Esc closes the topmost layer only (overlay before page).
- Keyboard shortcuts must work under non-Latin layouts (match on key position, not character).
- No hardcoded English in chrome — every string goes through the `UiMessages` key set.
- App and starter components import host primitives from `@onno/ui` (`Button`, `Segmented`,
  `Select`, …) — never rebuild lookalikes.

### Shared button settings

Use `Button` (exported from `@onno/ui`) for inbox, list and connection
actions. `variant="subtle" size="toolbar"` is the compact neutral pill. Its automatic
outline is solid; set `outlineStyle="solid"`, `"dashed"`, `"dotted"` or `"none"` to
choose explicitly. `outlineStyle="auto"` preserves the variant default. Configure these
appearances in `components/ui/button.tsx`, not individual page class strings. Filter
triggers reuse `buttonVariants` for the same dimensions and neutral appearance.

List selection checkboxes retain a 16px visible box and use a transparent 36px hit area.
Unchecked boxes use an opaque background and a muted-foreground border so row hover and
selection fills do not muddy their appearance. Pointer events stay on the checkbox, not row navigation.

`Badge` accepts an optional configured hex `color`, using the shared `enumPillStyle` contrast calculation. CRM contact stages and entity tags use this filled pill treatment; unknown colors fall back to the semantic badge variant.

Components can anchor a shared `PopoverContent` to an existing field using `PopoverAnchor` with `asChild`. Use this for input-driven suggestion popovers without adding a separate trigger button; preserve input focus via the popover autofocus callbacks. CRM controls use the `@onno/ui` buttons, labels, inputs, selects, and popovers.

List rows (`[data-onno-row]`) share one tint family in `index.css`: hover and selection are a
low-alpha `--primary` wash, and the "Open in list" focused row (`data-onno-focused`, #410) is a
slightly stronger wash plus an inset 3px `--primary` leading bar, with a single outline pulse
on arrival (`--ease-smooth-out`; none under reduced motion). Do not add other row highlight
colours; reuse these attributes.

Use `DatePicker` for date-only values and `DateTimePicker` for local date plus 24-hour time.
Both share `DateInput` and the calendar; the legacy `DatePicker includeTime` prop remains supported.

Document tabular sections use `TabularSectionEditor` in `record-form.tsx`: a semantic
table with one shared column sizing model, sticky headers, centered row numbers and an internally
scrolling viewport. Keep the Add row / Undo remove footer outside the horizontal scroll.
Cells reuse `AttrControl`; preserve native picker, textarea and IME key handling. Row keys
must survive insertion/removal so neighboring picker state and focus stay attached to the
same row. Keep a single muted Remove action per row. Localize row actions through `UiMessages`.

Forms with tabular sections use the available pane width rather than the scalar form's
`max-w-2xl` cap. Keep section titles and counts unbroken; show keyboard help through the shared `HintIcon`
in the section header. The table remains the horizontal scroll boundary on narrow panes.

### CRM workspace sizing

CRM chat layouts respond to the widget container, not only the browser viewport. Keep the list, history and composer within the host's allocated height (`min-h-0` throughout). The composer stays in document flow; only history and long lists scroll. Under 720px use a list-to-chat drill-in with a visible back control; under 1100px contact details overlay the chat with focus restoration. Keep secondary actions behind the conversation actions disclosure so contact names remain legible.

Use real borders for CRM pane outlines inside clipped containers. Outer Tailwind rings extend beyond the box and are cut off by the workspace and contact-slot overflow boundaries.

Keep `FacetSheet` mounted with its controlled `open` prop; conditionally removing it skips Vaul’s exit animation. Closed sheets must not intercept Escape. CRM chat slides begin on user navigation, not initial loading or a compact-layout breakpoint change.

Top-level mobile section switches use a quick 120ms fade, never a horizontal slide. Opening details and returning to a retained parent use the shared directional slide.

The mobile bottom-bar shell occupies `100dvh` and places Back inside the page title/actions header through a shared `data-onno-page-header` hook (`lib/mobile-header.ts`).
The route-local shell portal owns the accessible 44px control and preserves dirty-form confirmation;
headerless/loading/error pages retain a fallback Back row. Desktop headers are unchanged.
The shell allocates its page viewport,
and a measured reservation for the floating bottom navigation. Each retained route owns its page scroller;
full-height list/workspace surfaces consume that allocation and scroll only their inner panes.
Content-sized forms and dashboards may scroll within the route viewport. The bottom menu has a transparent fixed wrapper, safe-area clearance, and an additional gap above
the screen edge. Reserve its actual measured height, never guessed padding, for full-height surfaces.

Compact CRM composers use a full-width account selector, an accessible icon Send button,
and a compact retry action. Keep attachment actions disabled when unavailable, with the reason on their hover wrapper;
never open the file picker while the channel is unavailable or the file limit is reached. Preserve message history space and keep the composer in flow.

The CRM chat header centers the contact name between balanced action areas. The profile avatar
on the right opens contact details; do not add a duplicate sidebar toggle or a generic history
subtitle. Compact layouts show those details as a third full-width navigation page with Back;
desktop keeps the same contact content in its aside.

`WorkspaceToolbar` is the canonical configurable layout for operational workspace controls.
Its ordered regions are start/center/end/secondary/overflow, with optional compact overrides.
Callers supply already-authorized nodes; layout never changes permission or command semantics.
A custom list renderer can opt into `toolbarPlacement="renderer"` and place `props.toolbar`
in its own pane, including empty feeds. Never rebuild a second search/filter state in a widget.

List toolbars may use `compactPresentation="sheet"`: touch layouts and toolbar containers
narrower than 560px show one full-width 36px search
field and a matching Filters button, with other controls in a bottom sheet. Avoid stacking
multiple desktop chip/select rows above a phone list. Nested facet sheets use Vaul's
nested root; Escape dismisses only the topmost dialog.

Compact workspace filter chips, select triggers and search use a shared 36px visible height, field radius, neutral fill and solid border. Search text remains 16px to avoid mobile browser focus zoom; its placeholder matches the 14px control labels. Do not enlarge individual CRM dropdowns independently.

`ResponsiveActionPanel` composes canonical Popover and FacetSheet primitives. It anchors to its
trigger on desktop, uses a tablet modal and a phone sheet. Use it for compact filter panels and
workspace action menus; container width selects control density, viewport selects overlay type.
It is exported from `@onno/ui`.

For command menus, use `ResponsiveActionPanel presentation="actions"`: consistent 44px,
left-aligned icon rows and no confirmation footer. The default `controls` presentation keeps
Done for filter/configuration sheets. Both retain the shared close button and swipe dismissal.

FacetSheet supplies DialogPortalContext to its contents, as DialogShell does. Nested popovers
and selects must portal into that modal subtree so editing their forms is not treated as an
outside interaction that dismisses the owning sheet.

CRM voice media uses the shared `VoicePlayer` widget for audio attachments: semantic muted surfaces, field radius, pill playback controls, a keyboard-accessible seek slider, elapsed time, playback speed, and optional download action. Native audio supplies playback underneath the themed controls.
