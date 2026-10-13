import { text as t } from "@onno/widget-sdk";

export type MediaItem = { url: string; filename: string };

/** Tiles drawn before the rest collapse into a "+N" tile, as Telegram does for large albums. */
const MAX_TILES = 9;

/**
 * Several images on one message, laid out as one album: two side by side, three as one wide and two
 * square, four as a square grid, more in rows of three. A single image keeps its natural shape.
 * Every tile opens the full-size viewer at that image.
 */
export function MediaGrid({ images }: { images: MediaItem[] }) {
  if (!images.length) return null;
  const open = (index: number, opener: HTMLElement) => openMediaViewer(images, index, opener);
  if (images.length === 1) {
    const [image] = images;
    return (
      <button type="button" onClick={event => open(0, event.currentTarget)}
        aria-label={t("crm.media.open", { index: 1, count: 1 })}
        className="mt-1.5 block cursor-zoom-in overflow-hidden rounded-field border-0 bg-transparent p-0">
        <img src={image.url} alt={image.filename} loading="lazy" className="max-h-80 w-auto max-w-full rounded-field object-cover" />
      </button>
    );
  }
  const shown = images.slice(0, MAX_TILES);
  const hidden = images.length - shown.length;
  const columns = images.length <= 4 ? "grid-cols-2" : "grid-cols-3";
  return (
    <div className={`mt-1.5 grid w-72 max-w-full gap-0.5 overflow-hidden rounded-field sm:w-80 ${columns}`}>
      {shown.map((image, index) => {
        const wide = images.length === 3 && index === 0;
        const more = hidden > 0 && index === shown.length - 1;
        return (
          <button key={image.url} type="button" onClick={event => open(index, event.currentTarget)}
            aria-label={t("crm.media.open", { index: index + 1, count: images.length })}
            className={`relative block cursor-zoom-in overflow-hidden border-0 bg-muted p-0 ${wide ? "col-span-2 aspect-[2/1]" : "aspect-square"}`}>
            <img src={image.url} alt={image.filename} loading="lazy" className="size-full object-cover" />
            {more && (
              <span className="absolute inset-0 flex items-center justify-center bg-black/55 text-lg font-semibold text-white">
                +{hidden + 1}
              </span>
            )}
          </button>
        );
      })}
    </div>
  );
}

/**
 * Full-screen viewer over the whole app: arrows or swipes step through the album, Escape or a
 * backdrop click closes it and returns focus to the tile that opened it. Built on the DOM directly
 * so it escapes any transformed inbox pane, which would otherwise trap a fixed overlay.
 */
export function openMediaViewer(images: MediaItem[], start: number, opener?: HTMLElement) {
  let index = start;
  const overlay = document.createElement("div");
  overlay.setAttribute("role", "dialog");
  overlay.setAttribute("aria-modal", "true");
  overlay.setAttribute("aria-label", t("crm.media.viewer"));
  overlay.tabIndex = -1;
  overlay.className = "fixed inset-0 z-[80] flex flex-col bg-black/90 text-white outline-none";

  const control = "flex size-10 items-center justify-center rounded-full border-0 bg-white/10 text-xl leading-none text-white hover:bg-white/20 cursor-pointer";
  const bar = document.createElement("div");
  bar.className = "flex shrink-0 items-center gap-3 px-4 py-3 text-sm";
  const counter = document.createElement("span");
  counter.className = "tabular-nums text-white/80";
  const name = document.createElement("span");
  name.className = "min-w-0 flex-1 truncate text-white/80";
  const download = document.createElement("a");
  download.className = "rounded-full bg-white/10 px-3 py-1.5 text-xs font-medium text-white no-underline hover:bg-white/20";
  download.textContent = t("crm.media.download");
  download.target = "_blank";
  download.rel = "noreferrer";
  const close = document.createElement("button");
  close.type = "button";
  close.className = control;
  close.setAttribute("aria-label", t("crm.media.close"));
  close.textContent = "×";
  bar.append(counter, name, download, close);

  const stage = document.createElement("div");
  stage.className = "relative flex min-h-0 flex-1 items-center justify-center px-2 pb-4 sm:px-16";
  const img = document.createElement("img");
  img.className = "max-h-full max-w-full select-none object-contain";
  const previous = document.createElement("button");
  previous.type = "button";
  previous.className = `${control} absolute left-3 top-1/2 -translate-y-1/2`;
  previous.setAttribute("aria-label", t("crm.media.previous"));
  previous.textContent = "‹";
  const next = document.createElement("button");
  next.type = "button";
  next.className = `${control} absolute right-3 top-1/2 -translate-y-1/2`;
  next.setAttribute("aria-label", t("crm.media.next"));
  next.textContent = "›";
  stage.append(img);
  if (images.length > 1) stage.append(previous, next);
  overlay.append(bar, stage);

  const show = (to: number) => {
    index = (to + images.length) % images.length;
    const image = images[index];
    img.src = image.url;
    img.alt = image.filename;
    name.textContent = image.filename;
    download.href = image.url;
    download.download = image.filename;
    counter.textContent = images.length > 1 ? `${index + 1} / ${images.length}` : "";
  };
  const dismiss = () => {
    document.removeEventListener("keydown", keys, true);
    overlay.remove();
    document.body.style.overflow = previousOverflow;
    opener?.focus();
  };
  const keys = (event: KeyboardEvent) => {
    if (event.key === "Escape") { event.preventDefault(); event.stopPropagation(); dismiss(); }
    else if (event.key === "ArrowLeft" && images.length > 1) { event.preventDefault(); show(index - 1); }
    else if (event.key === "ArrowRight" && images.length > 1) { event.preventDefault(); show(index + 1); }
    else if (event.key === "Tab") {
      // Keep keyboard focus inside the viewer while it is open.
      const focusable = Array.from(overlay.querySelectorAll<HTMLElement>("a,button"));
      const at = focusable.indexOf(document.activeElement as HTMLElement);
      event.preventDefault();
      focusable[(at + (event.shiftKey ? -1 : 1) + focusable.length) % focusable.length]?.focus();
    }
  };
  let touchX: number | null = null;
  stage.addEventListener("touchstart", event => { touchX = event.touches[0]?.clientX ?? null; }, { passive: true });
  stage.addEventListener("touchend", event => {
    const end = event.changedTouches[0]?.clientX;
    if (touchX != null && end != null && Math.abs(end - touchX) > 50 && images.length > 1) show(index + (end < touchX ? 1 : -1));
    touchX = null;
  });
  previous.addEventListener("click", () => show(index - 1));
  next.addEventListener("click", () => show(index + 1));
  close.addEventListener("click", dismiss);
  stage.addEventListener("click", event => { if (event.target === stage) dismiss(); });

  const previousOverflow = document.body.style.overflow;
  document.body.style.overflow = "hidden";
  // Capture phase, so Escape closes the viewer before the inbox treats it as "leave this chat".
  document.addEventListener("keydown", keys, true);
  show(index);
  document.body.append(overlay);
  overlay.focus();
}
