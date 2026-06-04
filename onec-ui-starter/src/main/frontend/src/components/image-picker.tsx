import { useRef, useState } from "react";
import { toast } from "sonner";
import { ImagePlus, Trash2, Upload, X } from "lucide-react";
import { cn } from "@/lib/utils";

/**
 * An image input for an attribute whose field hint sets {@code .widget("image")} (or
 * {@code "avatar"} for a small round variant). The value is a base64 {@code data:} URL, so it
 * round-trips through a String attribute — which must be declared large enough to be stored as
 * TEXT (e.g. {@code @Attribute(length = 2_000_000)}; see SchemaGenerator.columnType). Drag an
 * image file onto the drop zone or click to pick one; the file is read entirely in the browser,
 * never uploaded separately.
 */

// Cap the original file so a row doesn't balloon (base64 adds ~37%). Larger → ask the user to
// shrink it rather than silently storing a multi-megabyte string.
const MAX_BYTES = 2 * 1024 * 1024;

function readAsDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(reader.error ?? new Error("read failed"));
    reader.readAsDataURL(file);
  });
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
  const avatar = variant === "avatar";
  const hasImage = typeof value === "string" && value.length > 0;

  const accept = async (file: File | undefined | null) => {
    if (!file) return;
    if (!file.type.startsWith("image/")) {
      toast.error("That file isn't an image.");
      return;
    }
    if (file.size > MAX_BYTES) {
      toast.error(`Image is too large (max ${Math.round(MAX_BYTES / 1024 / 1024)} MB).`);
      return;
    }
    try {
      onChange(await readAsDataUrl(file));
    } catch {
      toast.error("Couldn't read that image.");
    }
  };

  const openPicker = () => inputRef.current?.click();

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
          avatar ? "size-28 rounded-full" : "h-44 w-full rounded-xl"
        )}
      >
        {hasImage ? (
          <img src={value} alt="" className="h-full w-full object-cover" />
        ) : (
          <div className="flex flex-col items-center gap-1.5 px-3 text-center">
            <ImagePlus className="size-6" aria-hidden="true" />
            <span className="text-xs">
              {avatar ? "Add photo" : "Drop an image here, or click to choose"}
            </span>
          </div>
        )}
      </div>
      {hasImage ? (
        <div className="flex gap-2">
          <button
            type="button"
            onClick={openPicker}
            className="inline-flex items-center gap-1.5 rounded-md px-2 py-1 text-xs text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
          >
            <Upload className="size-3.5" aria-hidden="true" />
            Replace
          </button>
          <button
            type="button"
            onClick={() => onChange("")}
            className="inline-flex items-center gap-1.5 rounded-md px-2 py-1 text-xs text-muted-foreground transition-colors hover:bg-accent hover:text-destructive"
          >
            <Trash2 className="size-3.5" aria-hidden="true" />
            Remove
          </button>
        </div>
      ) : null}
    </div>
  );
}

// Several images are stored newline-joined in one String attribute. base64 data URLs contain no
// newline, so the join is unambiguous and the server splits on it too (SurfaceDivBuilder).
const GALLERY_SEP = "\n";

/**
 * A multi-image picker for an attribute whose field hint sets {@code .widget("images")} (or
 * {@code "gallery"}). Drop several files at once or click Add; each becomes a base64 data URL,
 * stored newline-joined — so this also needs a large-length / TEXT attribute. Thumbnails show in
 * a grid; hover a thumbnail to remove it.
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
  const urls = (value ?? "")
    .split(GALLERY_SEP)
    .map((s) => s.trim())
    .filter(Boolean);

  const addFiles = async (files: FileList | File[] | null | undefined) => {
    if (!files) return;
    const accepted: string[] = [];
    for (const file of Array.from(files)) {
      if (!file.type.startsWith("image/")) {
        toast.error(`"${file.name}" isn't an image.`);
        continue;
      }
      if (file.size > MAX_BYTES) {
        toast.error(`"${file.name}" is too large (max ${Math.round(MAX_BYTES / 1024 / 1024)} MB).`);
        continue;
      }
      try {
        accepted.push(await readAsDataUrl(file));
      } catch {
        toast.error(`Couldn't read "${file.name}".`);
      }
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
          "grid grid-cols-3 gap-2 rounded-xl sm:grid-cols-4",
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
