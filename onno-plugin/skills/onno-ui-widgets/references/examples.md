# Widget Examples

## Table Of Contents

- Built-In Widget Page
- Custom Widget Declaration
- Custom Widget TSX
- SSE Updates
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

        b.widget("Low stock").type("count").width("1/3").order(0)
                .register(StockRegister.class)
                .config("metric", "count")
                .config("filter", "quantity < 5");

        b.widget("Stock by warehouse").type("chart").width("2/3").order(1)
                .register(StockRegister.class)
                .config("kind", "bar")
                .config("groupBy", "warehouse_display")
                .config("metric", "sum")
                .config("metricField", "quantity");

        b.widget("Recent movements").type("list").width("full").order(2)
                .register(StockRegister.class)
                .maxItems(20)
                .config("titleTemplate", "{product_display}")
                .config("secondaryField", "warehouse_display");
    }
}
```

## Custom Widget Declaration

```java
b.widget("Recent activity").type("eventLog").width("full").order(20)
        .document(SalesOrder.class)
        .maxItems(10)
        .config("dateField", "_date")
        .config("titleField", "_number")
        .config("secondaryDisplay", "customer_display")
        .config("amountField", "total")
        .config("currency", "USD")
        .hint("Custom widget compiled from src/main/widgets/EventLog.tsx");
```

`type("eventLog")` must match the custom widget registration name expected by the widget bundle.

## Custom Widget TSX

```tsx
import {
  Badge,
  Button,
  Card,
  useOnnoWidget,
  type OnnoWidgetProps,
} from "@onno/widget-sdk";
import { useEffect, useState } from "react";

type Row = Record<string, unknown>;

export default function EventLog(props: OnnoWidgetProps) {
  const widget = useOnnoWidget(props);
  const [rows, setRows] = useState<Row[]>([]);
  const dateField = String(widget.config.dateField ?? "_date");
  const titleField = String(widget.config.titleField ?? "_number");

  async function load() {
    if (!widget.entity) return;
    const result = await widget.api.list(widget.entity.kind, widget.entity.name, {
      limit: widget.maxItems ?? 10,
      sort: `${dateField}:desc`,
    });
    setRows(result.rows);
  }

  useEffect(() => {
    void load();
  }, [widget.entity?.name, widget.maxItems]);

  return (
    <Card className="p-3">
      <div className="flex items-center justify-between">
        <strong>{widget.title}</strong>
        <Button size="sm" variant="ghost" onClick={() => void load()}>
          Refresh
        </Button>
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
```

Prefer SDK controls (`Button`, `Badge`, `Select`, `DatePicker`, etc.) over hand-built lookalikes.

## SSE Updates

```tsx
useEffect(() => {
  const events = new EventSource("/api/events");
  const reload = () => void load();
  events.addEventListener("created", reload);
  events.addEventListener("updated", reload);
  events.addEventListener("deleted", reload);
  events.addEventListener("posted", reload);
  events.addEventListener("unposted", reload);
  return () => events.close();
}, []);
```

Events are named. `events.onmessage` will not fire for these updates.

## Troubleshooting

- If utilities do not style, keep class names literal in `src/main/widgets`; dynamic class strings are
  not scanned.
- If React duplicates appear, ensure the widget bundle aliases React to the host via the Gradle
  plugin instead of bundling another copy.
- If the widget is blank, verify the `type` string, browser console, and plugin bundle URL under
  `{onno.ui.path}/plugins/**`.
- If data is stale, subscribe to named SSE events or provide a refresh button.
