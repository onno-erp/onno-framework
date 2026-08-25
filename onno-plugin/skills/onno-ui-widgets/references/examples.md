# Widget Examples

## Table Of Contents

- Built-In Widget Page
- Custom Widget Declaration
- Custom Widget TSX
- SSE Updates
- Packaging And Verification
- Troubleshooting

## Built-In Widget Page

```java
@Component
public class InventoryPage implements Page {
    @Override
    public String route() {
        return "/inventory";
    }

    @Override
    public void compose(PageBuilder b) {
        b.title("Inventory");

        b.widget("Time range").type("timeRange").width("full").order(-10)
                .config("presets", "24h,7d,30d,all")
                .config("default", "30d");

        b.widget("Open orders").type("count").width("1/3").order(0)
                .document(SalesOrder.class)
                .config("metric", "count")
                .config("filter", "status != 'CANCELLED'");

        b.widget("Stock by warehouse").type("chart").width("2/3").order(1)
                .register(StockRegister.class)
                .config("kind", "bar")
                .config("groupBy", "warehouse_display")
                .config("metric", "sum")
                .config("metricField", "quantity");

        b.widget("Recent receipts").type("list").width("full").order(2)
                .document(GoodsReceipt.class)
                .maxItems(20)
                .config("titleTemplate", "{number}")
                .config("secondaryField", "date");
    }
}
```

## Custom Widget Declaration

```java
b.widget("Recent activity").type("eventLog").width("full").order(20)
        .document(SalesOrder.class)
        .maxItems(10)
        .config("dateField", "date")
        .config("titleField", "number")
        .config("secondaryDisplay", "customerDisplay")
        .config("amountField", "total")
        .config("currency", "USD")
        .hint("Custom widget compiled from src/main/widgets/EventLog.tsx");
```

`type("eventLog")` must match the custom widget registration name expected by the widget bundle.

## Custom Widget TSX

```tsx
import {
  Badge,
  Card,
  api,
  registerWidget,
  useCallback,
  useEffect,
  useMemo,
  useState,
  useWidgetUpdates,
  type EntityRecord,
  type WidgetProps,
} from "@onno/widget-sdk";

function EventLog({ widget }: WidgetProps) {
  const [all, setAll] = useState<EntityRecord[]>([]);
  const cfg = widget.extraConfig ?? {};
  const dateField = cfg.dateField || "date";
  const titleField = cfg.titleField || "number";
  const max = widget.maxItems > 0 ? widget.maxItems : 10;

  const load = useCallback(async () => {
    if (widget.entityType !== "document") return;
    setAll(await api.listDocuments(widget.entityName));
  }, [widget.entityName, widget.entityType]);

  useEffect(() => { void load(); }, [load]);
  useWidgetUpdates(widget, load);

  const rows = useMemo(() => [...all]
    .sort((a, b) => String(b[dateField] ?? "").localeCompare(String(a[dateField] ?? "")))
    .slice(0, max), [all, dateField, max]);

  return (
    <Card className="p-3">
      <div className="flex items-center justify-between">
        <strong>{widget.title}</strong>
      </div>
      <div className="mt-3 space-y-2">
        {rows.map((row, index) => (
          <div key={String(row.id ?? index)} className="flex items-center gap-2">
            <Badge>{String(row[dateField] ?? "")}</Badge>
            <span>{String(row[titleField] ?? "")}</span>
          </div>
        ))}
      </div>
    </Card>
  );
}

registerWidget("eventLog", EventLog);
```

Prefer SDK controls (`Button`, `Badge`, `Select`, `DatePicker`, etc.) over hand-built lookalikes.

## SSE Updates

```tsx
useEffect(() => { void load(); }, [load]);
useWidgetUpdates(widget, load);
```

`useWidgetUpdates` reuses the host's one authenticated, reconnecting SSE connection across every
widget and browser tab. It matches the widget's bound catalog/document/register, coalesces bursts,
and unsubscribes on unmount. Never construct `EventSource` inside a widget. For a widget that
depends on several entities or non-entity events, use `events.subscribe(...)` or `useUiEvents(...)`.

## Packaging And Verification

Run `./gradlew compileWidgets processResources` (or the consuming module's qualified tasks), then
inspect the artifact for `onno-plugins/*.js` and its generated CSS. At runtime, authenticate and
check `/api/config` for `pluginScripts`/`pluginStyles`, fetch every bundle URL with the expected
content type, inspect the browser console, verify read-only RBAC data, and trigger a matching SSE
change. Add component tests plus descriptor/packaging tests. Keep Tailwind class names literal and
use semantic host tokens/radii.

## Troubleshooting

- If utilities do not style, keep class names literal in `src/main/widgets`; dynamic class strings are
  not scanned.
- If React duplicates appear, ensure the widget bundle aliases React to the host via the Gradle
  plugin instead of bundling another copy.
- If the widget is blank, verify the `type` string, browser console, and plugin bundle URL under
  `{onno.ui.path}/plugins/**`.
- If data is stale, ensure the stable loader used for the initial fetch is also passed to
  `useWidgetUpdates(widget, load)` and that the widget is bound to the entity it reads.
- Built-in `list` accepts catalogs/documents only. For register movements or low-resource counts,
  use a custom widget with `api.getMovements(...)` or `api.getBalance(...)`.
