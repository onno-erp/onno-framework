import { useState } from "react";
import { ChevronUp, LogOut, Moon, Sun } from "lucide-react";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { Badge } from "@/components/ui/badge";
import { Separator } from "@/components/ui/separator";
import { useAuth } from "@/providers/auth-provider";
import { useTheme } from "@/providers/theme-provider";
import { cn } from "@/lib/utils";

function initials(name: string): string {
  const trimmed = name.trim();
  if (!trimmed) return "?";
  const parts = trimmed.split(/[\s._-]+/).filter(Boolean);
  if (parts.length >= 2) {
    return (parts[0][0] + parts[1][0]).toUpperCase();
  }
  return trimmed.slice(0, 2).toUpperCase();
}

interface AccountMenuProps {
  onAfterSignOut?: () => void;
}

export function AccountMenu({ onAfterSignOut }: AccountMenuProps) {
  const { user, logout } = useAuth();
  const { theme, setTheme } = useTheme();
  const [open, setOpen] = useState(false);

  if (!user) return null;

  const displayRoles = user.roles.map((r) => r.replace(/^ROLE_/, ""));
  const isDark = theme === "dark";

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger
        className={cn(
          "flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left",
          "text-[13px] hover:bg-accent transition-colors",
          "data-[state=open]:bg-accent"
        )}
      >
        <span
          aria-hidden
          className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-primary text-[11px] font-medium text-primary-foreground"
        >
          {initials(user.username)}
        </span>
        <span className="flex-1 truncate font-medium">{user.username}</span>
        <ChevronUp className="h-3.5 w-3.5 text-muted-foreground" />
      </PopoverTrigger>

      <PopoverContent
        align="start"
        side="top"
        sideOffset={8}
        className="w-60 p-0"
      >
        <div className="px-3 pt-3 pb-2">
          <div className="flex items-center gap-2">
            <span
              aria-hidden
              className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-primary text-xs font-medium text-primary-foreground"
            >
              {initials(user.username)}
            </span>
            <div className="min-w-0 flex-1">
              <div className="truncate text-sm font-medium">{user.username}</div>
              <div className="text-xs text-muted-foreground">Signed in</div>
            </div>
          </div>
        </div>

        <Separator />

        <div className="px-3 py-2">
          <div className="text-[10px] font-medium uppercase tracking-wide text-muted-foreground">
            Roles
          </div>
          <div className="mt-1.5 flex flex-wrap gap-1">
            {displayRoles.length > 0 ? (
              displayRoles.map((role) => (
                <Badge key={role} variant="secondary">
                  {role}
                </Badge>
              ))
            ) : (
              <span className="text-xs text-muted-foreground">No roles</span>
            )}
          </div>
        </div>

        <Separator />

        <div className="p-1">
          <button
            onClick={() => setTheme(isDark ? "light" : "dark")}
            className="flex w-full items-center gap-2 rounded-md px-2.5 py-1.5 text-[13px] text-foreground hover:bg-accent transition-colors"
          >
            {isDark ? <Sun className="h-3.5 w-3.5" /> : <Moon className="h-3.5 w-3.5" />}
            <span className="flex-1 text-left">{isDark ? "Light mode" : "Dark mode"}</span>
          </button>
          <button
            onClick={async () => {
              setOpen(false);
              await logout();
              onAfterSignOut?.();
            }}
            className="flex w-full items-center gap-2 rounded-md px-2.5 py-1.5 text-[13px] text-foreground hover:bg-accent transition-colors"
          >
            <LogOut className="h-3.5 w-3.5" />
            <span className="flex-1 text-left">Sign out</span>
          </button>
        </div>
      </PopoverContent>
    </Popover>
  );
}
