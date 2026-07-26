import { useCallback, useEffect, useState } from "react";
import { CheckCircle2, Loader2, RefreshCw, UserRound } from "lucide-react";
import { toast } from "sonner";
import { api } from "@/lib/api";
import type { DashboardWidgetMeta, ProcessWorkItem } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { HintIcon } from "@/components/ui/hint-icon";

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

  return (
    <Card className="pointer-events-auto w-full">
      <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-3">
        <div className="flex items-center gap-1.5">
          <CardTitle className="text-base">{widget.title || "My tasks"}</CardTitle>
          {widget.hint ? <HintIcon text={widget.hint} /> : null}
        </div>
        <Button variant="ghost" size="icon" onClick={() => void reload()} aria-label="Refresh tasks">
          <RefreshCw className={loading ? "animate-spin" : ""} />
        </Button>
      </CardHeader>
      <CardContent className="space-y-3">
        {loading ? (
          <div className="flex items-center gap-2 py-5 text-sm text-muted-foreground">
            <Loader2 className="size-4 animate-spin" /> Loading tasks…
          </div>
        ) : error ? (
          <p className="py-3 text-sm text-destructive">{error}</p>
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
              <div className="mt-4 flex flex-wrap gap-2">
                {task.status === "OPEN" ? (
                  <Button size="sm" onClick={() => void claim(task)} disabled={busy === task.id}>
                    {busy === task.id ? <Loader2 className="animate-spin" /> : null}
                    Claim
                  </Button>
                ) : (
                  task.outcomes.map((outcome) => (
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
                  ))
                )}
              </div>
            </div>
          ))
        )}
      </CardContent>
    </Card>
  );
}
