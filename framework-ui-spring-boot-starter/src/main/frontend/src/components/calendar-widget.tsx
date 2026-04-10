import { useEffect, useState } from "react";
import { format, startOfMonth, endOfMonth, isSameDay } from "date-fns";
import { api } from "@/lib/api";
import { toSnakeCase } from "@/lib/utils";
import type { DashboardWidgetMeta, EntityRecord } from "@/lib/types";
import { Calendar } from "@/components/ui/calendar";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";

interface CalendarWidgetProps {
  widget: DashboardWidgetMeta;
}

export function CalendarWidget({ widget }: CalendarWidgetProps) {
  const [month, setMonth] = useState(new Date());
  const [items, setItems] = useState<EntityRecord[]>([]);
  const [selectedDay, setSelectedDay] = useState<Date | undefined>();

  useEffect(() => {
    const from = format(startOfMonth(month), "yyyy-MM-dd'T'00:00:00");
    const to = format(endOfMonth(month), "yyyy-MM-dd'T'23:59:59");
    const name = toSnakeCase(widget.entityName);

    if (widget.entityType === "document") {
      api.listDocuments(name, from, to).then(setItems);
    }
  }, [month, widget]);

  const dateField = widget.dateField || "_date";
  const titleField = widget.titleField || "_number";

  const eventDates = new Set(
    items
      .map((item) => {
        const val = item[dateField] as string;
        return val ? format(new Date(val), "yyyy-MM-dd") : null;
      })
      .filter(Boolean)
  );

  const dayEvents = selectedDay
    ? items.filter((item) => {
        const val = item[dateField] as string;
        return val && isSameDay(new Date(val), selectedDay);
      })
    : [];

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-sm font-medium">{widget.title}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3 px-2 pb-4">
        <Calendar
          mode="single"
          selected={selectedDay}
          onSelect={setSelectedDay}
          month={month}
          onMonthChange={setMonth}
          modifiers={{
            hasEvent: (day) => eventDates.has(format(day, "yyyy-MM-dd")),
          }}
          modifiersClassNames={{
            hasEvent: "bg-primary/10 text-primary font-semibold rounded-md",
          }}
        />
        {selectedDay && (
          <div className="space-y-1">
            <p className="text-xs font-medium text-muted-foreground">
              {format(selectedDay, "PPP")} — {dayEvents.length} event{dayEvents.length !== 1 ? "s" : ""}
            </p>
            {dayEvents.map((ev, i) => (
              <div
                key={i}
                className="flex items-center justify-between rounded-md border px-3 py-1.5 text-sm"
              >
                <span>{String(ev[titleField] ?? ev._number ?? "")}</span>
                {ev._posted !== undefined && (
                  <Badge variant={ev._posted ? "default" : "secondary"} className="text-xs">
                    {ev._posted ? "Posted" : "Draft"}
                  </Badge>
                )}
              </div>
            ))}
            {dayEvents.length === 0 && (
              <p className="text-xs text-muted-foreground">No events</p>
            )}
          </div>
        )}
      </CardContent>
    </Card>
  );
}
