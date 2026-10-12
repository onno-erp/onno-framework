import { createPortal } from "react-dom";
import { mountMobileHeaderSlot } from "@/lib/mobile-header";
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
  const navigationRef = useRef<HTMLDivElement>(null);
  const [navigationHeight, setNavigationHeight] = useState(0);
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
  const showNavigation = !detail && !localDetail;
  useLayoutEffect(() => {
    const node = navigationRef.current;
    if (!showNavigation || !node) { setNavigationHeight(0); return; }
    const measure = () => setNavigationHeight(node.getBoundingClientRect().height);
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(node);
    return () => observer.disconnect();
  }, [showNavigation]);
  // Fill-height widgets observe window/layout changes; removing shell chrome does not
  // necessarily resize the document, so explicitly invalidate their allocation.
  useLayoutEffect(() => {
    const frame = requestAnimationFrame(() => window.dispatchEvent(new Event("resize")));
    return () => cancelAnimationFrame(frame);
  }, [detail, localDetail, navigationHeight]);
  return <div className="flex h-full min-h-0 w-full flex-col overflow-hidden">
    <div ref={pagesRef} className="relative min-h-0 flex-1 overflow-hidden">
      {retainedRoutes.map(route => <div key={route} data-slide-page={route}
        hidden={route !== path && route !== outgoing} inert={route !== path} aria-hidden={route !== path}
        className={route === path ? "relative h-full w-full overflow-y-auto overflow-x-hidden overscroll-contain" : "absolute inset-0 h-full w-full overflow-y-auto overflow-x-hidden pointer-events-none"}
        style={route === path ? undefined : {transform: "translateX(-100%)"}}
      ><MobileRouteHeader enabled={!roots.includes(route) && route !== "/" && route !== "/menu"}
        back={() => back(currentStack[currentStack.length - 2] ?? mobileParent(path))} label={backLabel}
      >{render(route)}</MobileRouteHeader></div>)}
    </div>
    {showNavigation && <>
      <div aria-hidden="true" className="shrink-0" style={{height:navigationHeight}} />
      <div ref={navigationRef} className="pointer-events-none fixed inset-x-0 bottom-0 z-30 bg-transparent"
        style={{paddingBottom:"max(8px, env(safe-area-inset-bottom))"}}>
        {navigation}
      </div>
    </>}
  </div>;
}

/** The portal keeps the shell-owned navigation/dirty-form guard inside the page header. */
function MobileRouteHeader({ enabled, back, label, children }: {
  enabled: boolean; back: () => void; label: string; children: ReactNode;
}) {
  const [slot, setSlot] = useState<HTMLElement | null>(null);
  const host = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => {
    if (!enabled || !host.current) return;
    let header: HTMLElement | null = null;
    let mounted: ReturnType<typeof mountMobileHeaderSlot> | null = null;
    const sync = () => {
      const next = host.current?.querySelector<HTMLElement>("[data-onno-page-header]") ?? null;
      if (next === header) return;
      mounted?.dispose();
      header = next;
      mounted = next ? mountMobileHeaderSlot(next) : null;
      setSlot(mounted?.slot ?? null);
    };
    // Form portals mount outside this React tree; observe only the explicit header hook.
    const observer = new MutationObserver(sync);
    observer.observe(host.current, {childList: true, subtree: true, attributes: true, attributeFilter: ["data-onno-page-header"]});
    sync();
    return () => { observer.disconnect(); mounted?.dispose(); };
  }, [enabled]);
  const button = <Button variant="ghost" size="icon" className="h-11 w-11 shrink-0"
    aria-label={label} title={label} onClick={back}><ArrowLeft /></Button>;
  return <div ref={host} className="contents">
    {enabled && (slot ? createPortal(button, slot) :
      <header className="flex h-12 shrink-0 items-center border-b border-border bg-background px-3">{button}</header>)}
    {children}
  </div>;
}
