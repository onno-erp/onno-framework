import { useEffect, useMemo, useState } from "react";
import { toast } from "sonner";
import { Check, RotateCcw, Search, ShieldCheck, SlidersHorizontal } from "lucide-react";
import { api } from "@/lib/api";
import type { SettingMeta } from "@/lib/types";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";

/**
 * The app-settings editor, embedded into a page through the {@code onno-constants} block. Renders
 * the framework's {@code @Constant} values as toggles/inputs (served by SettingsController,
 * admin-only) and saves them in place via PUT /api/settings. The page provides the heading, so
 * this is just the form — letting Settings be an ordinary page composed of framework primitives.
 */

const isBool = (t: string) => /^(boolean|Boolean)$/.test(t);
const isNum = (t: string) =>
  /^(Integer|Long|Double|Float|Short|BigDecimal|int|long|double)$/.test(t);

function normalize(type: string, value: unknown): unknown {
  if (isBool(type)) return value === true;
  if (value == null) return "";
  return value;
}

function sameValue(type: string, a: unknown, b: unknown): boolean {
  return String(normalize(type, a)) === String(normalize(type, b));
}

function settingCaption(setting: SettingMeta): string {
  if (isBool(setting.type)) return "Applies immediately after saving.";
  if (isNum(setting.type)) return "Number used by app-wide business rules and defaults.";
  return "Text value used across the application.";
}

export function ConstantsEditor({ title, names }: { title?: string; names?: string[] }) {
  const [settings, setSettings] = useState<SettingMeta[] | null>(null);
  const [values, setValues] = useState<Record<string, unknown>>({});
  const [baseline, setBaseline] = useState<Record<string, unknown>>({});
  const [query, setQuery] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

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
        for (const s of list) seed[s.name] = normalize(s.type, s.value);
        setValues(seed);
        setBaseline(seed);
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
  };

  const visibleSettings = useMemo(() => {
    if (!settings) return null;
    const q = query.trim().toLowerCase();
    if (!q) return settings;
    return settings.filter((s) => `${s.displayName} ${s.name}`.toLowerCase().includes(q));
  }, [query, settings]);

  const dirtyNames = useMemo(() => {
    if (!settings) return [];
    return settings
      .filter((s) => !sameValue(s.type, values[s.name], baseline[s.name]))
      .map((s) => s.name);
  }, [baseline, settings, values]);

  const dirty = dirtyNames.length > 0;
  const showSearch = (settings?.length ?? 0) > 8;

  const save = async () => {
    setSaving(true);
    try {
      await api.saveSettings(values);
      toast.success("Settings saved");
      setBaseline(values);
    } catch (e) {
      toast.error(`Couldn't save settings: ${e instanceof Error ? e.message : String(e)}`);
    } finally {
      setSaving(false);
    }
  };

  return (
    // DivKit wraps custom blocks in pointer-events:none spans — re-assert so the controls work.
    <div className="pointer-events-auto w-full">
      <div className="mb-4 flex flex-col gap-3 border-b border-border pb-4 sm:flex-row sm:items-end sm:justify-between">
        <div className="min-w-0">
          <div className="mb-2 inline-flex items-center gap-1.5 rounded-field bg-secondary px-2 py-1 text-xs font-medium text-muted-foreground">
            <ShieldCheck className="size-3.5" aria-hidden="true" />
            Administrator controls
          </div>
          <h2 className="text-base font-semibold text-foreground">{title || "App settings"}</h2>
          <p className="mt-1 text-sm leading-relaxed text-muted-foreground">
            Manage app-wide defaults. Changes affect every user.
          </p>
        </div>
        {settings ? (
          <div className="inline-flex shrink-0 items-center gap-1.5 rounded-field border border-border px-2.5 py-1.5 text-xs text-muted-foreground">
            <SlidersHorizontal className="size-3.5" aria-hidden="true" />
            {settings.length} {settings.length === 1 ? "setting" : "settings"}
          </div>
        ) : null}
      </div>

      {error ? (
        <div className="rounded-card border border-border bg-card p-5 text-sm text-destructive">
          Failed to load settings: {error}
        </div>
      ) : !settings ? (
        <div className="space-y-2">
          <div className="h-16 animate-pulse rounded-card bg-muted/40" />
          <div className="h-16 animate-pulse rounded-card bg-muted/40" />
        </div>
      ) : settings.length === 0 ? (
        <div className="rounded-card border border-border bg-card p-5 text-sm text-muted-foreground">
          No app settings are exposed yet. Settings appear here when the application defines app-wide constants.
        </div>
      ) : (
        <>
          {showSearch ? (
            <div className="mb-3 flex h-9 items-center gap-2 rounded-field border border-input bg-muted px-3">
              <Search className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
              <input
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder="Search settings"
                className="h-full w-full bg-transparent text-sm outline-none placeholder:text-muted-foreground"
              />
            </div>
          ) : null}
          <div className="divide-y divide-border overflow-hidden rounded-card border border-border bg-card">
            {visibleSettings?.length === 0 ? (
              <div className="px-5 py-8 text-center text-sm text-muted-foreground">No settings match your search.</div>
            ) : null}
            {visibleSettings?.map((s) => (
              <div key={s.name} className="flex flex-col gap-3 px-5 py-4 sm:flex-row sm:items-center sm:justify-between">
                <div className="min-w-0">
                  <Label htmlFor={`setting-${s.name}`} className="text-sm font-medium text-foreground">
                    {s.displayName}
                  </Label>
                  <p className="mt-1 text-xs leading-relaxed text-muted-foreground">
                    {settingCaption(s)}
                  </p>
                </div>
                <div className="shrink-0 sm:min-w-56">
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
                      className="w-full sm:w-56"
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
          <div className="sticky bottom-3 mt-4 flex flex-col gap-2 rounded-card border border-border bg-card/95 p-3 shadow-lg backdrop-blur sm:flex-row sm:items-center sm:justify-between">
            <span className="text-sm text-muted-foreground">
              {dirty ? `${dirtyNames.length} unsaved ${dirtyNames.length === 1 ? "change" : "changes"}` : "All settings saved"}
            </span>
            <div className="flex gap-2">
              <button
                type="button"
                disabled={saving || !dirty}
                onClick={() => setValues(baseline)}
                className="inline-flex h-9 flex-1 items-center justify-center gap-1.5 rounded-control border border-input px-3 text-sm font-medium text-foreground transition-colors hover:bg-accent disabled:opacity-50 sm:flex-none"
              >
                <RotateCcw className="size-4" aria-hidden="true" />
                Revert
              </button>
              <button
                type="button"
                disabled={saving || !dirty}
                onClick={save}
                className="inline-flex h-9 flex-1 items-center justify-center gap-1.5 rounded-control bg-primary px-3.5 text-sm font-medium text-primary-foreground transition-opacity hover:opacity-90 disabled:opacity-50 sm:flex-none"
              >
                <Check className="size-4" aria-hidden="true" />
                {saving ? "Saving…" : "Save changes"}
              </button>
            </div>
          </div>
        </>
      )}
    </div>
  );
}
