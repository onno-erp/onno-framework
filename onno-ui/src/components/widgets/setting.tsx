import { useEffect, useState } from "react";
import { toast } from "../ui/toast";
import { Check } from "lucide-react";
import { api } from "../../lib/api";
import { queryClient, settingsKey } from "../../lib/query";
import { notifyError } from "../../lib/notify-error";
import type { SettingMeta } from "../../lib/types";
import { Boundary } from "../pages/boundary";
import { Card, CardContent } from "../ui/card";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { Switch } from "../ui/switch";
import { HintIcon } from "../ui/hint-icon";

/**
 * An input bound to a single framework {@code @Constant}, placed on any page via
 * {@code <Setting constant="<name>" />}. It loads the current
 * value from SettingsController ({@code settings.edit} only, {@code GET /api/settings}), renders a text/number
 * field or a switch by the constant's type, and persists edits with {@code PUT /api/settings}.
 *
 * This is the whole "settings" surface now: no bespoke editor or dedicated page — settings is just
 * an ordinary page that drops one of these per constant it wants to expose.
 */

const isBool = (t: string) => /^(boolean|Boolean)$/.test(t);
const isNum = (t: string) =>
  /^(Integer|Long|Double|Float|Short|BigDecimal|int|long|double)$/.test(t);

/** Props of {@link Setting}. */
export interface SettingProps {
  /** The `@Constant`'s name, e.g. `"Store Name"` (typed from the generated model). */
  constant: string;
  /** The field label (default: the constant's display name). */
  title?: string;
  /** Help text behind a `?` next to the label. */
  hint?: string;
  /** An explicit label that wins over `title`. */
  label?: string;
}

export function Setting(props: SettingProps) {
  return (
    <Boundary label={props.title || props.label || "Setting"}>
      <SettingBody {...props} />
    </Boundary>
  );
}

function SettingBody({ constant, title, hint, label: labelProp }: SettingProps) {
  const name = constant ?? "";
  const [meta, setMeta] = useState<SettingMeta | null>(null);
  const [value, setValue] = useState<unknown>("");
  const [baseline, setBaseline] = useState<unknown>("");
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!name) {
      setError('This setting widget has no `constant` configured.');
      return;
    }
    let cancelled = false;
    queryClient
      .fetchQuery({ queryKey: settingsKey, queryFn: () => api.getSettings() })
      .then((all) => {
        if (cancelled) return;
        const found = all.find((s) => s.name === name) ?? null;
        if (!found) {
          setError(`No @Constant named "${name}" is registered.`);
          return;
        }
        const v = isBool(found.type) ? found.value === true : found.value ?? "";
        setMeta(found);
        setValue(v);
        setBaseline(v);
      })
      .catch((e) => {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e));
      });
    return () => {
      cancelled = true;
    };
  }, [name]);

  const dirty = String(value) !== String(baseline);

  const save = async () => {
    if (!meta) return;
    setSaving(true);
    try {
      // Silent: this widget owns the failure toast (with its "Couldn't save" context).
      await api.saveSettings({ [meta.name]: value }, { silent: true });
      toast.success("Setting saved");
      setBaseline(value);
      void queryClient.invalidateQueries({ queryKey: settingsKey });
    } catch (e) {
      notifyError(e, { format: (m) => `Couldn't save: ${m}` });
    } finally {
      setSaving(false);
    }
  };

  // An explicit `label` wins, then the author's `title`; the constant's name is the fallback.
  const label = labelProp || title || meta?.name;

  return (
    <Card className="pointer-events-auto w-full">
      <CardContent className="p-5">
        <div className="flex items-center gap-1.5">
          <Label htmlFor={`setting-${name}`} className="text-sm font-medium text-foreground">
            {label}
          </Label>
          {hint ? <HintIcon text={hint} /> : null}
        </div>

        {error ? (
          <p className="mt-3 text-sm text-destructive">{error}</p>
        ) : !meta ? (
          <div className="mt-3 h-9 animate-pulse rounded-control bg-muted/40" />
        ) : (
          <div className="mt-3 flex items-center gap-2">
            {isBool(meta.type) ? (
              <Switch
                id={`setting-${name}`}
                checked={value === true}
                onCheckedChange={(v) => setValue(v)}
              />
            ) : (
              <Input
                id={`setting-${name}`}
                type={isNum(meta.type) ? "number" : "text"}
                className="w-full"
                value={(value as string) ?? ""}
                onChange={(e) =>
                  setValue(
                    isNum(meta.type)
                      ? e.target.value === ""
                        ? ""
                        : Number(e.target.value)
                      : e.target.value
                  )
                }
              />
            )}
            <button
              type="button"
              disabled={saving || !dirty}
              onClick={save}
              className="inline-flex h-9 shrink-0 items-center justify-center gap-1.5 rounded-control bg-primary px-3.5 text-sm font-medium text-primary-foreground transition-opacity hover:opacity-90 disabled:opacity-50"
            >
              <Check className="size-4" aria-hidden="true" />
              {saving ? "Saving…" : "Save"}
            </button>
          </div>
        )}
      </CardContent>
    </Card>
  );
}
