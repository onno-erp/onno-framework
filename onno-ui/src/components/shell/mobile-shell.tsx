import { navigationLinks } from "../../lib/navigation-tree";
import { NavigationHost, DefaultNavigation } from "./navigation";
import { createContext, useContext } from "react";
import { Bell, ChevronRight, LogOut, Moon, Sun } from "lucide-react";
import { Icon } from "../../lib/icons";
import { UnreadBadge, UnreadDot } from "../collaboration/notification-indicators";
import { cn } from "../../lib/utils";
import type { Translate } from "../../lib/messages";
import type { ShellData, ShellNavItem, ShellNavSection } from "../../lib/view-types";

/**
 * What the shell hands to its chrome components and to client-side shell pages (the mobile
 * `/menu` hub): the resolved shell, the current route, and the action dispatcher (`onno://` URLs
 * and app routes, the same handler surfaces use).
 */
export type ShellContextValue = {
  shell: ShellData | null;
  activePath: string;
  resolvedTheme: "light" | "dark";
  onAction: (url: string) => void;
  t: Translate;
};

export const ShellContext = createContext<ShellContextValue | null>(null);

export function useShell(): ShellContextValue {
  const ctx = useContext(ShellContext);
  if (!ctx) throw new Error("useShell() outside the app shell");
  return ctx;
}

export function isNavItemActive(activePath: string, itemPath: string): boolean {
  const route = activePath.split("?")[0];
  if (itemPath === "/") return route === "/" || route === "";
  return route === itemPath || route.startsWith(itemPath + "/");
}

// A phone bottom bar fits only a handful of comfortable touch targets: the first few destinations
// (by their mobile order), then a "More" tab opening the /menu hub with everything else.
const MAX_PRIMARY_TABS = 4;

export function bottomBarItems(navigation: ShellNavSection[]): ShellNavItem[] {
  const seen = new Set<string>();
  return navigation
    .flatMap((section) => navigationLinks(section.items))
    .filter((item) => { if (seen.has(item.path)) return false; seen.add(item.path); return true; })
    .map((item, index) => ({ item, index }))
    .sort((a, b) => (a.item.mobileOrder ?? Number.MAX_SAFE_INTEGER) - (b.item.mobileOrder ?? Number.MAX_SAFE_INTEGER)
      || a.index - b.index)
    .slice(0, MAX_PRIMARY_TABS)
    .map(({ item }) => item);
}

/**
 * The floating bottom tab bar (phones and tablets). {@code compact} (tablet) hugs its tabs so it can
 * pin to a corner; otherwise it stretches and the tabs share the width.
 */
export function BottomTabBar({ compact }: { compact: boolean }) {
  const { shell, activePath, onAction, t } = useShell();
  const items = bottomBarItems(shell?.navigation ?? []);
  const more: ShellNavItem = { label: t("shell.more"), mobileLabel: t("shell.more"), icon: "menu", path: "/menu" };
  return (
    <div
      className={cn(
        "m-3 flex rounded-[22px] border border-border bg-card p-2",
        compact ? "w-fit gap-1 rounded-3xl" : "w-auto"
      )}
    >
      {[...items, more].map((item) => {
        const active = isNavItemActive(activePath, item.path);
        return (
          <button
            key={item.path}
            type="button"
            aria-label={item.label}
            aria-current={active ? "page" : undefined}
            onClick={() => onAction(item.path)}
            className={cn(
              "flex min-w-0 flex-col items-center gap-[3px] rounded-xl py-2 text-xs transition-colors",
              compact ? "px-3.5" : "flex-1 px-0.5",
              active ? "bg-accent text-primary" : "text-muted-foreground"
            )}
          >
            <span className="relative">
              <Icon name={item.icon || "circle"} size={14} />
              {item.path === "/menu" ? (
                <span className="absolute -right-1 -top-1 size-2"><UnreadDot /></span>
              ) : null}
            </span>
            <span className="max-w-full truncate">{item.mobileLabel || item.label}</span>
          </button>
        );
      })}
    </div>
  );
}

/**
 * The mobile "More" hub at `/menu`: every destination grouped by section, the notifications row,
 * and the account block (theme, sign-out). A client-side page — registered as a
 * route override, so an app can replace it like any other route.
 */
export function MobileMenu() {
  const { shell, activePath, onAction, resolvedTheme, t } = useShell();
  const account = shell?.accountInfo;
  const row = "flex w-full items-center gap-3 px-4 py-3 text-left text-sm";
  return (
    <div className="flex flex-col gap-1.5 p-4">
      <div className="mb-3.5">
        {shell?.logo ? (
          <img src={shell.logo} alt={shell.brand ?? ""} className="h-8 w-auto" />
        ) : (
          <h1 className="text-2xl font-medium">{shell?.brand || t("shell.menu")}</h1>
        )}
      </div>
      {shell?.notifications ? (
        <div className="overflow-hidden rounded-card border border-border bg-card">
          <button type="button" className={row} onClick={() => onAction("onno://notifications")}>
            <Bell className="size-4 text-muted-foreground" />
            <span className="flex-1">{t("notifications.title")}</span>
            <span className="h-5 w-10"><UnreadBadge /></span>
          </button>
        </div>
      ) : null}
      <NavigationHost navigation={shell?.navigation ?? []} activePath={activePath} mobile={true} onNavigate={onAction}>
        <DefaultNavigation sections={shell?.navigation ?? []} />
      </NavigationHost>
      {account ? (
        <div className="mt-4 overflow-hidden rounded-card border border-border bg-card">
          <div className="flex items-center gap-3 px-4 py-3">
            {account.avatarUrl ? (
              <img src={account.avatarUrl} alt="" className="size-8 rounded-full object-cover" />
            ) : null}
            <div className="min-w-0 flex-1">
              <div className="text-xs font-medium text-muted-foreground">{t("shell.signedInAs")}</div>
              <div className="truncate text-sm">{account.displayName}</div>
            </div>
            <button
              type="button"
              aria-label={t("shell.theme")}
              className="grid size-9 place-items-center rounded-control text-muted-foreground hover:bg-muted"
              onClick={() => onAction("onno://theme/toggle")}
            >
              {resolvedTheme === "dark" ? <Sun className="size-4" /> : <Moon className="size-4" />}
            </button>
            <button
              type="button"
              aria-label={t("shell.signOut")}
              className="grid size-9 place-items-center rounded-control text-muted-foreground hover:bg-muted"
              onClick={() => onAction("onno://logout")}
            >
              <LogOut className="size-4" />
            </button>
          </div>
        </div>
      ) : null}
    </div>
  );
}
