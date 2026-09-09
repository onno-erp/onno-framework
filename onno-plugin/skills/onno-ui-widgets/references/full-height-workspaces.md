# Full-height workspaces

Use this for an inbox, scheduler, or editor that should occupy the active tab while its content
panes scroll independently. Ordinary dashboard cards remain content-sized. Browser Fullscreen API
mode is a separate user request.

## Allocate height in the host

For an entity-backed list renderer, opt into the existing list surface:

```java
@Override
public void compose(PageBuilder page) {
    page.bare(); // Optional: omit the authored title when the list has its own toolbar.
    page.list(Conversation.class, view -> view.fill());
}
```

Declare `list.custom("conversationWorkspace").label("Inbox").defaultView()` in the entity's
`EntityView.list(ListSpec)` and register that key with `registerListRenderer`. `width("full")`
on a dashboard widget only sets width. `bare()` only hides the header. Neither grants full height.
For a non-list widget, inspect the host's allocation contract first; do not invent a widget
`fill()` API or add a fake entity merely to obtain height.

## Bound the renderer and its panes

The following is the layout skeleton; supply the real data, accessible controls, and actions.
Use SDK primitives for those controls and literal Tailwind classes so plugin CSS compiles them.

```tsx
import { registerListRenderer, type ListRendererProps } from "@onno/widget-sdk";

function ConversationWorkspace({ rows }: ListRendererProps) {
  return (
    <div data-testid="conversation-workspace"
      className="grid h-full min-h-0 min-w-0 w-full grid-cols-[minmax(0,1fr)] overflow-hidden overscroll-none md:grid-cols-[260px_minmax(0,1fr)]"
      style={{ height: "100%", minHeight: 0, maxHeight: "100%", contain: "layout size" }}>
      <aside className="hidden min-h-0 min-w-0 flex-col md:flex">
        <header className="shrink-0">Conversations</header>
        <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain">
          {rows.map(row => <div key={String(row.id)}>{String(row.description ?? "")}</div>)}
        </div>
      </aside>
      <section className="flex min-h-0 min-w-0 flex-col overflow-hidden">
        <header className="shrink-0">Selected conversation</header>
        <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain">
          {/* Messages, comments, and activity. */}
        </div>
        <footer className="shrink-0">{/* Reply composer. */}</footer>
      </section>
    </div>
  );
}

registerListRenderer("conversationWorkspace", ConversationWorkspace);
```

A nested list pane owns pagination too: consume `ListRendererProps.hasMore`, `loadingMore`,
`loadMoreFailed` and `loadMore()` from the host. Call `loadMore()` near the pane's bottom and
provide a visible keyboard-accessible load-more/retry button. Stop automatic retry after a failure;
do not depend on the outer list container scrolling or replace the host's scoped query with your own.

Every intermediate flex/grid pane must be able to shrink (`min-h-0`, and `min-w-0` horizontally).
Keep headers/composers in normal layout with `shrink-0`; only the content bodies scroll.
Containment assumes a host-allocated height; applying it to a content-sized card can collapse it.
Do not use `100vh`, a guessed `calc(100vh - ...px)`, or arbitrary height caps inside the widget.
`overscroll-contain` prevents scroll chaining; it does not fix an oversized ancestor.

## Verify the actual tab, not only the document

Compile the widget and ensure the running app serves the newly built JS/CSS. Restart the app if it
still serves old packaged resources. Inspect the actual computed layout after reload.

Use real long content and inspect desktop and a smaller supported viewport. Confirm the composer
and bottom gap stay visible. Scroll each intended pane to both ends, then continue scrolling:
headers and the outer page must stay stationary. Repeat after resizing or opening a split pane.

In the browser, start at the workspace root and walk every ancestor. For each with computed
`overflowY` of `auto` or `scroll`, record `clientHeight`, `scrollHeight`, and `scrollTop`. Outer
tab/page wrappers must have zero vertical scroll range (`scrollHeight - clientHeight`), allowing
at most subpixel rounding; inner content panes may overflow. Check horizontal overflow too.
A check of `document.documentElement` alone misses the shell's nested tab scroller.

If the card fits but the outer tab still moves slightly, inspect wrapper padding. The host fill
calculation must reserve trailing padding between the list root and its enclosing scroller. The
CRM incident left exactly 16px because the authored DivKit page added bottom padding after a list
that already reached the scroller bottom. Inspect the consuming framework version's
`EntityListWidget` sizing before assuming this correction exists. Correct or upgrade the host
when in scope; explain a dependency limitation otherwise. Do not hide the symptom with a global
`overflow:hidden` rule that clips content or breaks ordinary scrolling pages.

For a host calculation, use the scroller's content coordinates so measuring a scrolled page does
not enlarge the surface: available height is the scroller's client height minus the list's top
offset in scroll content minus trailing ancestor padding. Observe the actual allocation container
when split-pane resizing can change it without a window resize.
