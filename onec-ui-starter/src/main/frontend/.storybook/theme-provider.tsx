import { createContext, useContext, type ReactNode } from "react";

export type Theme = "light" | "dark" | "system";

interface ThemeCtx {
  theme: Theme;
  setTheme: (t: Theme) => void;
}

export const ThemeContext = createContext<ThemeCtx>({
  theme: "light",
  setTheme: () => {},
});

// Passthrough — the Storybook preview decorator wires the real value via ThemeContext.Provider.
export function ThemeProvider({ children }: { children: ReactNode }) {
  return <>{children}</>;
}

export const useTheme = () => useContext(ThemeContext);
