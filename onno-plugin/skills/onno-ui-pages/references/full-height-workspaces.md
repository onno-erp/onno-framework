# Full-height workspaces

Use this for an inbox, scheduler, or editor that should occupy the active tab while its content
panes scroll independently. Ordinary page widgets remain content-sized. Browser Fullscreen API
mode is a separate user request.

## Allocate height in the host

For an entity-backed workspace, render the entity's list with `fill` and give the list a custom
view that is the workspace:

```tsx
// pages/InboxPage.tsx — registered as { path: "/inbox", component: InboxPage, label: "Inbox", icon: Inbox }
import { EntityList, Page } from "@onno/ui";

export function InboxPage() {
  return (
    <Page>
      <EntityList entity="Tickets" fill custom={{ component: TicketWorkspace, label: "Inbox", defaultView: true }} />
    </Page>
  );
}
```



`Page` without a `title` already drops the header. `fill` is what allocates height; a `Row` or a
card does not. A `fill` list rendered straight as the route's content (no `Page` around it, as
`<CrmInbox>` usually is) takes the same gutter as a default list page itself; inside a `Page` it
leaves the gutter to the page, so the two never double up. For a non-list surface, `Calendar` also takes `fill`; otherwise inspect the host's
allocation contract first — do not add a fake entity merely to obtain height.

## Bound the renderer and its panes

The following is the layout skeleton; supply the real data, accessible controls, and actions.
Use `@onno/ui` controls and literal Tailwind classes (the build runs Tailwind over `src/main/ui`).

```tsx
import type { ListRendererComponent, ListRendererProps } from "@onno/ui";

function Workspace({ rows }: ListRendererProps) {
  return (
    <div data-testid="ticket-workspace"
      className="grid h-full min-h-0 min-w-0 w-full grid-cols-[minmax(0,1fr)] overflow-hidden overscroll-none md:grid-cols-[260px_minmax(0,1fr)]"
      style={{ height: "100%", minHeight: 0, maxHeight: "100%", contain: "layout size" }}>
      <aside className="hidden min-h-0 min-w-0 flex-col md:flex">
        <header className="shrink-0">Tickets</header>
        <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain">
          {rows.map((row) => <div key={String(row.id)}>{String(row.description ?? "")}</div>)}
        </div>
      </aside>
      <section className="flex min-h-0 min-w-0 flex-col overflow-hidden">
        <header className="shrink-0">Selected ticket</header>
        <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain">
          {/* Messages, comments, and activity. */}
        </div>
        <footer className="shrink-0">{/* Reply composer. */}</footer>
      </section>
    </div>
  );
}

/** The pane shows its own loading state, so the host's "Loading more…" line is suppressed. */
export const TicketWorkspace: ListRendererComponent = Object.assign(Workspace, { ownsLoadingMore: true });
```

A nested list pane owns pagination too: consume `ListRendererProps.hasMore`, `loadingMore`,
`loadMoreFailed` and `loadMore()` from the host. Call `loadMore()` near the pane's bottom and
provide a visible keyboard-accessible load-more/retry button. Stop automatic retry after a failure;
do not depend on the outer list container scrolling or replace the host's scoped query with your own.
To render the list's own toolbar inside the workspace, compose `EntityListView` with
`toolbarPlacement="renderer"` and render `props.toolbar` once inside the workspace (including on
empty results); the host keeps the query and pagination.

Every intermediate flex/grid pane must be able to shrink (`min-h-0`, and `min-w-0` horizontally).
Keep headers/composers in normal layout with `shrink-0`; only the content bodies scroll.
Containment assumes a host-allocated height; applying it to a content-sized card can collapse it.
Do not use `100vh`, a guessed `calc(100vh - ...px)`, or arbitrary height caps inside the component.
`overscroll-contain` prevents scroll chaining; it does not fix an oversized ancestor.

Reuse avatar fields, resolved reference `…Avatar` companions and comment author avatars before
inventing one. A communication timeline merges messages, comments and durable domain events
chronologically, visually distinct but in one flow.

## Verify the actual tab, not only the document

Run the UI with `npm run dev` (or rebuild the jar and restart, so the app serves the new build).
Inspect the actual computed layout after reload.

Use real long content and inspect desktop and a smaller supported viewport. Confirm the composer
and bottom gap stay visible. Scroll each intended pane to both ends, then continue scrolling:
headers and the outer page must stay stationary. Repeat after resizing or opening a split pane.

In the browser, start at the workspace root and walk every ancestor. For each with computed
`overflowY` of `auto` or `scroll`, record `clientHeight`, `scrollHeight`, and `scrollTop`. Outer
tab/page wrappers must have zero vertical scroll range (`scrollHeight - clientHeight`), allowing
at most subpixel rounding; inner content panes may overflow. Check horizontal overflow too.
A check of `document.documentElement` alone misses the shell's nested tab scroller.

If the card fits but the outer tab still moves slightly, inspect wrapper padding: the host's fill
calculation must reserve trailing padding between the list root and its enclosing scroller (an
authored `Page` adds bottom padding after a list that already reaches the scroller bottom). Fix
`EntityListView` sizing in `@onno/ui` when in scope; explain a dependency limitation otherwise. Do
not hide the symptom with a global `overflow:hidden` rule that clips content or breaks ordinary
scrolling pages.

For a host calculation, use the scroller's content coordinates so measuring a scrolled page does
not enlarge the surface: available height is the scroller's client height minus the list's top
offset in scroll content minus trailing ancestor padding. Observe the actual allocation container
when split-pane resizing can change it without a window resize.
