import { useRef, useState } from "react";
import { toast } from "sonner";
import { ImagePlus, Loader2, Trash2, Upload, X } from "lucide-react";
import { uploadMedia } from "@/lib/api";
import { cn } from "@/lib/utils";

/**
 * An image input for an attribute whose field hint sets {@code .widget("image")} (or
 * {@code "avatar"} for a small round variant). The chosen file is streamed to {@code POST /api/media}
 * (see MediaController) and only the returned reference URL is stored in the attribute — so a plain
 * String column holds it, no base64-sized TEXT needed. Legacy {@code data:} base64 values still
 * render fine, so older records keep working.
 */

// Client-side guard mirroring the server's onno.media.max-file-size default (10 MB). The server
// validates authoritatively; this just gives instant feedback before the upload starts.
const MAX_BYTES = 10 * 1024 * 1024;

// Stream one image file to object storage and return its stored URL, or null on rejection.
async function uploadImage(file: File): Promise<string | null> {
  if (!file.type.startsWith("image/")) {
    toast.error(`"${file.name}" isn't an image.`);
    return null;
  }
  if (file.size > MAX_BYTES) {
    toast.error(`"${file.name}" is too large (max ${Math.round(MAX_BYTES / 1024 / 1024)} MB).`);
    return null;
  }
  try {
    const stored = await uploadMedia(file);
    return stored.url;
  } catch {
    toast.error(`Couldn't upload "${file.name}".`);
    return null;
  }
}

export function ImagePicker({
  value,
  onChange,
  variant = "image",
}: {
  value?: string;
  onChange: (val: string) => void;
  variant?: "image" | "avatar";
}) {
  const inputRef = useRef<HTMLInputElement | null>(null);
  const [dragging, setDragging] = useState(false);
  const [busy, setBusy] = useState(false);
  const avatar = variant === "avatar";
  const hasImage = typeof value === "string" && value.length > 0;

  const accept = async (file: File | undefined | null) => {
    if (!file || busy) return;
    setBusy(true);
    try {
      const url = await uploadImage(file);
      if (url) onChange(url);
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
        accept="image/*"
        className="hidden"
        onChange={(e) => {
          void accept(e.target.files?.[0]);
          e.target.value = ""; // allow re-picking the same file
        }}
      />
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
          "relative flex cursor-pointer items-center justify-center overflow-hidden border border-dashed border-border bg-muted/30 text-muted-foreground transition-colors hover:bg-muted/60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
          dragging && "border-primary bg-primary/10 text-foreground",
          busy && "pointer-events-none opacity-70",
          avatar ? "size-28 rounded-full" : "h-44 w-full rounded-card"
        )}
      >
        {hasImage ? (
          <img src={value} alt="" className="h-full w-full object-cover" />
        ) : (
          <div className="flex flex-col items-center gap-1.5 px-3 text-center">
            {busy ? (
              <Loader2 className="size-6 animate-spin" aria-hidden="true" />
            ) : (
              <ImagePlus className="size-6" aria-hidden="true" />
            )}
            <span className="text-xs">
              {busy ? "Uploading…" : avatar ? "Add photo" : "Drop an image here, or click to choose"}
            </span>
          </div>
        )}
        {hasImage && busy ? (
          <div className="absolute inset-0 grid place-items-center bg-background/60">
            <Loader2 className="size-6 animate-spin" aria-hidden="true" />
          </div>
        ) : null}
      </div>
      {hasImage ? (
        <div className="flex gap-2">
          <button
            type="button"
            onClick={openPicker}
            disabled={busy}
            className="inline-flex items-center gap-1.5 rounded-md px-2 py-1 text-xs text-muted-foreground transition-colors hover:bg-accent hover:text-foreground disabled:opacity-50"
          >
            <Upload className="size-3.5" aria-hidden="true" />
            Replace
          </button>
          <button
            type="button"
            onClick={() => onChange("")}
            disabled={busy}
            className="inline-flex items-center gap-1.5 rounded-md px-2 py-1 text-xs text-muted-foreground transition-colors hover:bg-accent hover:text-destructive disabled:opacity-50"
          >
            <Trash2 className="size-3.5" aria-hidden="true" />
            Remove
          </button>
        </div>
      ) : null}
    </div>
  );
}

// Several images are stored newline-joined in one String attribute. Stored-media URLs (and legacy
// base64 data URLs) contain no newline, so the join is unambiguous and the server splits on it too
// (SurfaceDivBuilder).
const GALLERY_SEP = "\n";

/**
 * A multi-image picker for an attribute whose field hint sets {@code .widget("images")} (or
 * {@code "gallery"}). Drop several files at once or click Add; each is streamed to
 * {@code POST /api/media} and stored as a reference URL, newline-joined. Thumbnails show in a grid;
 * hover a thumbnail to remove it.
 */
export function GalleryPicker({
  value,
  onChange,
}: {
  value?: string;
  onChange: (val: string) => void;
}) {
  const inputRef = useRef<HTMLInputElement | null>(null);
  const [dragging, setDragging] = useState(false);
  const [uploading, setUploading] = useState(0);
  const urls = (value ?? "")
    .split(GALLERY_SEP)
    .map((s) => s.trim())
    .filter(Boolean);

  const addFiles = async (files: FileList | File[] | null | undefined) => {
    if (!files) return;
    const list = Array.from(files);
    setUploading((n) => n + list.length);
    const accepted: string[] = [];
    try {
      // Upload concurrently, then append in selection order so the gallery keeps a stable order.
      const results = await Promise.all(list.map((file) => uploadImage(file)));
      for (const url of results) {
        if (url) accepted.push(url);
      }
    } finally {
      setUploading((n) => Math.max(0, n - list.length));
    }
    if (accepted.length) onChange([...urls, ...accepted].join(GALLERY_SEP));
  };

  const removeAt = (idx: number) =>
    onChange(urls.filter((_, i) => i !== idx).join(GALLERY_SEP));

  return (
    <div
      className="grid gap-2"
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
        void addFiles(e.dataTransfer.files);
      }}
    >
      <input
        ref={inputRef}
        type="file"
        accept="image/*"
        multiple
        className="hidden"
        onChange={(e) => {
          void addFiles(e.target.files);
          e.target.value = "";
        }}
      />
      <div
        className={cn(
          "grid grid-cols-3 gap-2 rounded-card sm:grid-cols-4",
          dragging && "ring-2 ring-primary"
        )}
      >
        {urls.map((url, idx) => (
          <div
            key={idx}
            className="group relative aspect-square overflow-hidden rounded-lg border border-border"
          >
            <img src={url} alt="" className="h-full w-full object-cover" />
            <button
              type="button"
              aria-label={`Remove image ${idx + 1}`}
              onClick={() => removeAt(idx)}
              className="absolute right-1 top-1 grid size-6 place-items-center rounded-md bg-black/60 text-white opacity-0 transition-opacity hover:bg-black/80 group-hover:opacity-100"
            >
              <X className="size-3.5" aria-hidden="true" />
            </button>
          </div>
        ))}
        {Array.from({ length: uploading }).map((_, i) => (
          <div
            key={`uploading-${i}`}
            className="grid aspect-square place-items-center rounded-lg border border-dashed border-border bg-muted/30 text-muted-foreground"
          >
            <Loader2 className="size-5 animate-spin" aria-hidden="true" />
          </div>
        ))}
        <button
          type="button"
          onClick={() => inputRef.current?.click()}
          className={cn(
            "flex aspect-square flex-col items-center justify-center gap-1 rounded-lg border border-dashed border-border bg-muted/30 text-muted-foreground transition-colors hover:bg-muted/60",
            dragging && "border-primary bg-primary/10 text-foreground"
          )}
        >
          <ImagePlus className="size-5" aria-hidden="true" />
          <span className="text-[11px]">Add</span>
        </button>
      </div>
      <p className="text-xs text-muted-foreground">
        {urls.length
          ? `${urls.length} image${urls.length > 1 ? "s" : ""} — drop more or click Add`
          : "Drop images here, or click Add"}
      </p>
    </div>
  );
}
