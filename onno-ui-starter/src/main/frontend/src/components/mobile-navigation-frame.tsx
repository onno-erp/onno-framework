import { usePageSlide } from "@/lib/use-page-slide";
import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from "react";
import { ArrowLeft } from "lucide-react";
import { Button } from "@/components/ui/button";
import { stripBasePath } from "@/lib/base-path";

export function mobileParent(path: string): string {
  const parts = path.split("?")[0].split("/").filter(Boolean);
  if (["catalogs", "documents", "registers"].includes(parts[0]) && parts.length > 2)
    return "/" + parts.slice(0, 2).join("/");
  return parts.length > 1 ? "/" + parts.slice(0, -1).join("/") : "/";
}

/** Retain the parent surface while a child is open: filters, drafts and internal scroll survive Back. */
export function MobileNavigationFrame({ path, roots, render, navigation, back, backLabel }: {
  path: string; roots: string[]; render: (path: string) => ReactNode;
  navigation: ReactNode; back: (path: string) => void; backLabel: string;
}) {
  const [stack, setStack] = useState<string[]>([path]);
  const [requests, setRequests] = useState<Record<string, string>>({});
  const scroll = useRef(new Map<string, number>());
  const previous = useRef(path);
  const pagesRef = useRef<HTMLDivElement>(null);
  const exiting = useRef<string | null>(null);
  if (previous.current !== path) exiting.current = previous.current;
  const outgoing = exiting.current;
  const isRoot = roots.includes(path) || path === "/" || path === "/menu";
  const motion = useRef<"slide" | "fade">("slide");
  if (previous.current !== path) {
    // Switching sections is lateral navigation; returning to a retained parent is Back.
    const fromRoot = roots.includes(previous.current) || previous.current === "/" || previous.current === "/menu";
    motion.current = isRoot && (fromRoot || !stack.includes(path)) ? "fade" : "slide";
  }
  const currentStack = stack[stack.length - 1] === path ? stack : isRoot ? [path]
    : stack.includes(path) ? stack.slice(0, stack.indexOf(path) + 1) : [...stack, path];
  useLayoutEffect(() => {
    if (previous.current !== path) {
      window.scrollTo(0, scroll.current.get(path) ?? 0);
      previous.current = path;
    }

  }, [path, currentStack, stack]);
  usePageSlide(pagesRef, path, true, () => {
    exiting.current = null;
    setStack([...currentStack]);
  }, motion.current);
  const retainedRoutes = [...new Set([...stack, ...currentStack])];
  useLayoutEffect(() => {
    const listener = (event: Event) => {
      const detail = (event as CustomEvent).detail;
      if (!detail || typeof detail.id !== "string") return;
      setRequests(current => {
        const next = {...current};
        if (detail.active && typeof detail.path === "string") next[detail.id] = stripBasePath(detail.path);
        else delete next[detail.id];
        return next;
      });
    };
    window.addEventListener("onno:mobile-detail", listener);
    return () => window.removeEventListener("onno:mobile-detail", listener);
  }, []);
  useEffect(() => {
    const save = () => scroll.current.set(previous.current, window.scrollY);
    window.addEventListener("scroll", save, {passive:true});
    return () => window.removeEventListener("scroll", save);
  }, []);
  const localDetail = Object.values(requests).includes(path);
  const detail = !isRoot;
  // Fill-height widgets observe window/layout changes; removing shell chrome does not
  // necessarily resize the document, so explicitly invalidate their allocation.
  useLayoutEffect(() => {
    const frame = requestAnimationFrame(() => window.dispatchEvent(new Event("resize")));
    return () => cancelAnimationFrame(frame);
  }, [detail, localDetail]);
  return <div className="min-h-screen w-full overflow-x-hidden">
    {detail && <header className="sticky top-0 z-20 flex h-12 items-center border-b border-border bg-background px-3">
      <Button variant="ghost" onClick={() => back(currentStack[currentStack.length - 2] ?? mobileParent(path))}>
        <ArrowLeft className="size-4" />{backLabel}
      </Button>
    </header>}
    <div ref={pagesRef} className={`relative overflow-x-clip ${detail || localDetail ? "" : "pb-24"}`}>
      {retainedRoutes.map(route => <div key={route} data-slide-page={route}
        hidden={route !== path && route !== outgoing} inert={route !== path} aria-hidden={route !== path}
        className={route === path ? "relative w-full" : "absolute inset-x-0 top-0 w-full pointer-events-none"}
        style={route === path ? undefined : {transform: "translateX(-100%)"}}
      >{render(route)}</div>)}
    </div>
    {!detail && !localDetail && navigation}
  </div>;
}
