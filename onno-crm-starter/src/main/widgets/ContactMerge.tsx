import { ContactStageLabel, useContactStages } from "./ContactStage";
import { Merge } from "lucide-react";
import { Label, Button, Popover, PopoverTrigger, PopoverContent, Select, SelectTrigger, SelectValue, SelectContent, SelectItem, registerListSelection, useEffect, useState, toast, type ListSelectionProps } from "@onno/widget-sdk";
import { request } from "./CrmWorkspace";

type Contact = { fields: Record<string, unknown> };
type Preview = { source: string; target: string; revision: string; conflicts: { key: string; source: unknown; target: unknown }[]; conversations: number; opportunities: number; identities: number };
const labels: Record<string, string> = { description: "Name", stage: "Lifecycle stage", avatarUrl: "Photo", source: "Lead source", owner: "Assigned to", email: "Email", phone: "Phone", company: "Company", city: "City", tags: "Tags" };

export function ContactMerge({ ids, complete }: ListSelectionProps) {
  const { stages } = useContactStages();
  const [open, setOpen] = useState(false);
  const [contacts, setContacts] = useState<Contact[]>([]);
  const [target, setTarget] = useState("");
  const [preview, setPreview] = useState<Preview | null>(null);
  const [choices, setChoices] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const selection = ids.join(",");
  useEffect(() => { setOpen(false); setPreview(null); }, [selection]);
  useEffect(() => {
    if (!open) return;
    let active = true;
    setBusy(true); setError(""); setContacts([]); setPreview(null); setChoices({});
    Promise.all(ids.map(id => request<Contact>(`/contacts/${id}`)))
      .then(data => { if (active) { setContacts(data); setTarget(ids[0]); } })
      .catch(e => { if (active) setError(e.message); })
      .finally(() => { if (active) setBusy(false); });
    return () => { active = false; };
  }, [open, selection]);
  const run = async (work: () => Promise<void>) => {
    setBusy(true); setError("");
    try { await work(); } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  };
  const targetName = String(contacts.find(c => c.fields.id === target)?.fields.description || "selected contact");
  return <Popover open={open} onOpenChange={(value: boolean) => { if (!busy) setOpen(value); }}>
    <PopoverTrigger asChild><Button size="toolbar" variant="subtle" disabled={ids.length !== 2} title={ids.length !== 2 ? "Select exactly two contacts to merge" : "Merge selected contacts"}><Merge />Merge contacts</Button></PopoverTrigger>
    <PopoverContent align="start" className="w-[min(480px,calc(100vw-32px))] max-h-[70vh] overflow-y-auto space-y-4 p-4">
      <h3 className="text-sm font-semibold">Merge selected contacts</h3>
      {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
      {!preview ? <>
        <p className="text-sm text-muted-foreground">Choose the contact to keep. The other contact’s conversations, opportunities, identities, and comments will move into it.</p>
        <Label className="block space-y-2 text-sm"><span>Contact to keep</span>
          <Select value={target} onValueChange={setTarget} disabled={busy || contacts.length !== 2}>
            <SelectTrigger aria-label="Contact to keep"><SelectValue placeholder="Choose a contact" /></SelectTrigger>
            <SelectContent>{contacts.map(c => <SelectItem key={String(c.fields.id)} value={String(c.fields.id)}>{String(c.fields.description)}{c.fields.email ? ` · ${c.fields.email}` : c.fields.phone ? ` · ${c.fields.phone}` : ""}</SelectItem>)}</SelectContent>
          </Select>
        </Label>
        <Button disabled={busy || contacts.length !== 2} onClick={() => void run(async () => {
          setChoices({}); setPreview(await request<Preview>("/contacts/merge-preview", { source: ids.find(id => id !== target), target }));
        })}>{busy ? "Loading…" : "Review merge"}</Button>
      </> : <>
        <p className="text-sm">Keep <strong>{targetName}</strong> and move {preview.conversations} conversations, {preview.opportunities} opportunities, and {preview.identities} linked identities into it. Message history and reply channels are preserved.</p>
        {preview.conflicts.map(c => <Label key={c.key} className="block space-y-2 text-sm"><span>{labels[c.key] || c.key}</span>
          <Select value={choices[c.key] || ""} onValueChange={(value: string) => setChoices(previous => ({ ...previous, [c.key]: value }))} disabled={busy}>
            <SelectTrigger aria-label={`Resolve ${labels[c.key] || c.key}`}><SelectValue placeholder="Choose the value to keep" /></SelectTrigger>
            <SelectContent><SelectItem value="target">Keep: {c.key === "stage" ? <ContactStageLabel stage={stages.find(s => String(s.id) === String(c.target))} /> : String(c.target)}</SelectItem><SelectItem value="source">Use other contact: {c.key === "stage" ? <ContactStageLabel stage={stages.find(s => String(s.id) === String(c.source))} /> : String(c.source)}</SelectItem></SelectContent>
          </Select>
        </Label>)}
        <p className="text-xs text-muted-foreground">The duplicate will be archived. Undo is available only before later changes to the merged records.</p>
        <div className="flex gap-2"><Button disabled={busy || preview.conflicts.some(c => !choices[c.key])} onClick={() => void run(async () => {
          await request("/contacts/merge", { source: preview.source, target: preview.target, revision: preview.revision, choices });
          toast.success(`Contacts merged into ${targetName}`); setOpen(false); complete();
        })}>{busy ? "Merging…" : "Confirm merge"}</Button><Button variant="outline" disabled={busy} onClick={() => setPreview(null)}>Back</Button></div>
      </>}
    </PopoverContent>
  </Popover>;
}
registerListSelection("crmContactMerge", ContactMerge);
