import { useCallback, useEffect, useMemo, useState } from "react";
import { Lock, LockOpen } from "lucide-react";
import { api, type PeriodCloseAuditEntry, type PeriodCloseState } from "../../lib/api";
import { notifyError } from "../../lib/notify-error";
import { formatTimestampDefault } from "../../lib/cell-format";
import { useModel } from "../../lib/model-store";
import { Boundary } from "../pages/boundary";
import { Button } from "../ui/button";
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "../ui/card";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { Segmented } from "../ui/segmented";
import { Textarea } from "../ui/textarea";
import { toast } from "../ui/toast";
import { DatePicker } from "../fields/date-picker";

/** Props of {@link PeriodClose}. */
export interface PeriodCloseProps {
  /** Heading (default "Period close"). */
  title?: string;
  /**
   * The scopes the forms offer, as `PeriodScope` strings: `"global"`, `"context:Sales"`,
   * `"register:Stock"`, `"organization:<id>"`. Default: global plus every bounded context and
   * register in the model.
   */
  scopes?: string[];
  /** How many recent audit rows to show (default 20; managers only). */
  auditRows?: number;
}

type Mode = "close" | "reopen";

/**
 * Period-close administration: the closes in force, and — for holders of the
 * `periodClose.manage` permission — forms to close or reopen a period and the recent audit trail.
 * Documents dated on or before a close date cannot be created, changed, deleted, posted or unposted.
 * Place it on any page: `<PeriodClose />`. The server enforces who may manage periods.
 */
export function PeriodClose(props: PeriodCloseProps) {
  return (
    <Boundary label={props.title || "Period close"}>
      <PeriodCloseBody {...props} />
    </Boundary>
  );
}

function PeriodCloseBody({ title, scopes, auditRows = 20 }: PeriodCloseProps) {
  const model = useModel();
  const [state, setState] = useState<PeriodCloseState | null>(null);
  const [audit, setAudit] = useState<PeriodCloseAuditEntry[]>([]);
  const [mode, setMode] = useState<Mode>("close");
  const [scope, setScope] = useState("global");
  const [date, setDate] = useState("");
  const [hard, setHard] = useState(false);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);

  const scopeOptions = useMemo(() => {
    if (scopes?.length) return scopes;
    const contexts = [...new Set((model?.entities ?? []).map((e) => e.context).filter(Boolean))];
    const registers = (model?.entities ?? []).filter((e) => e.kind === "registers").map((e) => e.logicalName);
    return ["global", ...contexts.map((c) => `context:${c}`), ...registers.map((r) => `register:${r}`)];
  }, [scopes, model]);

  const load = useCallback(async () => {
    try {
      const next = await api.getPeriodClose();
      setState(next);
      setAudit(next.canManage ? await api.getPeriodCloseAudit(auditRows) : []);
    } catch (e) {
      notifyError(e);
    }
  }, [auditRows]);

  useEffect(() => {
    void load();
  }, [load]);

  const submit = async () => {
    if (mode === "close" && !date) return toast.error("Pick a close date");
    if (mode === "reopen" && !reason.trim()) return toast.error("Give a reason for reopening");
    setBusy(true);
    try {
      const lock = mode === "close"
        ? await api.closePeriod({ scope, closedThrough: date, hard, reason: reason.trim() || undefined })
        : await api.reopenPeriod({ scope, closedThrough: date || null, reason: reason.trim() });
      toast.success(lock.closedThrough ? `${lock.label ?? scope} closed through ${lock.closedThrough}` : `${lock.label ?? scope} reopened`);
      setDate("");
      setReason("");
      await load();
    } catch (e) {
      notifyError(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle>{title || "Period close"}</CardTitle>
        <CardDescription>
          Documents dated on or before a close date cannot be created, changed, deleted, posted or unposted.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-5">
        {state === null ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : state.locks.length === 0 ? (
          <p className="text-sm text-muted-foreground">No period is closed.</p>
        ) : (
          <ul className="space-y-1 text-sm" aria-label="Closed periods">
            {state.locks.map((lock) => (
              <li key={lock.scope} className="flex items-center gap-2">
                <Lock className="size-4 text-muted-foreground" aria-hidden="true" />
                <span className="font-medium">{capitalize(lock.label ?? lock.scope)}</span>
                <span className="text-muted-foreground">
                  {lock.state === "hard" ? "hard-closed" : "closed"} through {lock.closedThrough}
                  {lock.updatedBy ? ` by ${lock.updatedBy}` : ""}
                </span>
              </li>
            ))}
          </ul>
        )}

        {state?.canManage ? (
          <form
            className="space-y-3 rounded-card border border-border p-4"
            onSubmit={(e) => {
              e.preventDefault();
              void submit();
            }}
          >
            <Segmented<Mode>
              value={mode}
              onChange={setMode}
              options={[
                { value: "close", label: "Close period", icon: Lock },
                { value: "reopen", label: "Reopen period", icon: LockOpen },
              ]}
            />
            <div className="grid gap-3 sm:grid-cols-2">
              <div className="space-y-1">
                <Label htmlFor="onno-period-scope">Applies to</Label>
                <Input
                  id="onno-period-scope"
                  list="onno-period-scopes"
                  value={scope}
                  onChange={(e) => setScope(e.target.value)}
                />
                <datalist id="onno-period-scopes">
                  {scopeOptions.map((s) => <option key={s} value={s} />)}
                </datalist>
              </div>
              <div className="space-y-1">
                <Label>{mode === "close" ? "Closed through (inclusive)" : "New close date (empty = fully open)"}</Label>
                <DatePicker value={date} onChange={setDate} aria-label="Close date" />
              </div>
            </div>
            {mode === "close" ? (
              <Segmented<"soft" | "hard">
                value={hard ? "hard" : "soft"}
                onChange={(v) => setHard(v === "hard")}
                options={[{ value: "soft", label: "Soft close" }, { value: "hard", label: "Hard close" }]}
              />
            ) : null}
            <div className="space-y-1">
              <Label htmlFor="onno-period-reason">{mode === "close" ? "Note" : "Reason"}</Label>
              <Textarea id="onno-period-reason" value={reason} onChange={(e) => setReason(e.target.value)} />
            </div>
            <Button type="submit" disabled={busy}>
              {mode === "close" ? "Close period" : "Reopen period"}
            </Button>
          </form>
        ) : null}

        {audit.length ? (
          <div className="space-y-1">
            <h3 className="text-sm font-semibold">Recent changes</h3>
            <ul className="space-y-1 text-sm text-muted-foreground">
              {audit.map((entry) => <li key={entry.id}>{describe(entry)}</li>)}
            </ul>
          </div>
        ) : null}
      </CardContent>
    </Card>
  );
}

function describe(entry: PeriodCloseAuditEntry): string {
  const at = formatTimestampDefault(entry.at) ?? entry.at;
  let line: string;
  if (entry.action === "OVERRIDE" || entry.action === "ADJUSTMENT") {
    line = `${entry.action === "OVERRIDE" ? "override" : "adjustment"}: ${(entry.operation ?? "change").toLowerCase()} ${entry.documentType ?? ""}`
      + (entry.documentDate ? ` dated ${entry.documentDate.slice(0, 10)}` : "")
      + ` (${entry.scope} closed through ${entry.closedThrough})`;
  } else if (entry.action === "CLOSE") {
    line = `${entry.hard ? "hard-closed" : "closed"} ${entry.scope} through ${entry.closedThrough}`;
  } else {
    line = `reopened ${entry.scope}${entry.closedThrough ? `, now closed through ${entry.closedThrough}` : " fully"} (was ${entry.previous})`;
  }
  return `${at} — ${line}${entry.actor ? ` by ${entry.actor}` : ""}${entry.reason ? `: ${entry.reason}` : ""}`;
}

function capitalize(text: string): string {
  return text ? text[0].toUpperCase() + text.slice(1) : text;
}
