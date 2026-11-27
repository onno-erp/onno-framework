import { createContext, useContext, useEffect, useMemo, useState, useSyncExternalStore } from "react";
import { api } from "../lib/api";

type Theme = "light" | "dark" | "system";

/** The theme actually on screen: the chosen one, or the OS preference while it is `system`. */
type ResolvedTheme = "light" | "dark";

interface ThemeCtx {
  theme: Theme;
  /**
   * The light/dark mode in effect. The only place the UI reads the OS colour scheme: it follows a
   * change of the OS preference while {@link theme} is `system`, so read it here rather than
   * querying `prefers-color-scheme` yourself.
   */
  resolvedTheme: ResolvedTheme;
  setTheme: (t: Theme) => void;
}

const ThemeContext = createContext<ThemeCtx>({
  theme: "system",
  resolvedTheme: "light",
  setTheme: () => {},
});

const DARK_SCHEME_QUERY = "(prefers-color-scheme: dark)";

function canMatchMedia() {
  return typeof window !== "undefined" && typeof window.matchMedia === "function";
}

function subscribeSystemTheme(onChange: () => void) {
  if (!canMatchMedia()) return () => {};
  const list = window.matchMedia(DARK_SCHEME_QUERY);
  list.addEventListener("change", onChange);
  return () => list.removeEventListener("change", onChange);
}

function systemTheme(): ResolvedTheme {
  return canMatchMedia() && window.matchMedia(DARK_SCHEME_QUERY).matches ? "dark" : "light";
}

const serverSystemTheme = (): ResolvedTheme => "light";

/** The saved choice; anything unknown (an edited or older value) falls back to the default, dark. */
function storedTheme(value: string | null): Theme {
  return value === "light" || value === "dark" || value === "system" ? value : "dark";
}

export function ThemeProvider({ children }: { children: React.ReactNode }) {
  const [theme, setTheme] = useState<Theme>(() => storedTheme(localStorage.getItem("onno-ui-theme")));
  const system = useSyncExternalStore(subscribeSystemTheme, systemTheme, serverSystemTheme);
  const resolvedTheme: ResolvedTheme = theme === "system" ? system : theme;

  // Apply backend theme CSS variables on mount
  useEffect(() => {
    api.getTheme().then((vars) => {
      const root = document.documentElement;
      Object.entries(vars).forEach(([key, value]) => {
        root.style.setProperty(`--${key}`, value);
      });
    }).catch(() => {
      // Backend theme not configured — use CSS defaults
    });
  }, []);

  // Apply dark/light class
  useEffect(() => {
    const root = document.documentElement;
    root.classList.remove("light", "dark");
    root.classList.add(resolvedTheme);
  }, [resolvedTheme]);

  useEffect(() => {
    localStorage.setItem("onno-ui-theme", theme);
  }, [theme]);

  const value = useMemo(() => ({ theme, resolvedTheme, setTheme }), [theme, resolvedTheme]);
  return (
    <ThemeContext.Provider value={value}>
      {children}
    </ThemeContext.Provider>
  );
}

export const useTheme = () => useContext(ThemeContext);
