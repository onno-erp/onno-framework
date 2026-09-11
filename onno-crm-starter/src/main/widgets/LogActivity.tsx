import { text as t } from "@onno/widget-sdk";
import { useId } from "react";
import { Button, Input, Label, Popover, PopoverContent, PopoverTrigger, Select, SelectContent, SelectItem, SelectTrigger, SelectValue, Textarea, useState } from "@onno/widget-sdk";
import { Phone } from "lucide-react";
import { request } from "./CrmWorkspace";

/** Manual interactions are ordinary CRM internal events, alongside messages and notes. */
export function LogActivity({customerId, conversationId, onSaved}: {customerId: string; conversationId: string; onSaved: () => Promise<void>}) {
  const [open, setOpen] = useState(false);
  const [type, setType] = useState("CALL_COMPLETED");
  const [details, setDetails] = useState("");
  const [recording, setRecording] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const id = useId();
  const save = async () => {
    if (busy || !details.trim()) return;
    setError("");
    if (recording.trim()) {
      try { if (new URL(recording.trim()).protocol !== "https:") throw new Error(); }
      catch { setError(t("crm.activity.linkError")); return; }
    }
    const body = details.trim() + (recording.trim() ? `\nRecording: ${recording.trim()}` : "");
    if (body.length > 7000) { setError(t("crm.activity.tooLong")); return; }
    setBusy(true);
    try {
      await request(`/contacts/${encodeURIComponent(customerId)}/activity`, {conversationId, type, details: body});
      setDetails(""); setRecording(""); setOpen(false);
      await onSaved();
    } catch (e) { setError((e as Error).message); }
    finally { setBusy(false); }
  };
  return <Popover open={open} onOpenChange={value => {if (!busy) {setOpen(value); setError("");}}}>
    <PopoverTrigger asChild><Button size="toolbar" variant="subtle"><Phone className="size-4" />{t("crm.activity.log")}</Button></PopoverTrigger>
    <PopoverContent align="end" className="w-96 max-w-[calc(100vw-32px)] space-y-3 p-4" aria-label={t("crm.activity.log")}>
      <div className="text-sm font-semibold">{t("crm.activity.addSummary")}</div>
      <Select value={type} onValueChange={setType} disabled={busy}>
        <SelectTrigger aria-label={t("crm.activity.type")}><SelectValue /></SelectTrigger>
        <SelectContent><SelectItem value="CALL_COMPLETED">{t("crm.activity.callCompleted")}</SelectItem><SelectItem value="OTHER">{t("crm.activity.other")}</SelectItem></SelectContent>
      </Select>
      <div className="space-y-1"><Label htmlFor={`${id}-summary`}>{t("crm.activity.summary")}</Label><Textarea id={`${id}-summary`} value={details} onChange={e => setDetails(e.target.value)} maxLength={7000} disabled={busy} placeholder={t("crm.activity.summaryPlaceholder")} /></div>
      <div className="space-y-1"><Label htmlFor={`${id}-recording`}>{t("crm.activity.recordingLink")}</Label><Input id={`${id}-recording`} value={recording} onChange={e => setRecording(e.target.value)} maxLength={2000} disabled={busy} placeholder="https://…" /></div>
      <p className="text-xs text-muted-foreground">{t("crm.activity.visibility")}</p>
      {error && <p role="alert" className="text-xs text-destructive">{error}</p>}
      <div className="flex justify-end gap-2"><Button variant="ghost" disabled={busy} onClick={() => setOpen(false)}>{t("crm.group.cancel")}</Button><Button disabled={busy || !details.trim()} onClick={() => void save()}>{busy ? t("crm.activity.saving") : t("crm.activity.save")}</Button></div>
    </PopoverContent>
  </Popover>;
}
