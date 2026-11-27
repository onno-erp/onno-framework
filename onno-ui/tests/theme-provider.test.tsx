import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import { ThemeProvider, useTheme } from "@/providers/theme-provider";
import { BrandingProvider } from "@/providers/branding-provider";
import { LoginLayout } from "@/components/auth/sign-in";
import { api } from "@/lib/api";

/** Stubs the OS colour scheme; `set` flips it and fires `change` as the browser does. */
function stubColorScheme(initial: "light" | "dark") {
  let dark = initial === "dark";
  const listeners = new Set<() => void>();
  vi.stubGlobal("matchMedia", (query: string) => ({
    media: query,
    get matches() { return query === "(prefers-color-scheme: dark)" && dark; },
    addEventListener: (_type: string, listener: () => void) => { if (query === "(prefers-color-scheme: dark)") listeners.add(listener); },
    removeEventListener: (_type: string, listener: () => void) => listeners.delete(listener),
  }));
  return {
    set(next: "light" | "dark") {
      dark = next === "dark";
      act(() => listeners.forEach((listener) => listener()));
    },
    listenerCount: () => listeners.size,
  };
}

function Resolved() {
  return <output aria-label="Resolved theme">{useTheme().resolvedTheme}</output>;
}

const html = document.documentElement;

beforeEach(() => {
  vi.spyOn(api, "getTheme").mockResolvedValue({});
  vi.spyOn(api, "getBranding").mockResolvedValue({});
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  localStorage.clear();
  html.classList.remove("light", "dark");
  html.removeAttribute("style");
});

describe("ThemeProvider", () => {
  it("follows the OS colour scheme while the theme is system", () => {
    localStorage.setItem("onno-ui-theme", "system");
    const scheme = stubColorScheme("light");
    render(<ThemeProvider><Resolved /></ThemeProvider>);
    expect(screen.getByLabelText("Resolved theme")).toHaveTextContent("light");
    expect(html).toHaveClass("light");

    scheme.set("dark");
    expect(screen.getByLabelText("Resolved theme")).toHaveTextContent("dark");
    expect(html).toHaveClass("dark");
    expect(html).not.toHaveClass("light");

    scheme.set("light");
    expect(screen.getByLabelText("Resolved theme")).toHaveTextContent("light");
    expect(html).toHaveClass("light");
  });

  it("keeps an explicit theme whatever the OS prefers", () => {
    localStorage.setItem("onno-ui-theme", "light");
    const scheme = stubColorScheme("light");
    render(<ThemeProvider><Resolved /></ThemeProvider>);
    scheme.set("dark");
    expect(screen.getByLabelText("Resolved theme")).toHaveTextContent("light");
    expect(html).toHaveClass("light");
  });

  it("stops listening to the OS once unmounted", () => {
    localStorage.setItem("onno-ui-theme", "system");
    const scheme = stubColorScheme("light");
    const { unmount } = render(<ThemeProvider><Resolved /></ThemeProvider>);
    expect(scheme.listenerCount()).toBeGreaterThan(0);
    unmount();
    expect(scheme.listenerCount()).toBe(0);
  });

  it.each(["blue", "SYSTEM", ""])("falls back to the dark default for an unknown saved theme %j", (saved) => {
    localStorage.setItem("onno-ui-theme", saved);
    stubColorScheme("light");
    render(<ThemeProvider><Resolved /></ThemeProvider>);
    expect(screen.getByLabelText("Resolved theme")).toHaveTextContent("dark");
    expect(html).toHaveClass("dark");
    expect(html.className).toBe("dark");
  });

  it("is light where media queries are unavailable", () => {
    localStorage.setItem("onno-ui-theme", "system");
    vi.stubGlobal("matchMedia", undefined);
    render(<ThemeProvider><Resolved /></ThemeProvider>);
    expect(screen.getByLabelText("Resolved theme")).toHaveTextContent("light");
  });
});

describe("theme consumers follow an OS change", () => {
  it("re-tints the brand accent for the new mode", async () => {
    localStorage.setItem("onno-ui-theme", "system");
    vi.mocked(api.getBranding).mockResolvedValue({
      palette: { light: { primary: "#2563EB" }, dark: { primary: "#60A5FA" } },
    });
    const scheme = stubColorScheme("light");
    render(<ThemeProvider><BrandingProvider><Resolved /></BrandingProvider></ThemeProvider>);
    await waitFor(() => expect(html.style.getPropertyValue("--accent")).toBe("221 80% 93%"));

    scheme.set("dark");
    expect(html.style.getPropertyValue("--accent")).toBe("213 45% 18%");
  });

  it("swaps the login logo for its dark variant", async () => {
    localStorage.setItem("onno-ui-theme", "system");
    vi.mocked(api.getBranding).mockResolvedValue({ appName: "Acme", logoUrl: "/light.svg", logoUrlDark: "/dark.svg" });
    const scheme = stubColorScheme("light");
    render(<ThemeProvider><BrandingProvider><LoginLayout>Sign in</LoginLayout></BrandingProvider></ThemeProvider>);
    expect(await screen.findByRole("img", { name: "Acme" })).toHaveAttribute("src", "/light.svg");

    scheme.set("dark");
    expect(screen.getByRole("img", { name: "Acme" })).toHaveAttribute("src", "/dark.svg");
  });
});
