# onno 4.1.0

Calendars that span several entities, a calendar widget rebuilt around scrolling and cards, HTML
email in the CRM inbox, and image albums. No breaking API changes; see
[Upgrade notes](#upgrade-notes) for the two behaviour changes.

## Calendar widget

- **Catalogs**: `.type("calendar").catalog(X.class)` now works like documents. Rows are windowed on
  `dateField` through the list feed's `ge`/`le` bounds, a drag writes only `dateField` /
  `endDateField`, and clicks open the record. Changes to the entity refresh the widget live.
- **Source-backed calendars**: `su.onno.ui.calendar.CalendarSource` beans that share a calendar name
  feed one widget (`.calendar(name)`): each source has a legend entry (also a show/hide toggle), its
  own read and reschedule permissions, per-item colour, subtitle, trailing meta, link, task state
  (done items struck through, open ones past their end flagged overdue), `editable(false)` and
  `resizable(false)`. `CalendarFacet` beans add filter pickers with an optional "Only me".
  `reschedule` receives the source-local id and a `CalendarMove(start, end, allDay)` and may refuse
  a move with `IllegalArgumentException`; the item snaps back with the message.
- New endpoints: `GET /api/calendars/{name}` (legend, facets, watched entities),
  `GET /api/calendars/{name}/items?from=&to=&facet=key:value` (inclusive days, at most 400) and
  `PUT /api/calendars/{name}/items/{id}` (`{start, end, allDay}`). Every call re-checks the source's
  `canRead` / `canReschedule`.
- **Views**: a year planner (twelve compact months with colour dots), a month grid that scrolls
  continuously through weeks, a week grid that scrolls sideways through days with pinned day
  headers, all-day row and hour column, and a day-grouped agenda (always on phones). The period
  title opens a month/year jump picker; buttons glide to the previous/next month or week.
- **Cards**: clicking an item opens a detail card beside it (full title, date, time and length,
  subtitle, meta, status, Open); clicking a busy day's "+N more" or a year-view day opens a day card
  in the same style. Short items clip to one line instead of overflowing their slot.
- `WidgetBuilder.fill()`: a widget with an internal scroller (currently `calendar`) takes the rest
  of the tab, so only the widget scrolls, never the page. Shares the list surface's measurement
  (`lib/fill-height.ts`).

## CRM inbox

- **HTML email**: Gmail keeps an email's HTML part, sanitized on import with a jsoup allowlist
  (layout tables, inline styles and images stay; scripts, handlers, forms, frames and unsafe URLs
  go), stored in the new `ConversationMessage.bodyHtml` beside the plain-text `body`. Inline `cid:`
  images point at the stored attachments. The inbox renders it in a script-less sandboxed frame with
  its own CSP, zoomed down to the bubble width when the email is laid out wider; long emails start
  collapsed. Once per mailbox the latest 50 inbox emails gain their HTML without re-downloading files.
- HTML-only mail gets a readable plain-text body, and inline signature images no longer append
  "[Message content unavailable]" to the text.
- **Albums**: several images on one message draw as one grid above the caption (Telegram-style
  layouts, "+N" after nine) and open a full-screen viewer with arrow keys, swipe and Escape.
- `MessageView` and the contact activity `Entry` carry `bodyHtml`.

## Lists

- A `widget("color")` column shows a colour swatch beside its `#RRGGBB` value.

## Upgrade notes

1. Bump `su.onno` to 4.1.0. The schema migrates itself: `ConversationMessage` gains a nullable
   `body_html` text column.
2. Remote images in HTML emails load by default, so senders' open-tracking pixels fire when an agent
   opens the conversation.
3. Apps that expose routes through the record-policy conformance test should classify
   `/api/calendars/**` if they copy its route table.
