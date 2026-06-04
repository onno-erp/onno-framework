import { useEffect, useState } from "react";
import { toast } from "sonner";
import { Check } from "lucide-react";
import { api } from "@/lib/api";
import type { SettingMeta } from "@/lib/types";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";

/**
 * App settings, backed by the framework's @Constant values (served by SettingsController,
 * admin-only). Booleans render as a toggle switch; strings/numbers as an input. Changes are
 * collected locally and persisted on Save via PUT /api/settings.
 */

const isBool = (t: string) => /^(boolean|Boolean)$/.test(t);
const isNum = (t: string) =>
  /^(Integer|Long|Double|Float|Short|BigDecimal|int|long|double)$/.test(t);

export function SettingsPage() {
  const [settings, setSettings] = useState<SettingMeta[] | null>(null);
  const [values, setValues] = useState<Record<string, unknown>>({});
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(false);

  useEffect(() => {
    let cancelled = false;
    api
      .getSettings()
      .then((list) => {
        if (cancelled) return;
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
  }, []);

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
    <div className="mx-auto w-full max-w-2xl px-4 py-5 sm:px-6">
      <h1 className="mb-1 text-xl font-semibold text-foreground">Settings</h1>
      <p className="mb-5 text-sm text-muted-foreground">
        App-wide configuration. Changes apply once you save.
      </p>

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
          <div className="mt-5 flex justify-end">
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
