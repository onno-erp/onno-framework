import { useRef, useState } from "react";
import { toast } from "sonner";
import { FileUp, Loader2, Paperclip, Trash2, Upload } from "lucide-react";
import {
  Attachment,
  AttachmentAction,
  AttachmentActions,
  AttachmentContent,
  AttachmentDescription,
  AttachmentMedia,
  AttachmentTitle,
  AttachmentTrigger,
} from "@/components/ui/attachment";
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
  const [uploadName, setUploadName] = useState<string | null>(null);
  const hasFile = typeof value === "string" && value.length > 0;

  const accept = async (file: File | undefined | null) => {
    if (!file || busy) return;
    if (file.size > MAX_BYTES) {
      toast.error(`"${file.name}" is too large (max ${Math.round(MAX_BYTES / 1024 / 1024)} MB).`);
      return;
    }
    setUploadName(file.name);
    setBusy(true);
    try {
      const stored = await uploadMedia(file);
      onChange(stored.url);
    } catch {
      toast.error(`Couldn't upload "${file.name}".`);
    } finally {
      setBusy(false);
      setUploadName(null);
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
      <Attachment
        state={busy ? "uploading" : "done"}
        className={cn(
          "bg-muted/30",
          !hasFile && "min-h-20 border-dashed",
          dragging && "border-primary bg-primary/10",
          busy && "opacity-80"
        )}
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
      >
        <AttachmentMedia>
          {busy ? (
            <Loader2 className="animate-spin" aria-hidden="true" />
          ) : hasFile ? (
            <Paperclip aria-hidden="true" />
          ) : (
            <FileUp aria-hidden="true" />
          )}
        </AttachmentMedia>
        <AttachmentContent>
          <AttachmentTitle>
            {hasFile ? leafOf(value!) : uploadName ?? "Choose a file"}
          </AttachmentTitle>
          <AttachmentDescription>
            {busy
              ? "Uploading"
              : hasFile
                ? "Open, replace, or remove attachment"
                : "Drop a file here, or click to choose"}
          </AttachmentDescription>
        </AttachmentContent>
        {hasFile ? (
          <>
            <AttachmentTrigger asChild>
              <a
                href={value}
                target="_blank"
                rel="noreferrer"
                aria-label={`Open ${leafOf(value!)}`}
                title={value}
              />
            </AttachmentTrigger>
            <AttachmentActions>
              <AttachmentAction
                type="button"
                onClick={openPicker}
                disabled={busy}
                aria-label="Replace file"
              >
                {busy ? <Loader2 className="animate-spin" /> : <Upload />}
              </AttachmentAction>
              <AttachmentAction
                type="button"
                onClick={() => onChange("")}
                disabled={busy}
                aria-label="Remove file"
                className="hover:text-destructive"
              >
                <Trash2 aria-hidden="true" />
              </AttachmentAction>
            </AttachmentActions>
          </>
        ) : (
          <AttachmentTrigger
            type="button"
            onClick={openPicker}
            disabled={busy}
            aria-label="Choose file"
          />
        )}
      </Attachment>
    </div>
  );
}
