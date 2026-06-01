import { useEffect, useState } from "react";
import { BookOpen, FileText, BarChart3 } from "lucide-react";
import { api } from "@/lib/api";
import { toSnakeCase } from "@/lib/utils";
import type { DashboardWidgetMeta, EntityRecord } from "@/lib/types";
import { CalendarWidget } from "@/components/calendar-widget";
import { KanbanWidget } from "@/components/kanban-widget";
import { ChartWidget } from "@/components/chart-widget";
import { useWidgetRegistry } from "@/providers/widget-registry";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import {
  Table,
  TableHeader,
  TableBody,
  TableRow,
  TableHead,
  TableCell,
} from "@/components/ui/table";

function widthToColSpan(width: string): string {
  switch (width) {
    case "full": return "md:col-span-3";
    case "1/2": return "md:col-span-2";
    default: return "";
  }
}

function CountWidget({ widget }: { widget: DashboardWidgetMeta }) {
  const [count, setCount] = useState(0);
  const icon = widget.entityType === "catalog" ? BookOpen
    : widget.entityType === "document" ? FileText
    : BarChart3;
  const Icon = icon;

  useEffect(() => {
    const name = toSnakeCase(widget.entityName);
    if (widget.entityType === "catalog") {
      api.listCatalog(name).then((items) => setCount(items.length));
    } else if (widget.entityType === "document") {
      api.listDocuments(name).then((items) => setCount(items.length));
    }
  }, [widget]);

  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between pb-2">
        <CardTitle className="text-[13px] font-medium text-muted-foreground">{widget.title}</CardTitle>
        <Icon className="h-4 w-4 text-muted-foreground/50" />
      </CardHeader>
      <CardContent>
        <div className="text-2xl font-semibold tracking-tight">{count}</div>
        <p className="text-xs text-muted-foreground mt-1">total records</p>
      </CardContent>
    </Card>
  );
}

function ListWidget({ widget }: { widget: DashboardWidgetMeta }) {
  const [items, setItems] = useState<EntityRecord[]>([]);

  useEffect(() => {
    const name = toSnakeCase(widget.entityName);
    if (widget.entityType === "catalog") {
      api.listCatalog(name).then((all) => setItems(all.slice(0, widget.maxItems)));
    } else if (widget.entityType === "document") {
      api.listDocuments(name).then((all) => setItems(all.slice(0, widget.maxItems)));
    }
  }, [widget]);

  const columns = widget.entityType === "document"
    ? ["_number", "_date", "_posted"]
    : ["_code", "_description"];

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-[13px] font-medium">{widget.title}</CardTitle>
      </CardHeader>
      <CardContent className="p-0">
        <Table>
          <TableHeader>
            <TableRow>
              {columns.map((c) => (
                <TableHead key={c}>{c.replace(/^_/, "").replace(/_/g, " ")}</TableHead>
              ))}
            </TableRow>
          </TableHeader>
          <TableBody>
            {items.length === 0 && (
              <TableRow>
                <TableCell colSpan={columns.length} className="text-center text-muted-foreground py-4">
                  No data
                </TableCell>
              </TableRow>
            )}
            {items.map((item, i) => (
              <TableRow key={i}>
                {columns.map((c) => (
                  <TableCell key={c}>
                    {c === "_posted" ? (
                      <Badge variant={item[c] ? "success" : "secondary"}>
                        {item[c] ? "Posted" : "Draft"}
                      </Badge>
                    ) : c === "_date" && item[c] ? (
                      new Date(item[c] as string).toLocaleString()
                    ) : (
                      String(item[c] ?? "")
                    )}
                  </TableCell>
                ))}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  );
}

function UnknownWidget({ widget }: { widget: DashboardWidgetMeta }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-[13px] font-medium">{widget.title}</CardTitle>
      </CardHeader>
      <CardContent>
        <p className="text-sm text-muted-foreground">
          Unknown widget type: <code>{widget.widgetType}</code>
        </p>
      </CardContent>
    </Card>
  );
}

export const builtInDashboardWidgets: Record<string, React.ComponentType<{ widget: DashboardWidgetMeta }>> = {
  count: CountWidget,
  list: ListWidget,
  calendar: CalendarWidget,
  kanban: KanbanWidget,
  chart: ChartWidget,
};

export function HomePage() {
  const { dashboardWidgets: registeredWidgets } = useWidgetRegistry();
  const [widgets, setWidgets] = useState<DashboardWidgetMeta[]>([]);
  const [counts, setCounts] = useState({ catalogs: 0, documents: 0, registers: 0 });

  useEffect(() => {
    api.getDashboardWidgets().then(setWidgets);
    Promise.all([api.getCatalogs(), api.getDocuments(), api.getRegisters()]).then(
      ([c, d, r]) => setCounts({ catalogs: c.length, documents: d.length, registers: r.length })
    );
  }, []);

  if (widgets.length === 0) {
    const cards = [
      { title: "Catalogs", count: counts.catalogs, icon: BookOpen },
      { title: "Documents", count: counts.documents, icon: FileText },
      { title: "Registers", count: counts.registers, icon: BarChart3 },
    ];

    return (
      <div className="animate-in-page">
        <div className="mb-8">
          <h1 className="text-lg font-semibold tracking-tight">Dashboard</h1>
          <p className="text-[13px] text-muted-foreground mt-0.5">Overview</p>
        </div>
        <div className="grid gap-4 md:grid-cols-3">
          {cards.map((c) => (
            <Card key={c.title}>
              <CardHeader className="flex flex-row items-center justify-between pb-2">
                <CardTitle className="text-[13px] font-medium text-muted-foreground">{c.title}</CardTitle>
                <c.icon className="h-4 w-4 text-muted-foreground/50" />
              </CardHeader>
              <CardContent>
                <div className="text-2xl font-semibold tracking-tight">{c.count}</div>
                <p className="text-xs text-muted-foreground mt-1">registered types</p>
              </CardContent>
            </Card>
          ))}
        </div>
      </div>
    );
  }

  return (
    <div className="animate-in-page">
      <div className="mb-8">
        <h1 className="text-lg font-semibold tracking-tight">Dashboard</h1>
        <p className="text-[13px] text-muted-foreground mt-0.5">Overview</p>
      </div>
      <div className="grid gap-4 md:grid-cols-3">
        {widgets.map((w, i) => {
          const Widget = registeredWidgets.get(w.widgetType) ?? builtInDashboardWidgets[w.widgetType] ?? UnknownWidget;
          return (
            <div key={i} className={widthToColSpan(w.width)}>
              <Widget widget={w} />
            </div>
          );
        })}
      </div>
    </div>
  );
}
