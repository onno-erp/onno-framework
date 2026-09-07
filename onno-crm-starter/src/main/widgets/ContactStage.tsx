import { api, Badge, Select, SelectTrigger, SelectValue, SelectContent, SelectItem, useCallback, useEffect, useState, useUiEvents, type EntityRecord } from "@onno/widget-sdk";

export function useContactStages() {
  const [stages, setStages] = useState<EntityRecord[]>([]);
  const [error, setError] = useState("");
  const load = useCallback(() => {
    void api.listCatalog("crm_customer_stages").then(rows => {
      setStages(rows.filter(row => !row.deletionMark).sort((a, b) => String(a.description).localeCompare(String(b.description))));
      setError("");
    }).catch(() => setError("Could not load contact stages."));
  }, []);
  useEffect(load, [load]);
  useUiEvents(load, { types: ["created", "updated", "deleted"], entityType: "catalog", entityName: "CrmCustomerStages" });
  return { stages, error };
}

export function ContactStageLabel({ stage, fallback = "—" }: { stage?: EntityRecord; fallback?: string }) {
  const color = typeof stage?.color === "string" && /^#[0-9a-f]{6}$/i.test(stage.color) ? stage.color : undefined;
  return <Badge color={color} variant="secondary" className="max-w-full rounded-pill px-2.5 py-0.5 text-xs font-medium">
    <span className="truncate">{String(stage?.description ?? fallback)}</span>
  </Badge>;
}

export function ContactStagePicker({ stages, value, onChange, label }: { stages: EntityRecord[]; value: string; onChange: (value: string) => void; label: string }) {
  return <Select value={value || "__unassigned__"} onValueChange={value => onChange(value === "__unassigned__" ? "" : value)}>
    <SelectTrigger aria-label={label}><SelectValue placeholder="Choose a stage…" /></SelectTrigger>
    <SelectContent>
      <SelectItem value="__unassigned__">Unassigned</SelectItem>
      {stages.map(stage => <SelectItem key={String(stage.id)} value={String(stage.id)}><ContactStageLabel stage={stage} /></SelectItem>)}
    </SelectContent>
  </Select>;
}
