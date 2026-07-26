import { useCallback, useEffect, useState } from "react";
import { CheckCircle2, Clock3, Loader2, UserRound, UserRoundPlus } from "lucide-react";
import { toast } from "sonner";
import { api } from "@/lib/api";
import type {
  DashboardWidgetMeta,
  ProcessWorkItem,
  ProcessWorkItemEvent,
  TaskAssigneeOption,
  UiEvent,
} from "@/lib/types";
import { useUiEvents } from "@/hooks/use-ui-events";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { HintIcon } from "@/components/ui/hint-icon";
import { DialogShell } from "@/components/ui/dialog-shell";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { toSnakeCase } from "@/lib/utils";

const humanize = (value: string) =>
  value
    .toLowerCase()
    .replace(/[_-]+/g, " ")
    .replace(/^\p{L}/u, (letter) => letter.toUpperCase());

export function ProcessTasksWidget({ widget }: { widget: DashboardWidgetMeta }) {
  const [tasks, setTasks] = useState<ProcessWorkItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [delegating, setDelegating] = useState<ProcessWorkItem | null>(null);
  const [history, setHistory] = useState<Record<string, ProcessWorkItemEvent[]>>({});

  const reload = useCallback(async () => {
    setError(null);
    try {
      setTasks(await api.listProcessTasks());
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  const onUiEvent = useCallback((event: UiEvent) => {
    if (event.type === "tasks-changed") {
      void reload();
    }
  }, [reload]);
  useUiEvents(onUiEvent);

  const claim = async (task: ProcessWorkItem) => {
    setBusy(task.id);
    try {
      await api.claimProcessTask(task.id);
      toast.success("Task claimed");
      await reload();
    } catch (failure) {
      toast.error(failure instanceof Error ? failure.message : "Could not claim task");
    } finally {
      setBusy(null);
    }
  };

  const complete = async (task: ProcessWorkItem, outcome: string) => {
    setBusy(task.id);
    try {
      await api.completeProcessTask(task.id, outcome);
      toast.success(`Task completed: ${humanize(outcome)}`);
      await reload();
    } catch (failure) {
      toast.error(failure instanceof Error ? failure.message : "Could not complete task");
    } finally {
      setBusy(null);
    }
  };

  const toggleHistory = async (task: ProcessWorkItem) => {
    if (history[task.id]) {
      setHistory((current) => {
        const next = { ...current };
        delete next[task.id];
        return next;
      });
      return;
    }
    try {
      const events = await api.getProcessTaskHistory(task.id);
      setHistory((current) => ({ ...current, [task.id]: events }));
    } catch (failure) {
      toast.error(failure instanceof Error ? failure.message : "Could not load task history");
    }
  };

  return (
    <Card className="pointer-events-auto w-full">
      <CardHeader className="pb-3">
        <div className="flex items-center gap-1.5">
          <CardTitle className="text-base">{widget.title || "My tasks"}</CardTitle>
          {widget.hint ? <HintIcon text={widget.hint} /> : null}
        </div>
      </CardHeader>
      <CardContent className="space-y-3">
        {loading ? (
          <div className="flex items-center gap-2 py-5 text-sm text-muted-foreground">
            <Loader2 className="size-4 animate-spin" /> Loading tasks…
          </div>
        ) : error ? (
          <div className="flex items-center justify-between gap-3 py-3">
            <p className="text-sm text-destructive">{error}</p>
            <Button variant="outline" size="sm" onClick={() => void reload()}>Retry</Button>
          </div>
        ) : tasks.length === 0 ? (
          <div className="flex items-center gap-2 py-5 text-sm text-muted-foreground">
            <CheckCircle2 className="size-4" /> You have no open tasks.
          </div>
        ) : (
          tasks.map((task) => (
            <div key={task.id} className="rounded-panel border border-border p-4">
              <div className="flex flex-wrap items-start justify-between gap-2">
                <div>
                  <div className="font-medium text-foreground">{task.title}</div>
                  <div className="mt-1 text-xs text-muted-foreground">
                    {humanize(task.definitionKey)} · {humanize(task.stepKey)}
                  </div>
                </div>
                <Badge variant={task.status === "CLAIMED" ? "secondary" : "outline"}>
                  {humanize(task.status)}
                </Badge>
              </div>
              {task.assignee ? (
                <div className="mt-3 flex items-center gap-1.5 text-xs text-muted-foreground">
                  <UserRound className="size-3.5" /> {task.assignee}
                </div>
              ) : null}
              {task.subject ? (
                <a
                  className="mt-2 inline-flex text-sm font-medium text-primary hover:underline"
                  href={`/${task.subject.kind}/${toSnakeCase(task.subject.entityName)}/${task.subject.id}`}
                >
                  Open {humanize(task.subject.entityName)}
                </a>
              ) : null}
              <div className="mt-4 flex flex-wrap gap-2">
                {task.status === "OPEN" ? (
                  <Button size="sm" onClick={() => void claim(task)} disabled={busy === task.id}>
                    {busy === task.id ? <Loader2 className="animate-spin" /> : null}
                    Claim
                  </Button>
                ) : (
                  <>
                  {task.outcomes.map((outcome) => (
                    <Button
                      key={outcome}
                      size="sm"
                      variant="outline"
                      onClick={() => void complete(task, outcome)}
                      disabled={busy === task.id}
                    >
                      {busy === task.id ? <Loader2 className="animate-spin" /> : null}
                      {humanize(outcome)}
                    </Button>
                  ))}
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => setDelegating(task)}
                    disabled={busy === task.id}
                  >
                    <UserRoundPlus /> Delegate
                  </Button>
                  </>
                )}
                <Button size="sm" variant="ghost" onClick={() => void toggleHistory(task)}>
                  <Clock3 /> History
                </Button>
              </div>
              {history[task.id] ? (
                <ol className="mt-3 space-y-2 border-t border-border pt-3 text-xs text-muted-foreground">
                  {history[task.id].map((event) => (
                    <li key={event.id}>
                      <span className="font-medium text-foreground">{humanize(event.type)}</span>
                      {event.actor ? ` by ${event.actor}` : ""}
                      {event.toAssignee ? ` → ${event.toAssignee}` : ""}
                      {event.reason ? ` — ${event.reason}` : ""}
                    </li>
                  ))}
                </ol>
              ) : null}
            </div>
          ))
        )}
      </CardContent>
      {delegating ? (
        <DelegateTaskDialog
          task={delegating}
          onClose={() => setDelegating(null)}
          onDelegated={async () => {
            setDelegating(null);
            await reload();
          }}
        />
      ) : null}
    </Card>
  );
}

function DelegateTaskDialog({
  task,
  onClose,
  onDelegated,
}: {
  task: ProcessWorkItem;
  onClose: () => void;
  onDelegated: () => Promise<void>;
}) {
  const [query, setQuery] = useState("");
  const [options, setOptions] = useState<TaskAssigneeOption[]>([]);
  const [target, setTarget] = useState<TaskAssigneeOption | null>(null);
  const [reason, setReason] = useState("");
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    let current = true;
    const timer = window.setTimeout(() => {
      void api.searchTaskAssignees(query).then((result) => {
        if (current) setOptions(result.filter((option) => option.actorId !== task.assigneeId));
      }).catch(() => {
        if (current) setOptions([]);
      });
    }, 150);
    return () => {
      current = false;
      window.clearTimeout(timer);
    };
  }, [query, task.assigneeId]);

  const submit = async () => {
    if (!target || !reason.trim()) return;
    setSubmitting(true);
    try {
      await api.delegateProcessTask(task.id, target.actorId, reason.trim());
      toast.success(`Task delegated to ${target.display}`);
      await onDelegated();
    } catch (failure) {
      toast.error(failure instanceof Error ? failure.message : "Could not delegate task");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <DialogShell
      title="Delegate task"
      description={task.title}
      onOpenChange={(open) => { if (!open && !submitting) onClose(); }}
      dismissable={!submitting}
      footer={
        <>
          <Button variant="outline" onClick={onClose} disabled={submitting}>Cancel</Button>
          <Button onClick={() => void submit()} disabled={!target || !reason.trim() || submitting}>
            {submitting ? <Loader2 className="animate-spin" /> : <UserRoundPlus />}
            Delegate
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        <div className="space-y-2">
          <label htmlFor="task-assignee-search" className="text-sm font-medium">Employee</label>
          <Input
            id="task-assignee-search"
            value={query}
            onChange={(event) => {
              setQuery(event.target.value);
              setTarget(null);
            }}
            placeholder="Search employees"
          />
          <div className="max-h-44 space-y-1 overflow-y-auto">
            {options.map((option) => (
              <button
                key={option.actorId}
                type="button"
                className={`w-full rounded-field border px-3 py-2 text-left text-sm ${
                  target?.actorId === option.actorId
                    ? "border-primary bg-primary/10"
                    : "border-border hover:bg-muted"
                }`}
                onClick={() => {
                  setTarget(option);
                  setQuery(option.display);
                }}
              >
                <span className="block font-medium">{option.display}</span>
                <span className="block text-xs text-muted-foreground">{option.username}</span>
              </button>
            ))}
          </div>
        </div>
        <div className="space-y-2">
          <label htmlFor="task-delegation-reason" className="text-sm font-medium">Reason</label>
          <Textarea
            id="task-delegation-reason"
            rows={3}
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            placeholder="Why is this task being transferred?"
          />
        </div>
      </div>
    </DialogShell>
  );
}
