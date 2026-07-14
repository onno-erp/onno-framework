import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import FullCalendar from "@fullcalendar/react";
import dayGridPlugin from "@fullcalendar/daygrid";
import timeGridPlugin from "@fullcalendar/timegrid";
import interactionPlugin from "@fullcalendar/interaction";
import ruLocale from "@fullcalendar/core/locales/ru";
import type {
  EventInput,
  EventContentArg,
  DatesSetArg,
  EventDropArg,
} from "@fullcalendar/core";
import type { EventResizeDoneArg } from "@fullcalendar/interaction";
import { useNavigate } from "react-router-dom";
import { format, endOfMonth, startOfMonth, addMonths, subMonths } from "date-fns";
import { ru } from "date-fns/locale";
import { ChevronLeft, ChevronRight, Lock } from "lucide-react";
import { toast } from "sonner";
import { api } from "@/lib/api";
import { toSnakeCase, cn } from "@/lib/utils";
import { formatAmount, resolveCurrency, toNumber } from "@/lib/format";
import type { DashboardWidgetMeta, EntityRecord } from "@/lib/types";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { HintIcon } from "@/components/ui/hint-icon";
import { Button } from "@/components/ui/button";
import { Segmented } from "@/components/ui/segmented";
import { useAppLocale, useMessages } from "@/providers/messages-provider";
import "./calendar-widget.css";

interface CalendarWidgetProps {
  widget: DashboardWidgetMeta;
}

interface EventExtendedProps {
  posted?: boolean;
  primary?: string;
  secondary?: string;
  amount?: number;
  currency?: string;
  hasEnd?: boolean;
  avatarUrl?: string;
  avatarLabel?: string;
}

// The 7-column month grid is unusable on a phone — each day is ~50px wide and
// every booking renders as a full bar, so the widget falls back to a tap-to-open
// agenda list below this width. Matches the app's server-side MOBILE_MAX so the
// agenda shows exactly where the mobile dashboard does.
const MOBILE_MAX = 600;

function useIsMobile(maxWidth = MOBILE_MAX): boolean {
  const [isMobile, setIsMobile] = useState(
    () => typeof window !== "undefined" && window.matchMedia(`(max-width: ${maxWidth}px)`).matches
  );
  useEffect(() => {
    const mql = window.matchMedia(`(max-width: ${maxWidth}px)`);
    const onChange = () => setIsMobile(mql.matches);
    onChange();
    mql.addEventListener("change", onChange);
    return () => mql.removeEventListener("change", onChange);
  }, [maxWidth]);
  return isMobile;
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
  const locale = useAppLocale();
  const t = useMessages();
  const calendarRef = useRef<FullCalendar | null>(null);
  const isMobile = useIsMobile();
  const [items, setItems] = useState<EntityRecord[]>([]);
  const [title, setTitle] = useState("");
  const [view, setView] = useState<"dayGridMonth" | "timeGridWeek">("dayGridMonth");
  // On mobile the agenda owns navigation (FullCalendar isn't mounted to emit
  // datesSet), so the focused month is tracked here and drives the fetch range.
  const [anchor, setAnchor] = useState(() => new Date());

  const readOnly =
    String(widget.extraConfig?.readOnly ?? "").toLowerCase() === "true";
  // Authored readOnly wins; RBAC write access (server-stamped canWrite) also locks rescheduling.
  const editable = !readOnly && widget.entityType === "document" && widget.canWrite !== false;
  const [range, setRange] = useState(() => {
    const now = new Date();
    return {
      from: format(startOfMonth(now), "yyyy-MM-dd'T'00:00:00"),
      to: format(endOfMonth(now), "yyyy-MM-dd'T'23:59:59"),
    };
  });

  // Drive the range + title from the anchor month while in the mobile agenda.
  useEffect(() => {
    if (!isMobile) return;
    setTitle(format(anchor, "MMMM yyyy", { locale: locale?.startsWith("ru") ? ru : undefined }));
    setRange({
      from: format(startOfMonth(anchor), "yyyy-MM-dd'T'00:00:00"),
      to: format(endOfMonth(anchor), "yyyy-MM-dd'T'23:59:59"),
    });
  }, [isMobile, anchor, locale]);

  useEffect(() => {
    const name = toSnakeCase(widget.entityName);
    // config("filter", …) scopes the calendar server-side (e.g. drop DRAFT/CANCELED bookings).
    const filter = widget.extraConfig?.filter || undefined;
    if (widget.entityType === "document") {
      api.listDocuments(name, range.from, range.to, filter).then(setItems);
    }
  }, [widget, range]);

  const dateField = widget.dateField || "_date";
  const titleField = widget.titleField || "_number";
  const endDateField = widget.extraConfig?.endDateField; // e.g. "_end_date" or "checkout"
  const durationField = widget.extraConfig?.durationField; // e.g. "duration_days"
  const colorByField = widget.extraConfig?.colorBy;
  const amountField = widget.extraConfig?.amountField || "total";
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
        const amount = toNumber(item[amountField]) ?? undefined;
        const currency = resolveCurrency(item, widget.extraConfig?.currencyField, widget.extraConfig?.currency);
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
            currency,
            hasEnd: Boolean(endValue),
            avatarUrl: avatar.url,
            avatarLabel: avatar.label,
          } satisfies EventExtendedProps,
          classNames: hue != null ? [] : item._posted ? ["fc-onno-posted"] : ["fc-onno-draft"],
        }];
      }),
    [items, dateField, titleField, secondaryFieldList, endDateField, durationField, allDay, colorByField, amountField]
  );

  // Events grouped by start day, ascending — the source for the mobile agenda.
  const agendaDays = useMemo(() => {
    const groups = new Map<string, EventInput[]>();
    for (const ev of events) {
      if (!ev.start) continue;
      const key = format(new Date(ev.start as string), "yyyy-MM-dd");
      const bucket = groups.get(key);
      if (bucket) bucket.push(ev);
      else groups.set(key, [ev]);
    }
    return [...groups.entries()]
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([key, evs]) => ({ key, date: new Date(`${key}T00:00:00`), events: evs }));
  }, [events]);

  const fmtAmount = (ext: EventExtendedProps) =>
    formatAmount(ext.amount as number, {
      currency: ext.currency,
      unit: widget.extraConfig?.unit,
      unitPosition: widget.extraConfig?.unitPosition,
      format: widget.extraConfig?.format,
      locale: widget.extraConfig?.locale,
    });

  const openEvent = (id: string | undefined) => {
    if (widget.entityType === "document" && id) {
      navigate(`/documents/${toSnakeCase(widget.entityName)}/${id}`);
    }
  };

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
          <span className="text-[10px] tabular-nums opacity-70">{fmtAmount(ext)}</span>
        )}
      </div>
    );
  };

  // Tap-to-open agenda for phones: a day-grouped list instead of the dense grid.
  const renderAgenda = () => {
    if (agendaDays.length === 0) {
      return (
        <div className="onno-agenda-empty">
          {t("calendar.emptyThisMonth", { name: widget.entityName.toLowerCase() })}
        </div>
      );
    }
    const today = format(new Date(), "yyyy-MM-dd");
    return (
      <div className="onno-calendar-agenda">
        {agendaDays.map((day) => (
          <div key={day.key} className="onno-agenda-day">
            <div className={cn("onno-agenda-date", day.key === today && "is-today")}>
              <span className="onno-agenda-dow">
                {format(day.date, "EEE", { locale: locale?.startsWith("ru") ? ru : undefined })}
              </span>
              <span className="onno-agenda-dom">{format(day.date, "d")}</span>
            </div>
            <div className="onno-agenda-events">
              {day.events.map((ev) => {
                const ext = ev.extendedProps as EventExtendedProps;
                const line1 = ext.secondary || ext.primary;
                const line2 = ext.secondary ? ext.primary : undefined;
                return (
                  <button
                    key={String(ev.id)}
                    type="button"
                    className={cn("onno-agenda-event", ext.posted ? "is-posted" : "is-draft")}
                    onClick={() => openEvent(ev.id ? String(ev.id) : undefined)}
                  >
                    <span className="onno-agenda-bar" aria-hidden />
                    {ext.avatarUrl ? (
                      <img
                        src={ext.avatarUrl}
                        alt=""
                        className="onno-agenda-avatar"
                      />
                    ) : null}
                    <span className="onno-agenda-text">
                      <span className="onno-agenda-primary">{line1}</span>
                      {line2 && <span className="onno-agenda-secondary">{line2}</span>}
                    </span>
                    {typeof ext.amount === "number" && (
                      <span className="onno-agenda-amount">{fmtAmount(ext)}</span>
                    )}
                  </button>
                );
              })}
            </div>
          </div>
        ))}
      </div>
    );
  };

  const goPrev = () =>
    isMobile ? setAnchor((a) => subMonths(a, 1)) : calendarRef.current?.getApi().prev();
  const goNext = () =>
    isMobile ? setAnchor((a) => addMonths(a, 1)) : calendarRef.current?.getApi().next();
  const goToday = () =>
    isMobile ? setAnchor(new Date()) : calendarRef.current?.getApi().today();
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
    <Card className="onno-calendar-widget">
      <CardHeader className="flex flex-row items-center justify-between gap-3 pb-3">
        <div className="min-w-0">
          <div className="flex items-center gap-1.5">
            <CardTitle className="text-[13px] font-medium">{widget.title}</CardTitle>
            <HintIcon text={widget.hint} size={13} />
            {readOnly && (
              <span
                className="inline-flex items-center gap-0.5 rounded-control bg-muted px-1.5 py-0.5 text-[10px] font-medium text-muted-foreground"
                aria-label="Read only"
              >
                <Lock className="h-2.5 w-2.5" /> {t("calendar.readOnly")}
              </span>
            )}
          </div>
          <p className="mt-0.5 truncate text-[11px] text-muted-foreground">{title}</p>
        </div>
        <div className="flex items-center gap-2">
          {/* The month/week grid toggle is grid-only; the mobile agenda hides it. */}
          {!isMobile && (
            <Segmented
              size="sm"
              value={view}
              onChange={switchView}
              options={[
                { value: "dayGridMonth", label: t("calendar.month") },
                { value: "timeGridWeek", label: t("calendar.week") },
              ]}
            />
          )}
          <Button variant="outline" size="sm" className="h-7 px-2" onClick={goToday}>
            {t("calendar.today")}
          </Button>
          <div className="flex">
            <Button
              variant="ghost"
              size="icon"
              className="h-7 w-7"
              onClick={goPrev}
              aria-label={t("calendar.previous")}
            >
              <ChevronLeft className="h-3.5 w-3.5" />
            </Button>
            <Button
              variant="ghost"
              size="icon"
              className="h-7 w-7"
              onClick={goNext}
              aria-label={t("calendar.next")}
            >
              <ChevronRight className="h-3.5 w-3.5" />
            </Button>
          </div>
        </div>
      </CardHeader>
      <CardContent className={cn(isMobile ? "px-0 pb-1" : "px-3 pb-3")}>
        {isMobile ? (
          renderAgenda()
        ) : (
          <FullCalendar
            ref={calendarRef}
            plugins={[dayGridPlugin, timeGridPlugin, interactionPlugin]}
            initialView="dayGridMonth"
            headerToolbar={false}
            height="auto"
            firstDay={1}
            locale={locale?.startsWith("ru") ? ruLocale : undefined}
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
        )}
      </CardContent>
    </Card>
  );
}
