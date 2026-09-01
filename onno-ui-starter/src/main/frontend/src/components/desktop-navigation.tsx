import { useEffect, useMemo, useState, type ReactNode } from "react";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { DynamicLucide } from "@/lib/icon-bridge";
import { NavPresenceIndicator } from "@/lib/nav-presence-bridge";
import { cn } from "@/lib/utils";
import type { Translate } from "@/lib/messages";

export type ShellNavItem = {
  label: string;
  icon?: string;
  path: string;
};

export type ShellNavSection = {
  title?: string | null;
  icon?: string | null;
  items: ShellNavItem[];
};

type DesktopNavigationProps = {
  brand?: string;
  mark?: string;
  markFramed?: boolean;
  navigation: ShellNavSection[];
  activePath: string;
  home: string;
  onNavigate: (path: string) => void;
  onSectionFocus?: () => void;
  account: (compact: boolean) => ReactNode;
  notification: ReactNode;
  surface: string;
  border: string;
  primary: string;
  primarySoft: string;
  t: Translate;
};

const STORAGE_KEY = "onno.desktop-navigation.expanded";

function isItemActive(activePath: string, itemPath: string): boolean {
  const route = activePath.split("?")[0];
  return route === itemPath || route.startsWith(itemPath + "/");
}

function sectionForPath(sections: ShellNavSection[], path: string): number {
  return sections.findIndex((section) => section.items.some((item) => isItemActive(path, item.path)));
}

function isDirectSection(section: ShellNavSection | undefined): boolean {
  return Boolean(section && !section.title && section.items.length === 1);
}

function brandMark(brand: string): string {
  return brand.trim().charAt(0).toLocaleUpperCase() || "O";
}

/**
 * Desktop navigation derived directly from authored Layout sections: a stable app rail chooses a
 * workspace and a collapsible drawer shows that workspace's routes. The drawer state is local UI
 * preference only; the server remains the source of truth for destinations and RBAC filtering.
 */
export function DesktopNavigation({
  brand = "",
  mark = "",
  markFramed = true,
  navigation,
  activePath,
  home,
  onNavigate,
  onSectionFocus,
  account,
  notification,
  surface,
  border,
  primary,
  primarySoft,
  t,
}: DesktopNavigationProps) {
  const sections = useMemo(() => navigation.filter((section) => section.items.length > 0), [navigation]);
  const activeSection = sectionForPath(sections, activePath);
  const [selected, setSelected] = useState(() => (activeSection >= 0 ? activeSection : 0));
  const [markFailed, setMarkFailed] = useState(false);

  useEffect(() => setMarkFailed(false), [mark]);
  const [expanded, setExpanded] = useState(() => {
    try {
      return window.localStorage.getItem(STORAGE_KEY) !== "false";
    } catch {
      return true;
    }
  });

  useEffect(() => {
    if (activeSection >= 0) {
      setSelected(activeSection);
    }
  }, [activePath, activeSection, sections]);

  useEffect(() => {
    try {
      window.localStorage.setItem(STORAGE_KEY, String(expanded));
    } catch {
      // Storage can be unavailable in privacy modes; the in-memory preference still works.
    }
  }, [expanded]);

  const selectedSection = sections[selected] ?? sections[0];
  const selectedSectionIsDirect = isDirectSection(selectedSection);
  const drawerOpen = expanded && !selectedSectionIsDirect;
  const toggleLabel = t(expanded ? "shell.collapseNavigation" : "shell.expandNavigation");

  return (
    <aside
      data-testid="desktop-navigation"
      data-expanded={drawerOpen}
      className="t-resize flex h-screen shrink-0 gap-2 overflow-hidden p-2"
      style={{ width: drawerOpen ? 280 : 64 }}
    >
      <div
        className="flex h-full w-12 shrink-0 flex-col items-center rounded-panel border py-1.5"
        style={{ background: surface, borderColor: border }}
      >
        <button
          type="button"
          onClick={() => onNavigate(home)}
          aria-label={brand || t("nav.dashboard")}
          title={brand || t("nav.dashboard")}
          className={cn(
            "mb-2 flex h-8 w-8 items-center justify-center overflow-hidden rounded-field text-sm font-medium text-foreground hover:bg-muted",
            markFramed && "border"
          )}
          style={markFramed ? { borderColor: border } : undefined}
        >
          {mark && !markFailed ? (
            <img
              src={mark}
              alt=""
              className="h-6 w-6 object-contain"
              onError={() => setMarkFailed(true)}
            />
          ) : (
            brandMark(brand)
          )}
        </button>

        <nav aria-label={t("shell.menu")} className="flex min-h-0 flex-1 flex-col items-center gap-1 overflow-y-auto">
          {sections.map((section, index) => {
            const direct = isDirectSection(section);
            const item = section.items[0];
            const routeActive = section.items.some((candidate) => isItemActive(activePath, candidate.path));
            const sectionSelected = index === selected;
            // A focused tab owns the rail highlight whenever its route belongs to a section.
            // If the focused pane is empty (or its route is outside authored navigation), keep
            // the currently selected drawer section highlighted as the shell-level fallback.
            // The filled background means the drawer is visibly selected, so it must disappear
            // with the drawer. A focused tab may still keep its owning icon accented below.
            const highlighted = expanded && (activeSection >= 0 ? routeActive : sectionSelected);
            const navigationState = routeActive
              ? "route-active"
              : highlighted
                ? "section-active"
                : "inactive";
            const label = section.title || item?.label || t("shell.menu");
            const icon = section.icon || item?.icon || "panel-left";
            return (
              <button
                key={`${label}:${index}`}
                type="button"
                aria-label={label}
                aria-current={routeActive ? "page" : undefined}
                aria-expanded={direct ? undefined : sectionSelected && expanded}
                data-navigation-state={navigationState}
                title={label}
                onClick={() => {
                  if (direct && item) {
                    setSelected(index);
                    onNavigate(item.path);
                    return;
                  }
                  if (sectionSelected) {
                    setExpanded((value) => {
                      const next = !value;
                      if (next) onSectionFocus?.();
                      return next;
                    });
                  } else {
                    setSelected(index);
                    setExpanded(true);
                    onSectionFocus?.();
                  }
                }}
                className={cn(
                  "flex h-9 w-9 shrink-0 items-center justify-center rounded-field transition-colors",
                  highlighted
                    ? "text-primary"
                    : "text-muted-foreground hover:bg-muted hover:text-foreground"
                )}
                style={highlighted ? { background: primarySoft, color: primary } : undefined}
              >
                <DynamicLucide name={icon} size={17} />
              </button>
            );
          })}
        </nav>

        <div data-testid="desktop-navigation-notifications" className="mb-1 flex shrink-0 items-center justify-center">
          {notification}
        </div>

        {selectedSectionIsDirect ? (
          <div data-testid="desktop-navigation-account" className="mt-1.5 flex shrink-0 items-center justify-center">
            {account(true)}
          </div>
        ) : (
          <button
            type="button"
            onClick={() => setExpanded((value) => !value)}
            aria-label={toggleLabel}
            title={toggleLabel}
            className="mt-1.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-field text-muted-foreground hover:bg-muted hover:text-foreground"
          >
            {expanded ? <ChevronLeft size={17} /> : <ChevronRight size={17} />}
          </button>
        )}
      </div>

      <div
        aria-hidden={!drawerOpen}
        className={cn(
          "flex h-full w-52 shrink-0 flex-col gap-2 overflow-hidden",
          !drawerOpen && "invisible pointer-events-none"
        )}
      >
        <div
          data-testid="desktop-navigation-drawer"
          className="flex min-h-0 flex-1 flex-col overflow-hidden rounded-panel border"
          style={{ background: surface, borderColor: border }}
        >
          {selectedSection?.title ? (
            <div
              data-testid="desktop-navigation-drawer-header"
              className="flex h-11 shrink-0 items-center border-b px-3"
              style={{ borderColor: border }}
            >
              <h2 className="truncate text-sm font-medium text-foreground">{selectedSection.title}</h2>
            </div>
          ) : null}
          <nav className="min-h-0 flex-1 space-y-0.5 overflow-y-auto p-1.5" aria-label={selectedSection?.title || t("shell.menu")}>
            {selectedSection?.items.map((item) => {
              const active = isItemActive(activePath, item.path);
              return (
                <button
                  key={item.path}
                  type="button"
                  aria-label={item.label}
                  onClick={() => onNavigate(item.path)}
                  aria-current={active ? "page" : undefined}
                  className="flex w-full items-center gap-2 rounded-field px-2 py-1.5 text-left text-sm transition-colors hover:bg-muted"
                  style={active ? { background: primarySoft, color: primary } : undefined}
                >
                  <span className={cn("shrink-0", !active && "text-muted-foreground")}>
                    <DynamicLucide name={item.icon || "circle"} size={15} />
                  </span>
                  <span className="min-w-0 flex-1 truncate">{item.label}</span>
                  <span className="h-5 w-12 shrink-0">
                    <NavPresenceIndicator path={item.path} />
                  </span>
                </button>
              );
            })}
          </nav>
        </div>

        {!selectedSectionIsDirect ? (
          <div data-testid="desktop-navigation-account" className="shrink-0">
            {account(false)}
          </div>
        ) : null}
      </div>
    </aside>
  );
}
