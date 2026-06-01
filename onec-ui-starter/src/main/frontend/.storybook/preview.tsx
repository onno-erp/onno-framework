import { useEffect } from "react";
import type { Preview } from "@storybook/react";
import { useGlobals } from "@storybook/preview-api";
import { MemoryRouter } from "react-router-dom";
import { Toaster } from "sonner";
import { AuthProvider } from "@/providers/auth-provider";
import { ThemeContext, type Theme } from "@/providers/theme-provider";
import { WidgetRegistryProvider } from "@/providers/widget-registry";
import { builtInDashboardWidgets } from "@/views/home";
import "../src/index.css";

const preview: Preview = {
  parameters: {
    layout: "centered",
    backgrounds: { disable: true },
    options: {
      storySort: {
        order: ["Auth", "Layout", "Views", "Primitives"],
      },
    },
  },
  globalTypes: {
    theme: {
      description: "Color scheme",
      defaultValue: "light",
      toolbar: {
        title: "Theme",
        icon: "circlehollow",
        items: [
          { value: "light", title: "Light" },
          { value: "dark", title: "Dark" },
        ],
        dynamicTitle: true,
      },
    },
  },
  decorators: [
    (Story, ctx) => {
      const [globals, updateGlobals] = useGlobals();
      const theme = ((globals.theme as Theme) ?? "light") as Theme;

      useEffect(() => {
        const applied = theme === "system" ? "light" : theme;
        const root = document.documentElement;
        root.classList.remove("light", "dark");
        root.classList.add(applied);
        document.body.style.background =
          applied === "dark" ? "hsl(0 0% 4%)" : "hsl(0 0% 100%)";
      }, [theme]);

      const initialEntries = (ctx.parameters?.router?.initialEntries ?? ["/"]) as string[];
      const setTheme = (next: Theme) => updateGlobals({ theme: next });

      return (
        <MemoryRouter initialEntries={initialEntries}>
          <ThemeContext.Provider value={{ theme, setTheme }}>
            <WidgetRegistryProvider builtInDashboardWidgets={builtInDashboardWidgets}>
              <AuthProvider>
                <div className="bg-background text-foreground">
                  <Story />
                  <Toaster richColors />
                </div>
              </AuthProvider>
            </WidgetRegistryProvider>
          </ThemeContext.Provider>
        </MemoryRouter>
      );
    },
  ],
};

export default preview;
