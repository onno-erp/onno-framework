import { useEffect, useState } from "react";
import { toast } from "sonner";
import { Check } from "lucide-react";
import { api } from "@/lib/api";
import type { DashboardWidgetMeta, SettingMeta } from "@/lib/types";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { HintIcon } from "@/components/ui/hint-icon";

/**
 * An input bound to a single framework {@code @Constant}, placed on any page via
 * {@code .widget(...).type("setting").config("constant", "<logicalName>")}. It loads the current
 * value from SettingsController (admin-only, {@code GET /api/settings}), renders a text/number
 * field or a switch by the constant's type, and persists edits with {@code PUT /api/settings}.
 *
 * This is the whole "settings" surface now: no bespoke editor or dedicated page — settings is just
 * an ordinary page that drops one of these per constant it wants to expose.
 */

const isBool = (t: string) => /^(boolean|Boolean)$/.test(t);
const isNum = (t: string) =>
  /^(Integer|Long|Double|Float|Short|BigDecimal|int|long|double)$/.test(t);

export function SettingWidget({ widget }: { widget: DashboardWidgetMeta }) {
  const cfg = widget.extraConfig ?? {};
  const name = cfg.constant ?? "";
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
    api
      .getSettings()
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
      await api.saveSettings({ [meta.name]: value });
      toast.success("Setting saved");
      setBaseline(value);
    } catch (e) {
      toast.error(`Couldn't save: ${e instanceof Error ? e.message : String(e)}`);
    } finally {
      setSaving(false);
    }
  };

  // The page author's widget title is the intended label; fall back to an explicit config label or
  // the constant's humanized name only when the widget has no title.
  const label = cfg.label || widget.title || meta?.displayName;

  return (
    <Card className="pointer-events-auto w-full">
      <CardContent className="p-5">
        <div className="flex items-center gap-1.5">
          <Label htmlFor={`setting-${name}`} className="text-sm font-medium text-foreground">
            {label}
          </Label>
          {widget.hint ? <HintIcon text={widget.hint} /> : null}
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
