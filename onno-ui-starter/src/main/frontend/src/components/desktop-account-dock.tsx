import { useCallback, useEffect, useRef, useState } from "react";
import { Check, LogOut, Monitor, Moon, Settings, Sun, Users } from "lucide-react";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { cn } from "@/lib/utils";
import type { Translate } from "@/lib/messages";

export type ShellAccountInfo = {
  displayName: string;
  avatarUrl?: string;
  profiles?: Array<{ id: string; title: string }>;
  activeProfileId?: string;
};

type DesktopAccountDockProps = {
  account: ShellAccountInfo;
  theme: "light" | "dark" | "system";
  surface: string;
  border: string;
  onThemeChange: (theme: "light" | "dark" | "system") => void;
  onSignOut: () => void;
  onProfileChange: (profile: string) => void;
  t: Translate;
};

function initials(name: string): string {
  return name
    .trim()
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part.charAt(0))
    .join("")
    .toLocaleUpperCase() || "U";
}

/** Desktop-native account footer aligned with the compact controls in the two-tier shell. */
export function DesktopAccountDock({
  account,
  theme,
  surface,
  border,
  onThemeChange,
  onSignOut,
  onProfileChange,
  t,
}: DesktopAccountDockProps) {
  const profiles = account.profiles ?? [];
  const [open, setOpen] = useState(false);
  const [entered, setEntered] = useState(false);
  const [closing, setClosing] = useState(false);
  const triggerRef = useRef<HTMLButtonElement | null>(null);
  const menuRef = useRef<HTMLDivElement | null>(null);
  const openFrame = useRef<number | null>(null);
  const closeTimer = useRef<number | null>(null);
  const setMenuOpen = useCallback((next: boolean) => {
    if (closeTimer.current != null) window.clearTimeout(closeTimer.current);
    if (openFrame.current != null) window.cancelAnimationFrame(openFrame.current);
    if (next) {
      setClosing(false);
      setEntered(false);
      setOpen(true);
      openFrame.current = window.requestAnimationFrame(() => {
        // Commit the dropdown's pre-open scale/opacity before applying its entered state.
        // Without this read, React can batch both states into one paint and skip the transition.
        void menuRef.current?.offsetWidth;
        setEntered(true);
        openFrame.current = null;
      });
      return;
    }
    const restoreTriggerFocus = menuRef.current?.contains(document.activeElement) === true;
    setEntered(false);
    setOpen(false);
    setClosing(true);
    // The content stays mounted for its close animation. Move focus out immediately so the
    // invisible Radix layer cannot keep consuming the next Escape intended for the workspace tab.
    if (restoreTriggerFocus) triggerRef.current?.focus({ preventScroll: true });
    const raw = getComputedStyle(document.documentElement).getPropertyValue("--dropdown-close-dur");
    const closeMs = Number.parseFloat(raw) || 150;
    closeTimer.current = window.setTimeout(() => setClosing(false), closeMs);
  }, []);
  useEffect(() => () => {
    if (closeTimer.current != null) window.clearTimeout(closeTimer.current);
    if (openFrame.current != null) window.cancelAnimationFrame(openFrame.current);
  }, []);

  const chooseTheme = (next: "light" | "dark" | "system") => {
    onThemeChange(next);
    setMenuOpen(false);
  };

  return (
    <div
      data-testid="desktop-account-dock"
      className="overflow-hidden rounded-panel border"
      style={{ background: surface, borderColor: border }}
    >
      <div className="flex min-w-0 items-center gap-2 p-1.5">
        <Avatar className="h-9 w-9 border" style={{ borderColor: border }}>
          {account.avatarUrl ? <AvatarImage src={account.avatarUrl} alt="" /> : null}
          <AvatarFallback>{initials(account.displayName)}</AvatarFallback>
        </Avatar>
        <div className="min-w-0 flex-1 leading-tight">
          <div className="truncate text-[11px] font-medium text-muted-foreground">{t("shell.signedInAs")}</div>
          <div className="truncate text-sm font-medium text-foreground">{account.displayName}</div>
        </div>
        <Popover open={open} onOpenChange={setMenuOpen}>
          <PopoverTrigger asChild>
            <button
              ref={triggerRef}
              type="button"
              aria-label={t("shell.accountMenu")}
              title={t("shell.accountMenu")}
              aria-expanded={open}
              className={cn(
                "flex h-8 w-8 shrink-0 items-center justify-center rounded-field text-muted-foreground transition-colors hover:bg-muted hover:text-foreground",
                open && "bg-muted text-foreground"
              )}
            >
              <Settings size={16} />
            </button>
          </PopoverTrigger>
          {open || closing ? (
            <PopoverContent
              ref={menuRef}
              forceMount
              role="menu"
              side="right"
              align="end"
              sideOffset={12}
              collisionPadding={12}
              motion="none"
              data-origin="bottom-left"
              data-testid="desktop-account-menu"
              className={cn(
                "t-dropdown w-60 rounded-panel p-1.5 shadow-xl",
                open && entered && "is-open",
                closing && "is-closing"
              )}
            >
            <div className="px-2 pb-1 pt-1.5 text-xs font-medium text-muted-foreground">
              {t("shell.appearance")}
            </div>
            {([
              ["light", t("shell.themeLight"), Sun],
              ["dark", t("shell.themeDark"), Moon],
              ["system", t("shell.themeSystem"), Monitor],
            ] as const).map(([value, label, Icon]) => (
              <button
                key={value}
                type="button"
                role="menuitemradio"
                aria-checked={theme === value}
                onClick={() => chooseTheme(value)}
                className="flex w-full items-center gap-2 rounded-field px-2 py-1.5 text-left text-sm text-foreground outline-none transition-colors hover:bg-accent focus-visible:bg-accent"
              >
                <Icon size={16} className="text-muted-foreground" />
                <span className="flex-1">{label}</span>
                {theme === value ? <Check size={15} className="text-primary" /> : null}
              </button>
            ))}
            {profiles.length > 1 ? (
              <>
                <div className="-mx-1 my-1 h-px bg-border" />
                <div className="px-2 pb-1 pt-1.5 text-xs font-medium text-muted-foreground">
                  {t("shell.profiles")}
                </div>
                {profiles.map((profile) => (
                  <button
                    key={profile.id}
                    type="button"
                    role="menuitemradio"
                    aria-checked={profile.id === account.activeProfileId}
                    onClick={() => {
                      onProfileChange(profile.id);
                      setMenuOpen(false);
                    }}
                    className="flex w-full items-center gap-2 rounded-field px-2 py-1.5 text-left text-sm text-foreground outline-none transition-colors hover:bg-accent focus-visible:bg-accent"
                  >
                    <Users size={16} className="text-muted-foreground" />
                    <span className="min-w-0 flex-1 truncate">{profile.title}</span>
                    {profile.id === account.activeProfileId ? <Check size={15} className="text-primary" /> : null}
                  </button>
                ))}
              </>
            ) : null}
            <div className="-mx-1 my-1 h-px bg-border" />
            <button
              type="button"
              role="menuitem"
              onClick={onSignOut}
              className="flex w-full items-center gap-2 rounded-field px-2 py-1.5 text-left text-sm text-destructive outline-none transition-colors hover:bg-destructive/10 focus-visible:bg-destructive/10"
            >
              <LogOut size={16} />
              {t("shell.signOut")}
            </button>
            </PopoverContent>
          ) : null}
        </Popover>
      </div>
    </div>
  );
}
