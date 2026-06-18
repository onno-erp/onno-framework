import { createContext, useContext, useEffect, useState } from "react";
import { api } from "@/lib/api";

type Theme = "light" | "dark" | "system";

interface ThemeCtx {
  theme: Theme;
  setTheme: (t: Theme) => void;
}

const ThemeContext = createContext<ThemeCtx>({
  theme: "system",
  setTheme: () => {},
});

export function ThemeProvider({ children }: { children: React.ReactNode }) {
  const [theme, setTheme] = useState<Theme>(
    () => (localStorage.getItem("onno-ui-theme") as Theme) || "dark"
  );

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

    if (theme === "system") {
      const sys = window.matchMedia("(prefers-color-scheme: dark)").matches
        ? "dark"
        : "light";
      root.classList.add(sys);
    } else {
      root.classList.add(theme);
    }

    localStorage.setItem("onno-ui-theme", theme);
  }, [theme]);

  return (
    <ThemeContext.Provider value={{ theme, setTheme }}>
      {children}
    </ThemeContext.Provider>
  );
}

export const useTheme = () => useContext(ThemeContext);
