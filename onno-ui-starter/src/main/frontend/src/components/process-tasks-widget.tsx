import { useCallback, useEffect, useMemo, useState } from "react";
import { CheckCircle2, Clock3, Loader2, Search, UserRoundPlus } from "lucide-react";
import { toast } from "@/components/ui/toast";
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
import { HintIcon } from "@/components/ui/hint-icon";
import { DialogShell } from "@/components/ui/dialog-shell";
import { Input } from "@/components/ui/input";
import { Segmented } from "@/components/ui/segmented";
import { Textarea } from "@/components/ui/textarea";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { glassAvatar, initials, tint } from "@/lib/avatar-utils";
import { toSnakeCase } from "@/lib/utils";
import { withBasePath } from "@/lib/base-path";

const humanize = (value: string) =>
  value
    .toLowerCase()
    .replace(/[_-]+/g, " ")
    .replace(/^\p{L}/u, (letter) => letter.toUpperCase());

type TaskFilter = "all" | "mine" | "available";

const singularize = (value: string) => {
  if (value.endsWith("ies")) return `${value.slice(0, -3)}y`;
  if (value.endsWith("s") && !value.endsWith("ss")) return value.slice(0, -1);
  return value;
};

const timeAgo = (iso?: string | null) => {
  if (!iso) return "";
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return "";
  const seconds = Math.max(1, Math.round((Date.now() - then) / 1000));
  const formatter = new Intl.RelativeTimeFormat(undefined, { numeric: "auto", style: "narrow" });
  const units: [Intl.RelativeTimeFormatUnit, number][] = [
    ["year", 31_536_000],
    ["month", 2_592_000],
    ["week", 604_800],
    ["day", 86_400],
    ["hour", 3_600],
    ["minute", 60],
  ];
  for (const [unit, size] of units) {
    if (seconds >= size) return formatter.format(-Math.floor(seconds / size), unit);
  }
  return formatter.format(-seconds, "second");
};

const historyEventText = (event: ProcessWorkItemEvent) => {
  const actor = event.actor || "Someone";
  switch (event.type) {
    case "CREATED":
      return "Task created";
    case "CLAIMED":
      return event.actor ? `${actor} claimed the task` : "Task claimed";
    case "DELEGATED": {
      const source = event.fromAssignee ? ` from ${event.fromAssignee}` : "";
      const target = event.toAssignee ? ` to ${event.toAssignee}` : "";
      return `${actor} delegated the task${source}${target}`;
    }
    case "COMPLETED":
      return event.actor ? `${actor} completed the task` : "Task completed";
  }
};

export function ProcessTasksWidget({ widget }: { widget: DashboardWidgetMeta }) {
  const [tasks, setTasks] = useState<ProcessWorkItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [delegating, setDelegating] = useState<ProcessWorkItem | null>(null);
  const [completing, setCompleting] = useState<{ task: ProcessWorkItem; outcome: string } | null>(null);
  const [historyTask, setHistoryTask] = useState<ProcessWorkItem | null>(null);
  const [historyEvents, setHistoryEvents] = useState<ProcessWorkItemEvent[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [filter, setFilter] = useState<TaskFilter>("all");
  const [query, setQuery] = useState("");
  const [people, setPeople] = useState<TaskAssigneeOption[]>([]);

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

  useEffect(() => {
    let current = true;
    void api.searchTaskAssignees("").then((result) => {
      if (current) setPeople(result);
    }).catch(() => {
      if (current) setPeople([]);
    });
    return () => {
      current = false;
    };
  }, []);

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
      setCompleting(null);
      await reload();
    } catch (failure) {
      toast.error(failure instanceof Error ? failure.message : "Could not complete task");
    } finally {
      setBusy(null);
    }
  };

  const openHistory = async (task: ProcessWorkItem) => {
    setHistoryTask(task);
    setHistoryEvents([]);
    setHistoryLoading(true);
    try {
      const events = await api.getProcessTaskHistory(task.id);
      setHistoryEvents(events);
    } catch (failure) {
      toast.error(failure instanceof Error ? failure.message : "Could not load task history");
      setHistoryTask(null);
    } finally {
      setHistoryLoading(false);
    }
  };

  const counts = useMemo(() => ({
    all: tasks.length,
    mine: tasks.filter((task) => task.status === "CLAIMED").length,
    available: tasks.filter((task) => task.status === "OPEN").length,
  }), [tasks]);

  const visibleTasks = useMemo(() => {
    const needle = query.trim().toLowerCase();
    return tasks.filter((task) => {
      if (filter === "mine" && task.status !== "CLAIMED") return false;
      if (filter === "available" && task.status !== "OPEN") return false;
      if (!needle) return true;
      return [
        task.title,
        task.definitionKey,
        task.stepKey,
        task.assignee,
        task.subject?.label,
        task.subject?.entityName,
      ].some((value) => value?.toLowerCase().includes(needle));
    });
  }, [filter, query, tasks]);

  const peopleById = useMemo(
    () => new Map(people.map((person) => [person.actorId, person])),
    [people],
  );

  return (
    <div className="pointer-events-auto w-full">
      <div className="mb-3 flex flex-wrap items-center gap-2 overflow-hidden rounded-card border border-border/70 bg-card px-2.5 py-2">
        <div className="mr-1 flex min-w-0 shrink-0 items-center gap-2">
          <h1 className="max-w-40 truncate whitespace-nowrap text-base font-semibold text-foreground">
            {widget.title || "My tasks"}
          </h1>
          {widget.hint ? <HintIcon text={widget.hint} /> : null}
        </div>
        <Segmented
          className="order-3 sm:order-none"
          size="sm"
          value={filter}
          onChange={setFilter}
          options={[
            { value: "all", label: `All ${counts.all}` },
            { value: "mine", label: `Mine ${counts.mine}` },
            { value: "available", label: `Available ${counts.available}` },
          ]}
        />
        <div className="relative order-2 ml-auto w-full sm:order-none sm:w-56">
          <Search className="pointer-events-none absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground" />
          <Input
            aria-label="Search tasks"
            className="h-8 pl-8 text-xs"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="Search tasks"
          />
        </div>
      </div>
      <div className="overflow-hidden rounded-field border border-border bg-card">
        {loading ? (
          <div className="flex items-center gap-2 px-3 py-5 text-sm text-muted-foreground">
            <Loader2 className="size-4 animate-spin" /> Loading tasks…
          </div>
        ) : error ? (
          <div className="flex items-center justify-between gap-3 px-3 py-3">
            <p className="text-sm text-destructive">{error}</p>
            <Button variant="outline" size="sm" onClick={() => void reload()}>Retry</Button>
          </div>
        ) : tasks.length === 0 ? (
          <div className="flex items-center gap-2 px-3 py-5 text-sm text-muted-foreground">
            <CheckCircle2 className="size-4" /> You have no open tasks.
          </div>
        ) : (
          <>
            {visibleTasks.length === 0 ? (
              <div className="px-3 py-8 text-center text-sm text-muted-foreground">
                No tasks match this view.
              </div>
            ) : (
              <div>
                {visibleTasks.map((task, index) => {
                  const age = timeAgo(task.status === "CLAIMED" ? task.claimedAt : task.createdAt);
                  const subjectLabel = task.subject?.label
                    || humanize(singularize(task.subject?.entityName || ""));
                  const assignee = task.assigneeId
                    ? peopleById.get(task.assigneeId)
                    : undefined;
                  return (
                    <div
                      key={task.id}
                      className={`p-3 ${index ? "border-t border-border" : ""}`}
                    >
                      <div className="flex flex-wrap items-start justify-between gap-2">
                        <div className="min-w-0">
                          <div className="truncate text-sm font-medium text-foreground">{task.title}</div>
                          <div className="mt-0.5 text-xs text-muted-foreground">
                            {humanize(task.definitionKey)} · {humanize(task.stepKey)}
                            {age ? ` · ${task.status === "CLAIMED" ? "Claimed" : "Created"} ${age}` : ""}
                          </div>
                        </div>
                        <Badge variant={task.status === "CLAIMED" ? "secondary" : "outline"}>
                          {task.status === "CLAIMED" ? "Mine" : "Available"}
                        </Badge>
                      </div>
                      <div className="mt-2 flex flex-wrap items-center justify-between gap-2">
                        <div className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1 text-xs">
                          {task.subject ? (
                            <a
                              className="font-medium text-primary hover:underline"
                              href={withBasePath(
                                `/${task.subject.kind}/${toSnakeCase(task.subject.entityName)}/${task.subject.id}`,
                              )}
                              onClick={(event) => {
                                if (
                                  event.button !== 0
                                  || event.metaKey
                                  || event.ctrlKey
                                  || event.shiftKey
                                  || event.altKey
                                ) {
                                  return;
                                }
                                event.preventDefault();
                                window.dispatchEvent(new CustomEvent("onno:action", {
                                  detail: `onno://${task.subject!.kind}/${
                                    toSnakeCase(task.subject!.entityName)
                                  }/${task.subject!.id}`,
                                }));
                              }}
                            >
                              Open {subjectLabel}
                            </a>
                          ) : null}
                          {task.assignee ? (
                            <span className="flex items-center gap-1 text-muted-foreground">
                              <PersonAvatar
                                person={assignee}
                                id={task.assigneeId}
                                display={task.assignee}
                                size="size-5"
                              />
                              {task.assignee}
                            </span>
                          ) : null}
                        </div>
                        <div className="flex flex-wrap justify-end gap-1.5">
                          {task.status === "OPEN" ? (
                            <Button
                              className="h-8"
                              size="sm"
                              onClick={() => void claim(task)}
                              disabled={busy === task.id}
                            >
                              {busy === task.id ? <Loader2 className="animate-spin" /> : null}
                              Claim
                            </Button>
                          ) : (
                            <>
                              {task.outcomes.map((outcome) => (
                                <Button
                                  className="h-8"
                                  key={outcome}
                                  size="sm"
                                  variant="outline"
                                  onClick={() => setCompleting({ task, outcome })}
                                  disabled={busy === task.id}
                                >
                                  {humanize(outcome)}
                                </Button>
                              ))}
                              <Button
                                className="h-8"
                                size="sm"
                                variant="outline"
                                onClick={() => setDelegating(task)}
                                disabled={busy === task.id}
                              >
                                <UserRoundPlus /> Delegate
                              </Button>
                            </>
                          )}
                          <Button
                            className="h-8"
                            size="sm"
                            variant="ghost"
                            onClick={() => void openHistory(task)}
                          >
                            <Clock3 /> History
                          </Button>
                        </div>
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </>
        )}
      </div>
      {completing ? (
        <ConfirmOutcomeDialog
          task={completing.task}
          outcome={completing.outcome}
          submitting={busy === completing.task.id}
          onClose={() => setCompleting(null)}
          onConfirm={() => void complete(completing.task, completing.outcome)}
        />
      ) : null}
      {historyTask ? (
        <TaskHistoryDialog
          task={historyTask}
          events={historyEvents}
          loading={historyLoading}
          peopleById={peopleById}
          onClose={() => setHistoryTask(null)}
        />
      ) : null}
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
    </div>
  );
}

function ConfirmOutcomeDialog({
  task,
  outcome,
  submitting,
  onClose,
  onConfirm,
}: {
  task: ProcessWorkItem;
  outcome: string;
  submitting: boolean;
  onClose: () => void;
  onConfirm: () => void;
}) {
  return (
    <DialogShell
      role="alertdialog"
      size="sm"
      tone="warning"
      title={`${humanize(outcome)} task?`}
      description={task.title}
      onOpenChange={(open) => { if (!open && !submitting) onClose(); }}
      dismissable={!submitting}
      footer={
        <>
          <Button variant="outline" onClick={onClose} disabled={submitting}>Cancel</Button>
          <Button onClick={onConfirm} disabled={submitting}>
            {submitting ? <Loader2 className="animate-spin" /> : null}
            Confirm {humanize(outcome)}
          </Button>
        </>
      }
    >
      <p className="text-sm text-muted-foreground">
        This completes the task and advances the process. The action cannot be undone from this inbox.
      </p>
    </DialogShell>
  );
}

function TaskHistoryDialog({
  task,
  events,
  loading,
  peopleById,
  onClose,
}: {
  task: ProcessWorkItem;
  events: ProcessWorkItemEvent[];
  loading: boolean;
  peopleById: Map<string, TaskAssigneeOption>;
  onClose: () => void;
}) {
  return (
    <DialogShell
      size="sm"
      title="Task history"
      description={task.title}
      onOpenChange={(open) => { if (!open) onClose(); }}
      footer={<Button variant="outline" onClick={onClose}>Close</Button>}
    >
      {loading ? (
        <div className="flex items-center gap-2 py-4 text-sm text-muted-foreground">
          <Loader2 className="size-4 animate-spin" /> Loading history…
        </div>
      ) : events.length === 0 ? (
        <p className="py-4 text-sm text-muted-foreground">No history is available.</p>
      ) : (
        <ol className="space-y-2">
          {events.map((event) => (
            <li key={event.id} className="flex gap-2 rounded-field border border-border px-3 py-2">
              <PersonAvatar
                person={event.actorId ? peopleById.get(event.actorId) : undefined}
                id={event.actorId}
                display={event.actor || "System"}
                size="size-7"
              />
              <div className="min-w-0">
                <div className="text-sm text-foreground">{historyEventText(event)}</div>
                <div
                  className="mt-0.5 text-xs text-muted-foreground"
                  title={new Date(event.occurredAt).toLocaleString()}
                >
                  {timeAgo(event.occurredAt)}
                  {event.reason ? ` · ${event.reason}` : ""}
                </div>
              </div>
            </li>
          ))}
        </ol>
      )}
    </DialogShell>
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
                <span className="flex items-center gap-2">
                  <PersonAvatar
                    person={option}
                    id={option.actorId}
                    display={option.display}
                    size="size-8"
                  />
                  <span className="min-w-0">
                    <span className="block truncate font-medium">{option.display}</span>
                    <span className="block truncate text-xs text-muted-foreground">
                      {option.username}
                    </span>
                  </span>
                </span>
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

function PersonAvatar({
  person,
  id,
  display,
  size,
}: {
  person?: TaskAssigneeOption;
  id?: string | null;
  display: string;
  size: string;
}) {
  const seed = id || person?.actorId || display;
  return (
    <Avatar className={`${size} border border-border`}>
      <AvatarImage
        src={person?.avatarUrl || glassAvatar(seed)}
        alt={display}
      />
      <AvatarFallback
        className="text-white"
        style={{ backgroundColor: tint(seed) }}
      >
        {initials(display)}
      </AvatarFallback>
    </Avatar>
  );
}
