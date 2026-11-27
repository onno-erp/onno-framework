import type { IconSource } from "../../lib/icons";
import { openRoute } from "../../lib/navigation";
import { useCallback, useRef, useState } from "react";
import { Ellipsis, Loader2 } from "lucide-react";
import { toast } from "../ui/toast";
import { Popover, PopoverContent, PopoverTrigger } from "../ui/popover";
import {
  CommandFormDialog,
  type CommandFormItem,
  type CommandFormValues,
} from "./command-form-dialog";
import { Icon } from "../../lib/icons";
import { api } from "../../lib/api";
import { useCommandFeedback, type CommandFeedbackHandlers } from "../../lib/command-feedback";
import { invalidateEntity } from "../../lib/query";
import { cn } from "../../lib/utils";

/**
 * The record header's action cluster: {@code "primary"}-placed items render as inline buttons and
 * {@code "menu"}-placed ones tuck into an overflow (⋯) dropdown. Async items — Post / Unpost and
 * the entity's record commands ({@code onno://command/{kind}/{name}/{key}/{id}}) — run here with an
 * in-button loading state; navigation actions and Delete open through {@link openRoute}, like the
 * rest of the app.
 */

export type RecordAction = {
  /** The command or record action this item runs (its customization key). */
  key?: string;
  label: string;
  icon?: IconSource;
  url: string;
  /** `"danger"` for a destructive action, `"accent"` for a promoted one, else neutral. */
  tone?: "normal" | "accent" | "danger";
  /** `"primary"`: an inline button; `"menu"`: the overflow menu. */
  placement?: "primary" | "menu";
  /** A command that may not run on this record now — render greyed and inert. */
  disabled?: boolean;
  /** A command's description: the dialog's subtitle. */
  description?: string;
  /** Inputs a command collects in a modal before it POSTs — scalar fields and/or row groups. */
  form?: CommandFormItem[];
  /** The form's opening values are fetched from the server per open (the command's defaults). */
  dynamicForm?: boolean;
  /** A command's group ("Change status"): the menu lists grouped items under its heading. */
  group?: string;
};
export function leavesPane(url: string): boolean {
  return /^onno:\/\/(catalogs|documents)\/[^/?]+\?(.*&)?focus=/.test(url);
}

// Items the cluster runs itself (with a loading state) rather than routing through openRoute:
// document posting and record commands. Everything else (edit/duplicate/delete) is navigation
// or needs the confirm modal, so it routes as before.
function isAsync(url: string): boolean {
  const r = url.replace("onno://", "");
  return r.startsWith("post/") || r.startsWith("unpost/") || r.startsWith("command/");
}

// The defaults fetch source of a command url ("onno://command/{kind}/{name}/{key}/{id}"), or
// undefined for any other shape (post/unpost/navigation never declare dynamic forms).
function commandSource(url: string): { kind: string; name: string; key: string; id?: string } | undefined {
  const r = url.replace("onno://", "");
  if (!r.startsWith("command/")) return undefined;
  const [, kind, name, key, id] = r.split("/"); // [command, kind, name, key, id]
  return { kind, name, key, id };
}

// Run a post/unpost/command url and apply its result. A failure rejects; the caller reports it
// once (the cluster, or the command dialog). `inputs` carries a command dialog's submitted values
// (commands only).
async function runAsync(url: string, inputs: CommandFormValues | undefined, feedback: CommandFeedbackHandlers): Promise<void> {
  const r = url.replace("onno://", "");
  if (r.startsWith("post/") || r.startsWith("unpost/")) {
    const unpost = r.startsWith("unpost/");
    const [, name, id] = r.split("/"); // [post|unpost, name, id]
    if (unpost) await api.unpostDocument(name, id);
    else await api.postDocument(name, id);
    toast.success(unpost ? "Document unposted" : "Document posted");
    return;
  }
  const [, kind, name, key, id] = r.split("/"); // [command, kind, name, key, id]
  const result = await api.runCommand(kind, name, key, id, inputs);
  feedback.applyResult(result, { refresh: () => invalidateEntity(`${kind}/${name}`, id ? [id] : undefined) });
}

// Exported so the combined record surface (RecordForm) can render the same cluster
// inline — the form is already React, so it needs no custom-element mount.
export function RecordActions({ items }: { items: RecordAction[] }) {
  const [open, setOpen] = useState(false);
  const [pending, setPending] = useState<Set<string>>(new Set());
  const skipReturnFocus = useRef(false);
  // A command with inputs waiting for its dialog; submit runs it with the values.
  const [formFor, setFormFor] = useState<RecordAction | null>(null);
  const feedback = useCommandFeedback();

  const run = useCallback((it: RecordAction, inputs?: CommandFormValues): Promise<void> | void => {
    if (!isAsync(it.url)) {
      openRoute(it.url);
      return;
    }
    if (it.form?.length && !inputs) {
      setFormFor(it);
      return;
    }
    setPending((s) => {
      if (s.has(it.url)) return s; // already running
      const n = new Set(s);
      n.add(it.url);
      return n;
    });
    return runAsync(it.url, inputs, feedback)
      .catch((error) => {
        feedback.notifyError(error);
      })
      .finally(() =>
        setPending((s) => {
          if (!s.has(it.url)) return s;
          const n = new Set(s);
          n.delete(it.url);
          return n;
        })
      );
  }, [feedback]);

  if (items.length === 0) return null;
  const inline = items.filter((a) => a.placement !== "menu");
  // Ungrouped items first, then each group under its heading (in first-seen order).
  const menuItems = items.filter((a) => a.placement === "menu");
  const menuGroups: { group: string | undefined; items: RecordAction[] }[] = [];
  for (const it of menuItems) {
    const existing = menuGroups.find((g) => g.group === it.group);
    if (existing) existing.items.push(it);
    else menuGroups.push({ group: it.group, items: [it] });
  }
  menuGroups.sort((a, b) => (a.group ? 1 : 0) - (b.group ? 1 : 0));
  const menu = menuItems;

  return (
    <div className="flex items-center gap-2">
      {inline.map((a) => {
        const busy = pending.has(a.url);
        return (
          <button
            key={a.url}
            type="button"
            disabled={busy || a.disabled}
            onClick={() => run(a)}
            className={cn(
              "inline-flex h-9 shrink-0 items-center gap-1.5 whitespace-nowrap rounded-control px-3 text-sm font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-60",
              a.tone === "accent"
                  ? "bg-primary text-primary-foreground hover:bg-primary/90"
                  : a.tone === "danger"
                    ? "bg-secondary text-destructive hover:bg-accent"
                    : "bg-secondary text-foreground hover:bg-accent"
            )}
            title={a.label}
          >
            {busy ? (
              <Loader2 className="size-4 animate-spin" aria-hidden="true" />
            ) : a.icon ? (
              <Icon name={a.icon} size={16} />
            ) : null}
            {a.label}
          </button>
        );
      })}

      {menu.length ? (
        <Popover open={open} onOpenChange={setOpen}>
          <PopoverTrigger asChild>
            <button
              type="button"
              aria-label="More actions"
              className="inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-control border border-border bg-secondary text-foreground transition-colors hover:bg-accent"
            >
              <Ellipsis className="size-4" aria-hidden="true" />
            </button>
          </PopoverTrigger>
          <PopoverContent
            align="end"
            className="max-h-[70vh] w-56 overflow-y-auto p-1"
            onCloseAutoFocus={(event) => {
              if (!skipReturnFocus.current) return;
              skipReturnFocus.current = false;
              event.preventDefault();
            }}
          >
            {menuGroups.map(({ group, items: groupItems }) => (
              <div key={group ?? ""} className={cn(group && "mt-1 border-t border-border pt-1")}>
                {group ? (
                  <div className="px-2.5 pb-0.5 pt-1 text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
                    {group}
                  </div>
                ) : null}
            {groupItems.map((it) => {
              const busy = pending.has(it.url);
              return (
                <button
                  key={it.url}
                  type="button"
                  disabled={busy || it.disabled}
                  onClick={() => {
                    if (isAsync(it.url)) {
                      run(it); // keep the menu open so the spinner is visible
                    } else {
                      skipReturnFocus.current = leavesPane(it.url);
                      setOpen(false);
                      openRoute(it.url);
                    }
                  }}
                  className={cn(
                    // rounded-field to match the context menu / select dropdowns (rounded-control
                    // is a full pill and reads as a lozenge on a menu row).
                    "flex w-full items-center gap-2 rounded-field px-2.5 py-1.5 text-left text-sm transition-colors hover:bg-accent disabled:opacity-60",
                    it.tone === "danger" ? "text-destructive hover:text-destructive" : "text-foreground"
                  )}
                >
                  {busy ? (
                    <Loader2 className="size-4 animate-spin" aria-hidden="true" />
                  ) : it.icon ? (
                    <Icon name={it.icon} size={16} />
                  ) : null}
                  {it.label}
                </button>
              );
            })}
              </div>
            ))}
          </PopoverContent>
        </Popover>
      ) : null}

      {formFor ? (
        <CommandFormDialog
          title={formFor.label}
          fields={formFor.form ?? []}
          description={formFor.description}
          defaultsSource={formFor.dynamicForm ? commandSource(formFor.url) : undefined}
          onClose={() => setFormFor(null)}
          onSubmit={(values) => runAsync(formFor.url, values, feedback).then(() => setOpen(false))}
        />
      ) : null}
    </div>
  );
}
