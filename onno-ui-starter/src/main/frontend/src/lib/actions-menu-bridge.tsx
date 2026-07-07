import { useCallback, useState, useSyncExternalStore } from "react";
import { createPortal } from "react-dom";
import { Ellipsis, Loader2 } from "lucide-react";
import { toast } from "sonner";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { ActionFormDialog, type ActionFormField } from "@/components/action-form-dialog";
import { DynamicLucide } from "@/lib/icon-bridge";
import { api } from "@/lib/api";
import { cn } from "@/lib/utils";

/**
 * Bridges DivKit's {@code div-custom} block of type {@code onno-actions-menu} to the React
 * detail-header action cluster: the {@code "primary"}-placed actions render as inline buttons and
 * the {@code "menu"}-placed ones tuck into an overflow (⋯) dropdown. The async actions — Post /
 * Unpost and custom server actions — run here with an in-button loading state (spinner + disabled),
 * just like the list's toolbar/row buttons; navigation actions (Edit / Duplicate) and Delete route
 * through the same {@code onno:action} host event the rest of the app uses. Mirrors the other
 * bridges: a live custom element registers in a store and {@link ActionsMenuPortals} (mounted
 * inside the app's Router/providers) portals the cluster into it.
 */

type ActionItem = {
  label: string;
  icon?: string;
  url: string;
  tone?: string;
  placement?: string;
  /** Form fields the action collects in a modal before it POSTs (ActionSpec.form). */
  form?: ActionFormField[];
};
type Mount = { id: number; el: HTMLElement; items: ActionItem[] };

let mounts: Mount[] = [];
const listeners = new Set<() => void>();
let seq = 0;

function emit() {
  for (const l of listeners) l();
}
function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}
function getSnapshot(): Mount[] {
  return mounts;
}

class OnnoActionsMenuElement extends HTMLElement {
  private readonly _id = ++seq;
  private _items: ActionItem[] = [];

  // DivKit assigns custom_props.items as a property (live array, not a string attribute).
  set items(value: ActionItem[] | null) {
    this._items = Array.isArray(value) ? value : [];
    this.sync();
  }
  get items(): ActionItem[] {
    return this._items;
  }

  connectedCallback() {
    this.applyHostStyle();
    this.sync();
  }
  disconnectedCallback() {
    if (mounts.some((m) => m.el === this)) {
      mounts = mounts.filter((m) => m.el !== this);
      emit();
    }
  }

  // DivKit renders a custom block with no action as a collapsed inline host with
  // pointer-events:none (so clicks fall through). Re-assert an interactive, content-sized box
  // (!important, re-applied each sync) so the buttons receive clicks and the cluster hugs them.
  private applyHostStyle() {
    this.style.setProperty("display", "inline-flex", "important");
    this.style.setProperty("align-items", "center", "important");
    this.style.setProperty("pointer-events", "auto", "important");
  }

  private sync() {
    if (!this.isConnected) return;
    this.applyHostStyle();
    const existing = mounts.find((m) => m.el === this);
    if (existing) existing.items = this._items;
    else mounts = [...mounts, { id: this._id, el: this, items: this._items }];
    mounts = [...mounts];
    emit();
  }
}

let defined = false;
export function defineActionsMenuElement() {
  if (defined || typeof customElements === "undefined") return;
  if (!customElements.get("onno-actions-menu")) {
    customElements.define("onno-actions-menu", OnnoActionsMenuElement);
  }
  defined = true;
}
defineActionsMenuElement();

/** The DivKit {@code customComponents} entry: custom_type → element tag. */
export const ACTIONS_MENU_CUSTOM_COMPONENTS = new Map<string, { element: string }>([
  ["onno-actions-menu", { element: "onno-actions-menu" }],
]);

function fire(url: string) {
  window.dispatchEvent(new CustomEvent("onno:action", { detail: url }));
}

// Actions the cluster runs itself (with a loading state) rather than routing through onno:action:
// document posting and custom server actions. Everything else (edit/duplicate/delete) is navigation
// or needs the confirm modal, so it routes as before.
function isAsync(url: string): boolean {
  const r = url.replace("onno://", "");
  return r.startsWith("post/") || r.startsWith("unpost/") || r.startsWith("action/");
}

// Run a post/unpost/custom-action url and apply its result. Errors self-toast via the api layer.
// `inputs` carries an action-form dialog's submitted values (custom actions only).
async function runAsync(url: string, inputs?: Record<string, string>): Promise<void> {
  const r = url.replace("onno://", "");
  if (r.startsWith("post/") || r.startsWith("unpost/")) {
    const unpost = r.startsWith("unpost/");
    const [, name, id] = r.split("/"); // [post|unpost, name, id]
    if (unpost) await api.unpostDocument(name, id);
    else await api.postDocument(name, id);
    toast.success(unpost ? "Document unposted" : "Document posted");
    return;
  }
  const [, kind, name, key, id] = r.split("/"); // [action, kind, name, key, id]
  const result = await api.runAction(kind, name, key, id, inputs);
  if (result?.message) toast.success(result.message);
  if (result?.navigate) fire(result.navigate);
}

function ActionsCluster({ items }: { items: ActionItem[] }) {
  const [open, setOpen] = useState(false);
  const [pending, setPending] = useState<Set<string>>(new Set());
  // A form-declaring action waiting for its dialog input; submit runs it with the values.
  const [formFor, setFormFor] = useState<ActionItem | null>(null);
  const [formBusy, setFormBusy] = useState(false);

  const run = useCallback((it: ActionItem, inputs?: Record<string, string>): Promise<void> | void => {
    if (!isAsync(it.url)) {
      fire(it.url);
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
    return runAsync(it.url, inputs)
      .catch(() => {})
      .finally(() =>
        setPending((s) => {
          if (!s.has(it.url)) return s;
          const n = new Set(s);
          n.delete(it.url);
          return n;
        })
      );
  }, []);

  if (items.length === 0) return null;
  const inline = items.filter((a) => a.placement !== "menu");
  const menu = items.filter((a) => a.placement === "menu");

  return (
    <div className="flex items-center gap-2">
      {inline.map((a) => {
        const busy = pending.has(a.url);
        return (
          <button
            key={a.url}
            type="button"
            disabled={busy}
            onClick={() => run(a)}
            className={cn(
              "inline-flex h-9 shrink-0 items-center gap-1.5 whitespace-nowrap rounded-control px-3 text-sm font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-60",
              a.tone === "primary"
                ? "bg-[hsl(var(--success))] text-[hsl(var(--success-foreground))] hover:bg-[hsl(var(--success))]/90"
                : a.tone === "accent"
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
              <DynamicLucide name={a.icon} size={16} />
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
          <PopoverContent align="end" className="w-48 p-1">
            {menu.map((it) => {
              const busy = pending.has(it.url);
              return (
                <button
                  key={it.url}
                  type="button"
                  disabled={busy}
                  onClick={() => {
                    if (isAsync(it.url)) {
                      run(it); // keep the menu open so the spinner is visible
                    } else {
                      setOpen(false);
                      fire(it.url);
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
                    <DynamicLucide name={it.icon} size={16} />
                  ) : null}
                  {it.label}
                </button>
              );
            })}
          </PopoverContent>
        </Popover>
      ) : null}

      {formFor ? (
        <ActionFormDialog
          title={formFor.label}
          fields={formFor.form ?? []}
          busy={formBusy}
          onClose={() => setFormFor(null)}
          onSubmit={(values) => {
            setFormBusy(true);
            void Promise.resolve(run(formFor, values)).finally(() => {
              setFormBusy(false);
              setFormFor(null);
              setOpen(false);
            });
          }}
        />
      ) : null}
    </div>
  );
}

/** Portals every live {@code <onno-actions-menu>} to its React cluster. Mount once, inside the Router. */
export function ActionsMenuPortals() {
  const list = useSyncExternalStore(subscribe, getSnapshot);
  return <>{list.map((m) => createPortal(<ActionsCluster items={m.items} />, m.el, String(m.id)))}</>;
}
