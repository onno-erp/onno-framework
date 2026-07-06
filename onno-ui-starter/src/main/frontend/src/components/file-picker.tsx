import { useRef, useState } from "react";
import { toast } from "sonner";
import { FileUp, Loader2, Paperclip, Trash2, Upload } from "lucide-react";
import { uploadMedia } from "@/lib/api";
import { cn } from "@/lib/utils";

/**
 * A generic file input for an attribute whose field hint sets {@code .widget("file")}. The chosen
 * file (any type) is streamed to {@code POST /api/media} (see MediaController) and only the returned
 * reference URL is stored in the String attribute — the same attach-by-URL shape the rest of the
 * framework already understands, now reachable for raw bytes too. Drop a file or click to choose.
 */

// Mirrors the server's onno.media.max-file-size default (10 MB); the server validates authoritatively.
const MAX_BYTES = 10 * 1024 * 1024;

// The display label for an already-attached file: the leaf of its stored URL.
function leafOf(url: string): string {
  try {
    const path = url.split(/[?#]/)[0];
    const leaf = path.substring(path.lastIndexOf("/") + 1);
    return leaf || url;
  } catch {
    return url;
  }
}

export function FilePicker({
  value,
  onChange,
}: {
  value?: string;
  onChange: (val: string) => void;
}) {
  const inputRef = useRef<HTMLInputElement | null>(null);
  const [dragging, setDragging] = useState(false);
  const [busy, setBusy] = useState(false);
  const hasFile = typeof value === "string" && value.length > 0;

  const accept = async (file: File | undefined | null) => {
    if (!file || busy) return;
    if (file.size > MAX_BYTES) {
      toast.error(`"${file.name}" is too large (max ${Math.round(MAX_BYTES / 1024 / 1024)} MB).`);
      return;
    }
    setBusy(true);
    try {
      const stored = await uploadMedia(file);
      onChange(stored.url);
    } catch {
      toast.error(`Couldn't upload "${file.name}".`);
    } finally {
      setBusy(false);
    }
  };

  const openPicker = () => {
    if (!busy) inputRef.current?.click();
  };

  return (
    <div className="grid gap-2">
      <input
        ref={inputRef}
        type="file"
        className="hidden"
        onChange={(e) => {
          void accept(e.target.files?.[0]);
          e.target.value = "";
        }}
      />
      {hasFile ? (
        <div className="flex items-center gap-2 rounded-lg border border-border bg-muted/30 px-3 py-2 text-sm">
          <Paperclip className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
          <a
            href={value}
            target="_blank"
            rel="noreferrer"
            className="min-w-0 flex-1 truncate text-foreground hover:underline"
            title={value}
          >
            {leafOf(value!)}
          </a>
          <button
            type="button"
            onClick={openPicker}
            disabled={busy}
            aria-label="Replace file"
            className="inline-flex items-center gap-1 rounded-md px-1.5 py-1 text-xs text-muted-foreground transition-colors hover:bg-accent hover:text-foreground disabled:opacity-50"
          >
            {busy ? <Loader2 className="size-3.5 animate-spin" /> : <Upload className="size-3.5" />}
          </button>
          <button
            type="button"
            onClick={() => onChange("")}
            disabled={busy}
            aria-label="Remove file"
            className="inline-flex items-center rounded-md px-1.5 py-1 text-xs text-muted-foreground transition-colors hover:bg-accent hover:text-destructive disabled:opacity-50"
          >
            <Trash2 className="size-3.5" aria-hidden="true" />
          </button>
        </div>
      ) : (
        <div
          role="button"
          tabIndex={0}
          onClick={openPicker}
          onKeyDown={(e) => {
            if (e.key === "Enter" || e.key === " ") {
              e.preventDefault();
              openPicker();
            }
          }}
          onDragOver={(e) => {
            e.preventDefault();
            setDragging(true);
          }}
          onDragLeave={(e) => {
            if (!e.currentTarget.contains(e.relatedTarget as Node)) setDragging(false);
          }}
          onDrop={(e) => {
            e.preventDefault();
            setDragging(false);
            void accept(e.dataTransfer.files?.[0]);
          }}
          className={cn(
            "flex h-20 cursor-pointer flex-col items-center justify-center gap-1.5 rounded-card border border-dashed border-border bg-muted/30 text-muted-foreground transition-colors hover:bg-muted/60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
            dragging && "border-primary bg-primary/10 text-foreground",
            busy && "pointer-events-none opacity-70"
          )}
        >
          {busy ? (
            <Loader2 className="size-5 animate-spin" aria-hidden="true" />
          ) : (
            <FileUp className="size-5" aria-hidden="true" />
          )}
          <span className="text-xs">{busy ? "Uploading…" : "Drop a file here, or click to choose"}</span>
        </div>
      )}
    </div>
  );
}
