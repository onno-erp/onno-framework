import { useMemo, useState, lazy, Suspense } from "react";
import type { AttributeMeta, TabularSectionMeta, EntityRecord } from "@/lib/types";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { RefSelect } from "@/components/ref-select";
import { DatePicker } from "@/components/date-picker";
import { TabularSectionEditor } from "@/components/tabular-section-editor";
import { useWidgetRegistry } from "@/providers/widget-registry";
import { Avatar, AvatarImage, AvatarFallback } from "@/components/ui/avatar";

const RichTextEditor = lazy(() =>
  import("@/components/rich-text-editor").then((m) => ({ default: m.RichTextEditor }))
);
const GeoPicker = lazy(() =>
  import("@/components/geo-picker").then((m) => ({ default: m.GeoPicker }))
);
const GeoShapeEditor = lazy(() =>
  import("@/components/geo-shape-editor").then((m) => ({ default: m.GeoShapeEditor }))
);

interface EntityFormProps {
  attributes: AttributeMeta[];
  baseFields?: { label: string; key: string; type?: string; maxLength?: number }[];
  tabularSections?: TabularSectionMeta[];
  initial?: EntityRecord;
  onSubmit: (data: EntityRecord, andPost?: boolean) => void;
  onCancel: () => void;
  showSaveAndPost?: boolean;
}

function fieldType(attr: AttributeMeta): string {
  if (attr.isRef) return "text";
  switch (attr.javaType) {
    case "BigDecimal":
    case "int":
    case "Integer":
    case "long":
    case "Long":
    case "double":
    case "Double":
      return "number";
    case "boolean":
    case "Boolean":
      return "checkbox";
    case "LocalDate":
      return "date";
    case "LocalDateTime":
      return "datetime-local";
    default:
      return "text";
  }
}

export function EntityForm({
  attributes,
  baseFields = [],
  tabularSections = [],
  initial = {},
  onSubmit,
  onCancel,
  showSaveAndPost = false,
}: EntityFormProps) {
  const { fieldRenderers } = useWidgetRegistry();
  const [data, setData] = useState<EntityRecord>({ ...initial });

  const set = (key: string, value: unknown) =>
    setData((prev) => ({ ...prev, [key]: value }));

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    onSubmit(data);
  };

  const formAttrs = useMemo(() => {
    const filtered = attributes.filter((a) => a.visibleInForm !== false);
    filtered.sort((a, b) => (a.order ?? 0) - (b.order ?? 0));
    return filtered;
  }, [attributes]);

  const groups = useMemo(() => {
    const map = new Map<string, AttributeMeta[]>();
    for (const attr of formAttrs) {
      const g = attr.group || "";
      if (!map.has(g)) map.set(g, []);
      map.get(g)!.push(attr);
    }
    return map;
  }, [formAttrs]);

  const widthClass = (hint: string) => {
    if (hint === "full") return "md:col-span-2 lg:col-span-3";
    if (hint === "wide") return "md:col-span-2";
    return "";
  };

  const renderField = (attr: AttributeMeta) => {
    const type = fieldType(attr);
    const wc = widthClass(attr.widthHint);

    const CustomRenderer = fieldRenderers.get(attr.javaType);
    if (CustomRenderer) {
      return (
        <div key={attr.fieldName} className={`grid gap-2 ${wc}`}>
          <Label htmlFor={attr.fieldName}>
            {attr.displayName}
            {attr.required && <span className="text-destructive ml-1">*</span>}
          </Label>
          <CustomRenderer
            attr={attr}
            value={data[attr.fieldName]}
            onChange={(v) => set(attr.fieldName, v)}
          />
        </div>
      );
    }

    if (attr.widget === "avatar") {
      const value = (data[attr.fieldName] as string) ?? "";
      const initials = String(data.fullName ?? data.name ?? data.description ?? "?")
        .slice(0, 2)
        .toUpperCase();
      return (
        <div key={attr.fieldName} className={`grid gap-2 ${wc}`}>
          <Label htmlFor={attr.fieldName}>
            {attr.displayName}
            {attr.required && <span className="text-destructive ml-1">*</span>}
          </Label>
          <div className="flex items-center gap-3">
            <Avatar className="h-12 w-12 text-sm">
              {value && <AvatarImage src={value} alt="" />}
              <AvatarFallback>{initials}</AvatarFallback>
            </Avatar>
            <Input
              id={attr.fieldName}
              type="url"
              placeholder="https://…"
              value={value}
              onChange={(e) => set(attr.fieldName, e.target.value)}
            />
          </div>
        </div>
      );
    }

    if (attr.widget === "textarea") {
      return (
        <div key={attr.fieldName} className={`grid gap-2 ${wc || "md:col-span-2 lg:col-span-3"}`}>
          <Label htmlFor={attr.fieldName}>
            {attr.displayName}
            {attr.required && <span className="text-destructive ml-1">*</span>}
          </Label>
          <Textarea
            id={attr.fieldName}
            rows={4}
            maxLength={attr.length > 0 ? attr.length : undefined}
            required={attr.required}
            value={(data[attr.fieldName] as string) ?? ""}
            onChange={(e) => set(attr.fieldName, e.target.value)}
          />
        </div>
      );
    }

    if (attr.widget === "richtext") {
      return (
        <div key={attr.fieldName} className={`grid gap-2 ${wc || "md:col-span-2 lg:col-span-3"}`}>
          <Label htmlFor={attr.fieldName}>
            {attr.displayName}
            {attr.required && <span className="text-destructive ml-1">*</span>}
          </Label>
          <Suspense fallback={<div className="h-[120px] rounded-md border bg-muted animate-pulse" />}>
            <RichTextEditor
              value={(data[attr.fieldName] as string) ?? ""}
              onChange={(html) => set(attr.fieldName, html)}
            />
          </Suspense>
        </div>
      );
    }

    if (attr.widget === "geolocation") {
      return (
        <div key={attr.fieldName} className={`grid gap-2 ${wc || "md:col-span-2 lg:col-span-3"}`}>
          <Label htmlFor={attr.fieldName}>
            {attr.displayName}
            {attr.required && <span className="text-destructive ml-1">*</span>}
          </Label>
          <Suspense fallback={<div className="h-[240px] rounded-md border bg-muted animate-pulse" />}>
            <GeoPicker
              value={(data[attr.fieldName] as string) ?? ""}
              onChange={(val) => set(attr.fieldName, val)}
            />
          </Suspense>
        </div>
      );
    }

    if (attr.widget === "geoshape") {
      return (
        <div key={attr.fieldName} className={`grid gap-2 ${wc || "md:col-span-2 lg:col-span-3"}`}>
          <Label htmlFor={attr.fieldName}>
            {attr.displayName}
            {attr.required && <span className="text-destructive ml-1">*</span>}
          </Label>
          <Suspense fallback={<div className="h-[300px] rounded-md border bg-muted animate-pulse" />}>
            <GeoShapeEditor
              value={(data[attr.fieldName] as string) ?? ""}
              onChange={(val) => set(attr.fieldName, val)}
            />
          </Suspense>
        </div>
      );
    }

    if (attr.isEnum && attr.enumValues) {
      return (
        <div key={attr.fieldName} className={`grid gap-2 ${wc}`}>
          <Label htmlFor={attr.fieldName}>
            {attr.displayName}
            {attr.required && <span className="text-destructive ml-1">*</span>}
          </Label>
          <Select
            value={(data[attr.fieldName] as string) ?? ""}
            onValueChange={(v) => set(attr.fieldName, v)}
          >
            <SelectTrigger>
              <SelectValue placeholder={`Select ${attr.displayName}`} />
            </SelectTrigger>
            <SelectContent>
              {attr.enumValues.map((ev) => (
                <SelectItem key={ev.id} value={ev.id}>
                  {ev.name}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      );
    }

    if (attr.isRef && attr.refTarget) {
      return (
        <div key={attr.fieldName} className={`grid gap-2 ${wc}`}>
          <Label htmlFor={attr.fieldName}>
            {attr.displayName}
            {attr.required && <span className="text-destructive ml-1">*</span>}
          </Label>
          <RefSelect
            catalogName={attr.refTarget}
            value={data[attr.fieldName] as string}
            onChange={(id) => set(attr.fieldName, id)}
          />
        </div>
      );
    }

    if (type === "checkbox") {
      return (
        <div key={attr.fieldName} className={`flex items-center gap-2 ${wc}`}>
          <Checkbox
            id={attr.fieldName}
            checked={!!data[attr.fieldName]}
            onCheckedChange={(v) => set(attr.fieldName, v)}
          />
          <Label htmlFor={attr.fieldName}>{attr.displayName}</Label>
        </div>
      );
    }

    if (type === "date" || type === "datetime-local") {
      return (
        <div key={attr.fieldName} className={`grid gap-2 ${wc}`}>
          <Label htmlFor={attr.fieldName}>
            {attr.displayName}
            {attr.required && <span className="text-destructive ml-1">*</span>}
          </Label>
          <DatePicker
            value={(data[attr.fieldName] as string) ?? ""}
            onChange={(val) => set(attr.fieldName, val)}
            includeTime={type === "datetime-local"}
          />
        </div>
      );
    }

    return (
      <div key={attr.fieldName} className={`grid gap-2 ${wc}`}>
        <Label htmlFor={attr.fieldName}>
          {attr.displayName}
          {attr.required && <span className="text-destructive ml-1">*</span>}
        </Label>
        <Input
          id={attr.fieldName}
          type={type}
          step={type === "number" && attr.scale > 0 ? Math.pow(10, -attr.scale).toString() : undefined}
          maxLength={attr.length > 0 ? attr.length : undefined}
          required={attr.required}
          value={(data[attr.fieldName] as string) ?? ""}
          onChange={(e) =>
            set(
              attr.fieldName,
              type === "number" ? parseFloat(e.target.value) || "" : e.target.value
            )
          }
        />
      </div>
    );
  };

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      {baseFields.map((f) => (
        <div key={f.key} className="grid gap-2">
          <Label htmlFor={f.key}>{f.label}</Label>
          {f.type === "date" || f.type === "datetime-local" ? (
            <DatePicker
              value={(data[f.key] as string) ?? ""}
              onChange={(val) => set(f.key, val)}
              includeTime={f.type === "datetime-local"}
            />
          ) : (
            <Input
              id={f.key}
              type={f.type || "text"}
              maxLength={f.maxLength}
              value={(data[f.key] as string) ?? ""}
              onChange={(e) => set(f.key, e.target.value)}
            />
          )}
        </div>
      ))}

      {[...groups.entries()].map(([groupName, groupAttrs]) => (
        <div key={groupName || "__default"} className="space-y-4">
          {groupName && (
            <div className="pt-4 border-b border-border pb-2">
              <h3 className="text-[13px] font-medium text-muted-foreground">
                {groupName}
              </h3>
            </div>
          )}
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
            {groupAttrs.map((attr) => renderField(attr))}
          </div>
        </div>
      ))}

      {tabularSections.length > 0 && (
        <Tabs defaultValue={tabularSections[0].name} className="pt-2">
          <TabsList>
            {tabularSections.map((ts) => (
              <TabsTrigger key={ts.name} value={ts.name}>
                {ts.name}
              </TabsTrigger>
            ))}
          </TabsList>
          {tabularSections.map((ts) => (
            <TabsContent key={ts.name} value={ts.name}>
              <TabularSectionEditor
                section={ts}
                rows={(data[ts.name] as EntityRecord[]) ?? []}
                onChange={(rows) => set(ts.name, rows)}
              />
            </TabsContent>
          ))}
        </Tabs>
      )}

      <div className="flex justify-end gap-2 pt-4 border-t">
        <Button type="button" variant="outline" onClick={onCancel}>
          Cancel
        </Button>
        {showSaveAndPost && (
          <Button type="button" variant="secondary" onClick={() => onSubmit(data, true)}>
            Save & Post
          </Button>
        )}
        <Button type="submit">Save</Button>
      </div>
    </form>
  );
}
