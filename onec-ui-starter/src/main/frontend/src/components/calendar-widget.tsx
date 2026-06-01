import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import FullCalendar from "@fullcalendar/react";
import dayGridPlugin from "@fullcalendar/daygrid";
import timeGridPlugin from "@fullcalendar/timegrid";
import interactionPlugin from "@fullcalendar/interaction";
import type {
  EventInput,
  EventContentArg,
  DatesSetArg,
  EventDropArg,
} from "@fullcalendar/core";
import type { EventResizeDoneArg } from "@fullcalendar/interaction";
import { useNavigate } from "react-router-dom";
import { format, endOfMonth, startOfMonth } from "date-fns";
import { ChevronLeft, ChevronRight, Lock } from "lucide-react";
import { toast } from "sonner";
import { api } from "@/lib/api";
import { toSnakeCase, cn } from "@/lib/utils";
import type { DashboardWidgetMeta, EntityRecord } from "@/lib/types";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import "./calendar-widget.css";

interface CalendarWidgetProps {
  widget: DashboardWidgetMeta;
}

interface EventExtendedProps {
  posted?: boolean;
  primary?: string;
  secondary?: string;
  amount?: number;
  hasEnd?: boolean;
  avatarUrl?: string;
  avatarLabel?: string;
}

function hashHue(input: string | undefined | null): number | null {
  if (!input) return null;
  let h = 0;
  for (let i = 0; i < input.length; i++) {
    h = (h * 31 + input.charCodeAt(i)) >>> 0;
  }
  // Stride the hue circle so consecutive values look distinct.
  return (h * 47) % 360;
}

function pickAvatar(row: EntityRecord): { url?: string; label?: string } {
  for (const key of Object.keys(row)) {
    if (key.endsWith("_avatar") && typeof row[key] === "string" && (row[key] as string).trim()) {
      const base = key.slice(0, -"_avatar".length);
      const label = row[`${base}_display`];
      return {
        url: row[key] as string,
        label: typeof label === "string" ? label : undefined,
      };
    }
  }
  return {};
}

function pickField(row: EntityRecord, fields: string[]): string | undefined {
  for (const f of fields) {
    const v = row[f];
    if (typeof v === "string" && v.trim()) return v;
    if (typeof v === "number") return String(v);
  }
  return undefined;
}

export function CalendarWidget({ widget }: CalendarWidgetProps) {
  const navigate = useNavigate();
  const calendarRef = useRef<FullCalendar | null>(null);
  const [items, setItems] = useState<EntityRecord[]>([]);
  const [title, setTitle] = useState("");
  const [view, setView] = useState<"dayGridMonth" | "timeGridWeek">("dayGridMonth");

  const readOnly =
    String(widget.extraConfig?.readOnly ?? "").toLowerCase() === "true";
  const editable = !readOnly && widget.entityType === "document";
  const [range, setRange] = useState(() => {
    const now = new Date();
    return {
      from: format(startOfMonth(now), "yyyy-MM-dd'T'00:00:00"),
      to: format(endOfMonth(now), "yyyy-MM-dd'T'23:59:59"),
    };
  });

  useEffect(() => {
    const name = toSnakeCase(widget.entityName);
    if (widget.entityType === "document") {
      api.listDocuments(name, range.from, range.to).then(setItems);
    }
  }, [widget, range]);

  const dateField = widget.dateField || "_date";
  const titleField = widget.titleField || "_number";
  const endDateField = widget.extraConfig?.endDateField; // e.g. "_end_date" or "checkout"
  const durationField = widget.extraConfig?.durationField; // e.g. "duration_days"
  const colorByField = widget.extraConfig?.colorBy;
  const allDayCfg = widget.extraConfig?.allDay;
  // Booking-style widgets (those with an end date or duration) default to all-day so
  // events render as continuous bars and can be resized day-by-day in month view.
  const isBookingLike = Boolean(endDateField || durationField);
  const allDay =
    allDayCfg != null
      ? String(allDayCfg).toLowerCase() === "true"
      : isBookingLike;
  const secondaryFieldList = useMemo(() => {
    const cfg = widget.extraConfig?.secondaryField;
    if (cfg) return cfg.split(",").map((s) => s.trim()).filter(Boolean);
    // sensible defaults: client/property/warehouse display names
    return [
      "customer_display",
      "client_display",
      "property_display",
      "warehouse_display",
      "_description",
      "name",
    ];
  }, [widget]);

  function computeEnd(item: EntityRecord, startIso: string): string | undefined {
    if (endDateField) {
      const v = item[endDateField];
      if (typeof v === "string" && v) return v;
    }
    if (durationField) {
      const raw = item[durationField];
      const days = typeof raw === "number" ? raw : parseFloat(String(raw ?? ""));
      if (Number.isFinite(days) && days > 0) {
        const d = new Date(startIso);
        d.setDate(d.getDate() + Math.round(days));
        return d.toISOString();
      }
    }
    return undefined;
  }

  const events: EventInput[] = useMemo(
    () =>
      items.flatMap<EventInput>((item) => {
        const dateValue = item[dateField] as string | undefined;
        if (!dateValue) return [];
        const id = String(item._id ?? "");
        const primary = String(item[titleField] ?? item._number ?? "");
        const secondary = pickField(item, secondaryFieldList);
        const amount = typeof item.total === "number" ? (item.total as number) : undefined;
        const endValue = computeEnd(item, dateValue);
        const avatar = pickAvatar(item);
        const colorKey = colorByField ? (item[colorByField] as string | undefined) : undefined;
        const hue = hashHue(colorKey);
        const styled = hue != null
          ? {
              backgroundColor: `hsl(${hue} 70% ${item._posted ? "45%" : "92%"})`,
              borderColor: `hsl(${hue} 70% ${item._posted ? "35%" : "70%"})`,
              textColor: item._posted ? "white" : `hsl(${hue} 50% 25%)`,
            }
          : {};
        return [{
          id,
          title: primary,
          start: dateValue,
          end: endValue,
          allDay,
          ...styled,
          extendedProps: {
            posted: Boolean(item._posted),
            primary,
            secondary,
            amount,
            hasEnd: Boolean(endValue),
            avatarUrl: avatar.url,
            avatarLabel: avatar.label,
          } satisfies EventExtendedProps,
          classNames: hue != null ? [] : item._posted ? ["fc-onec-posted"] : ["fc-onec-draft"],
        }];
      }),
    [items, dateField, titleField, secondaryFieldList, endDateField, durationField, allDay, colorByField]
  );

  const renderEvent = (arg: EventContentArg): { domNodes?: Node[]; html?: string } | ReactNode => {
    const ext = arg.event.extendedProps as EventExtendedProps;
    const isMonth = arg.view.type === "dayGridMonth";
    const avatar = ext.avatarUrl ? (
      <img
        src={ext.avatarUrl}
        alt={ext.avatarLabel ?? ""}
        title={ext.avatarLabel ?? ""}
        className="h-4 w-4 shrink-0 rounded-full ring-1 ring-white/40 object-cover"
      />
    ) : null;
    if (isMonth) {
      return (
        <div className="flex w-full min-w-0 items-center gap-1.5 leading-tight">
          {avatar}
          <div className="flex min-w-0 flex-1 flex-col">
            <span className="truncate font-medium">{ext.secondary || ext.primary}</span>
            <span className="truncate text-[9px] tabular-nums opacity-70">
              {arg.timeText && `${arg.timeText} · `}
              {ext.primary}
            </span>
          </div>
        </div>
      );
    }
    return (
      <div className="flex w-full min-w-0 flex-col gap-0.5 leading-tight">
        <span className="text-[10px] tabular-nums opacity-80">{arg.timeText}</span>
        <div className="flex items-center gap-1.5">
          {avatar}
          <span className="truncate font-medium">{ext.primary}</span>
        </div>
        {ext.secondary && (
          <span className="truncate text-[10px] opacity-80">{ext.secondary}</span>
        )}
        {typeof ext.amount === "number" && (
          <span className="text-[10px] tabular-nums opacity-70">
            ${ext.amount.toFixed(2)}
          </span>
        )}
      </div>
    );
  };

  const goPrev = () => calendarRef.current?.getApi().prev();
  const goNext = () => calendarRef.current?.getApi().next();
  const goToday = () => calendarRef.current?.getApi().today();
  const switchView = (v: typeof view) => {
    setView(v);
    calendarRef.current?.getApi().changeView(v);
  };

  const handleDatesSet = (arg: DatesSetArg) => {
    setTitle(arg.view.title);
    setRange({
      from: format(arg.start, "yyyy-MM-dd'T'00:00:00"),
      to: format(arg.end, "yyyy-MM-dd'T'23:59:59"),
    });
  };

  const persistEvent = async (
    id: string,
    nextStart: Date | null,
    nextEnd: Date | null,
    revert: () => void
  ) => {
    if (!nextStart || !id) {
      revert();
      return;
    }
    const startIso = nextStart.toISOString();
    const endIso = nextEnd ? nextEnd.toISOString() : undefined;
    const name = toSnakeCase(widget.entityName);
    setItems((prev) =>
      prev.map((r) => {
        if (r._id !== id) return r;
        const updated: EntityRecord = { ...r, _date: startIso };
        if (endDateField && endIso) updated[endDateField] = endIso;
        return updated;
      })
    );
    try {
      const payload: EntityRecord = { date: startIso };
      if (endDateField && endIso) {
        // Strip leading underscore convention when writing back.
        const writeKey = endDateField.replace(/^_/, "");
        payload[writeKey] = endIso;
      }
      await api.updateDocument(name, id, payload);
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "Move failed");
      revert();
      api.listDocuments(name, range.from, range.to).then(setItems);
    }
  };

  const handleEventDrop = (arg: EventDropArg) => {
    persistEvent(arg.event.id, arg.event.start, arg.event.end, arg.revert);
  };

  const handleEventResize = (arg: EventResizeDoneArg) => {
    persistEvent(arg.event.id, arg.event.start, arg.event.end, arg.revert);
  };

  return (
    <Card className="onec-calendar-widget">
      <CardHeader className="flex flex-row items-center justify-between gap-3 pb-3">
        <div className="min-w-0">
          <div className="flex items-center gap-1.5">
            <CardTitle className="text-[13px] font-medium">{widget.title}</CardTitle>
            {readOnly && (
              <span
                className="inline-flex items-center gap-0.5 rounded-sm bg-muted px-1.5 py-0.5 text-[10px] font-medium text-muted-foreground"
                aria-label="Read only"
              >
                <Lock className="h-2.5 w-2.5" /> read only
              </span>
            )}
          </div>
          <p className="mt-0.5 truncate text-[11px] text-muted-foreground">{title}</p>
        </div>
        <div className="flex items-center gap-2">
          <div className="flex rounded-md border border-border p-0.5">
            <button
              onClick={() => switchView("dayGridMonth")}
              className={cn(
                "rounded-sm px-2 py-0.5 text-[11px] font-medium transition-colors",
                view === "dayGridMonth"
                  ? "bg-accent text-foreground"
                  : "text-muted-foreground hover:text-foreground"
              )}
            >
              Month
            </button>
            <button
              onClick={() => switchView("timeGridWeek")}
              className={cn(
                "rounded-sm px-2 py-0.5 text-[11px] font-medium transition-colors",
                view === "timeGridWeek"
                  ? "bg-accent text-foreground"
                  : "text-muted-foreground hover:text-foreground"
              )}
            >
              Week
            </button>
          </div>
          <Button variant="outline" size="sm" className="h-7 px-2" onClick={goToday}>
            Today
          </Button>
          <div className="flex">
            <Button
              variant="ghost"
              size="icon"
              className="h-7 w-7"
              onClick={goPrev}
              aria-label="Previous"
            >
              <ChevronLeft className="h-3.5 w-3.5" />
            </Button>
            <Button
              variant="ghost"
              size="icon"
              className="h-7 w-7"
              onClick={goNext}
              aria-label="Next"
            >
              <ChevronRight className="h-3.5 w-3.5" />
            </Button>
          </div>
        </div>
      </CardHeader>
      <CardContent className="px-3 pb-3">
        <FullCalendar
          ref={calendarRef}
          plugins={[dayGridPlugin, timeGridPlugin, interactionPlugin]}
          initialView="dayGridMonth"
          headerToolbar={false}
          height="auto"
          firstDay={1}
          events={events}
          editable={editable}
          eventStartEditable={editable}
          eventDurationEditable={editable}
          eventResizableFromStart={editable}
          datesSet={handleDatesSet}
          eventTimeFormat={{ hour: "2-digit", minute: "2-digit", hour12: false }}
          eventContent={renderEvent}
          eventDrop={handleEventDrop}
          eventResize={handleEventResize}
          eventClick={(info) => {
            if (widget.entityType === "document" && info.event.id) {
              navigate(`/documents/${toSnakeCase(widget.entityName)}/${info.event.id}`);
            }
          }}
          dayMaxEventRows={false}
        />
      </CardContent>
    </Card>
  );
}
