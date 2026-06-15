import { useEffect, useState } from "react";
import { toast } from "sonner";
import { Check } from "lucide-react";
import { api } from "@/lib/api";
import type { SettingMeta } from "@/lib/types";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";

/**
 * The app-settings editor, embedded into a page through the {@code onec-constants} block. Renders
 * the framework's {@code @Constant} values as toggles/inputs (served by SettingsController,
 * admin-only) and saves them in place via PUT /api/settings. The page provides the heading, so
 * this is just the form — letting Settings be an ordinary page composed of framework primitives.
 */

const isBool = (t: string) => /^(boolean|Boolean)$/.test(t);
const isNum = (t: string) =>
  /^(Integer|Long|Double|Float|Short|BigDecimal|int|long|double)$/.test(t);

export function ConstantsEditor({ title, names }: { title?: string; names?: string[] }) {
  const [settings, setSettings] = useState<SettingMeta[] | null>(null);
  const [values, setValues] = useState<Record<string, unknown>>({});
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(false);

  // A page can drop just a subset of constants (e.g. one toggle) by naming them; with no names
  // the whole editor is shown. Keying on the joined names keeps the effect stable across renders.
  const namesKey = names && names.length ? names.join(",") : "";
  useEffect(() => {
    let cancelled = false;
    const wanted = namesKey ? new Set(namesKey.split(",")) : null;
    api
      .getSettings()
      .then((all) => {
        if (cancelled) return;
        const list = wanted ? all.filter((s) => wanted.has(s.name)) : all;
        setSettings(list);
        const seed: Record<string, unknown> = {};
        for (const s of list) seed[s.name] = isBool(s.type) ? s.value === true : s.value ?? "";
        setValues(seed);
      })
      .catch((e) => {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e));
      });
    return () => {
      cancelled = true;
    };
  }, [namesKey]);

  const set = (name: string, val: unknown) => {
    setValues((prev) => ({ ...prev, [name]: val }));
    setDirty(true);
  };

  const save = async () => {
    setSaving(true);
    try {
      await api.saveSettings(values);
      toast.success("Settings saved");
      setDirty(false);
    } catch (e) {
      toast.error(`Couldn't save settings: ${e instanceof Error ? e.message : String(e)}`);
    } finally {
      setSaving(false);
    }
  };

  return (
    // DivKit wraps custom blocks in pointer-events:none spans — re-assert so the controls work.
    <div className="pointer-events-auto w-full">
      {title ? (
        <h2 className="mb-2 text-sm font-semibold text-foreground">{title}</h2>
      ) : null}

      {error ? (
        <div className="rounded-xl border border-border bg-card p-5 text-sm text-destructive">
          Failed to load settings: {error}
        </div>
      ) : !settings ? (
        <div className="space-y-2">
          <div className="h-16 animate-pulse rounded-xl bg-muted/40" />
          <div className="h-16 animate-pulse rounded-xl bg-muted/40" />
        </div>
      ) : settings.length === 0 ? (
        <div className="rounded-xl border border-border bg-card p-5 text-sm text-muted-foreground">
          No settings defined yet. Add a <code>@Constant</code> to your app and it shows up here.
        </div>
      ) : (
        <>
          <div className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
            {settings.map((s) => (
              <div key={s.name} className="flex items-center justify-between gap-4 px-5 py-4">
                <div className="min-w-0">
                  <Label htmlFor={`setting-${s.name}`} className="text-sm font-medium text-foreground">
                    {s.displayName}
                  </Label>
                  <p className="text-xs text-muted-foreground">
                    {isBool(s.type) ? "On / off toggle" : s.type}
                  </p>
                </div>
                <div className="shrink-0">
                  {isBool(s.type) ? (
                    <Switch
                      id={`setting-${s.name}`}
                      checked={values[s.name] === true}
                      onCheckedChange={(v) => set(s.name, v)}
                    />
                  ) : (
                    <Input
                      id={`setting-${s.name}`}
                      type={isNum(s.type) ? "number" : "text"}
                      className="w-56"
                      value={(values[s.name] as string) ?? ""}
                      onChange={(e) =>
                        set(s.name, isNum(s.type) ? (e.target.value === "" ? "" : Number(e.target.value)) : e.target.value)
                      }
                    />
                  )}
                </div>
              </div>
            ))}
          </div>
          <div className="mt-4 flex justify-end">
            <button
              type="button"
              disabled={saving || !dirty}
              onClick={save}
              className="inline-flex items-center gap-1.5 rounded-lg bg-secondary px-3.5 py-2 text-sm font-medium text-foreground transition-colors hover:bg-accent disabled:opacity-50"
            >
              <Check className="size-4" aria-hidden="true" />
              {saving ? "Saving…" : "Save changes"}
            </button>
          </div>
        </>
      )}
    </div>
  );
}
