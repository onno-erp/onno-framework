import {
  Badge,
  Button,
  registerWidget,
  text as t,
  toast,
  useCallback,
  useEffect,
  useRef,
  useState,
  useTranslate,
  type WidgetProps,
} from "@onno/widget-sdk";
import { Download, FileSpreadsheet, FileText, RotateCcw, Upload } from "lucide-react";

/** One print form and its template state (GET /api/print/templates). */
type TemplateInfo = {
  kind: string;
  name: string;
  key: string;
  label: string;
  format: "docx" | "xlsx";
  uploaded: boolean;
  fileName: string | null;
  uploadedBy: string | null;
  uploadedAt: string | null;
  url: string;
};
type TemplateList = { uploadsEnabled: boolean; forms: TemplateInfo[] };

const msg = (key: string, params?: Record<string, string | number>) => t(`print.templates.${key}`, params);

function csrfToken(): string | undefined {
  const part = document.cookie.split(";").map((s) => s.trim()).find((s) => s.startsWith("XSRF-TOKEN="));
  return part ? decodeURIComponent(part.slice("XSRF-TOKEN=".length)) : undefined;
}

async function send(url: string, init: RequestInit): Promise<Response> {
  const csrf = csrfToken();
  const response = await fetch(url, {
    credentials: "same-origin",
    ...init,
    headers: { ...(init.headers ?? {}), ...(csrf ? { "X-XSRF-TOKEN": csrf } : {}) },
  });
  if (!response.ok) {
    let message = `${response.status} ${response.statusText}`;
    try {
      const body = await response.json();
      message = body.message || body.error || message;
    } catch {
      /* not JSON */
    }
    throw new Error(message);
  }
  return response;
}

function download(url: string) {
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.rel = "noopener";
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
}

function TemplateRow({ form, uploadsEnabled, reload }: { form: TemplateInfo; uploadsEnabled: boolean; reload: () => Promise<void> }) {
  const input = useRef<HTMLInputElement>(null);
  const [busy, setBusy] = useState(false);
  const Icon = form.format === "xlsx" ? FileSpreadsheet : FileText;

  const upload = async (file: File) => {
    setBusy(true);
    try {
      const body = new FormData();
      body.append("file", file);
      await send(form.url, { method: "PUT", body });
      toast.success(msg("uploadedToast", { form: form.label }));
      await reload();
    } catch (error) {
      toast.error((error as Error).message);
    } finally {
      setBusy(false);
      if (input.current) input.current.value = "";
    }
  };

  const reset = async () => {
    setBusy(true);
    try {
      await send(form.url, { method: "DELETE" });
      toast.success(msg("resetToast", { form: form.label }));
      await reload();
    } catch (error) {
      toast.error((error as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const uploadedAt = form.uploadedAt ? new Date(form.uploadedAt).toLocaleString() : "";
  return (
    <li className="flex flex-wrap items-center gap-3 border-b border-border px-4 py-3 last:border-b-0">
      <Icon className="size-5 shrink-0 text-muted-foreground" aria-hidden="true" />
      <div className="min-w-0 flex-1 basis-48">
        <div className="truncate text-sm font-medium">{form.label}</div>
        <div className="truncate text-xs text-muted-foreground">
          {form.name.replace(/_/g, " ")} · .{form.format}
        </div>
      </div>
      <div className="min-w-0 basis-56 text-xs text-muted-foreground">
        {form.uploaded ? (
          <span className="flex flex-col gap-0.5">
            <Badge variant="secondary" className="w-fit">{form.fileName}</Badge>
            <span>{msg("uploaded", { date: uploadedAt, user: form.uploadedBy ?? "—" })}</span>
          </span>
        ) : (
          <Badge variant="outline">{msg("builtIn")}</Badge>
        )}
      </div>
      <div className="flex shrink-0 flex-wrap gap-2">
        <Button size="toolbar" variant="subtle" onClick={() => download(form.url)}>
          <Download />
          {msg("download")}
        </Button>
        {form.uploaded && (
          <Button size="toolbar" variant="ghost" onClick={() => download(`${form.url}?builtin=true`)}>
            <Download />
            {msg("downloadBuiltIn")}
          </Button>
        )}
        {uploadsEnabled && (
          <>
            <input
              ref={input}
              type="file"
              hidden
              accept={`.${form.format}`}
              onChange={(e) => {
                const file = e.target.files?.[0];
                if (file) void upload(file);
              }}
            />
            <Button size="toolbar" variant="subtle" disabled={busy} onClick={() => input.current?.click()}>
              <Upload />
              {msg("upload")}
            </Button>
            {form.uploaded && (
              <Button size="toolbar" variant="ghost" disabled={busy} onClick={() => void reset()}>
                <RotateCcw />
                {msg("reset")}
              </Button>
            )}
          </>
        )}
      </div>
    </li>
  );
}

/**
 * Template management for print forms: download each form's template, upload an edited copy (it
 * replaces the built-in one for every user), or reset to the built-in file. Place it on an admin
 * page with {@code b.widget("Print templates").type("printTemplates")}; the API enforces
 * onno.print.templates.manage-roles.
 */
export function PrintTemplates({ widget }: WidgetProps) {
  useTranslate();
  const [data, setData] = useState<TemplateList | null>(null);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    try {
      const response = await send("/api/print/templates", { method: "GET" });
      setData((await response.json()) as TemplateList);
      setError("");
    } catch {
      setError(msg("loadFailed"));
    }
  }, []);
  useEffect(() => {
    void load();
  }, [load]);

  if (error) return <p role="alert" className="text-sm text-destructive">{error}</p>;
  if (!data) return null;
  return (
    <section className="flex flex-col gap-3">
      <h3 className="text-sm font-medium">{widget.title || msg("title")}</h3>
      <p className="text-xs text-muted-foreground">{data.uploadsEnabled ? msg("hint") : msg("disabled")}</p>
      {data.forms.length === 0 ? (
        <p className="text-sm text-muted-foreground">{msg("empty")}</p>
      ) : (
        <ul className="rounded-panel border border-border bg-card">
          {data.forms.map((form) => (
            <TemplateRow key={`${form.kind}/${form.name}/${form.key}`} form={form} uploadsEnabled={data.uploadsEnabled} reload={load} />
          ))}
        </ul>
      )}
    </section>
  );
}

registerWidget("printTemplates", PrintTemplates);
