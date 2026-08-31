import { afterEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { DesktopAccountDock } from "@/components/desktop-account-dock";

const t = (key: string) => ({
  "shell.signedInAs": "Signed in as",
  "shell.accountMenu": "Account menu",
  "shell.appearance": "Appearance",
  "shell.theme": "Theme",
  "shell.themeLight": "Light",
  "shell.themeDark": "Dark",
  "shell.themeSystem": "System",
  "shell.profiles": "Workspace",
  "shell.signOut": "Sign out",
})[key] ?? key;

describe("DesktopAccountDock", () => {
  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it("keeps identity primary and exposes compact shell actions", () => {
    vi.useFakeTimers();
    let enterFrame: FrameRequestCallback | undefined;
    vi.stubGlobal("requestAnimationFrame", vi.fn((callback: FrameRequestCallback) => {
      enterFrame = callback;
      return 1;
    }));
    vi.stubGlobal("cancelAnimationFrame", vi.fn());
    const onThemeToggle = vi.fn();
    const onSignOut = vi.fn();
    render(
      <DesktopAccountDock
        account={{ displayName: "Mara Ellis" }}
        theme="dark"
        surface="#121212"
        border="#242424"
        onThemeChange={onThemeToggle}
        onSignOut={onSignOut}
        onProfileChange={vi.fn()}
        t={t}
      />
    );

    expect(screen.getByText("Mara Ellis")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Account menu" }));
    expect(screen.getByTestId("desktop-account-menu")).not.toHaveClass("is-open");
    act(() => enterFrame?.(16));
    expect(screen.getByTestId("desktop-account-menu")).toHaveClass("is-open");
    expect(screen.getByTestId("desktop-account-menu")).toHaveAttribute("data-side", "right");
    expect(screen.getByTestId("desktop-account-menu")).toHaveAttribute("data-origin", "bottom-left");
    expect(screen.getByRole("menuitemradio", { name: "Dark" })).toHaveAttribute("aria-checked", "true");
    const lightTheme = screen.getByRole("menuitemradio", { name: "Light" });
    lightTheme.focus();
    fireEvent.click(lightTheme);
    expect(screen.getByTestId("desktop-account-menu")).toHaveClass("is-closing");
    expect(screen.getByRole("button", { name: "Account menu" })).toHaveFocus();
    act(() => vi.advanceTimersByTime(200));
    expect(screen.queryByTestId("desktop-account-menu")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Account menu" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "Sign out" }));
    expect(onThemeToggle).toHaveBeenCalledWith("light");
    expect(onSignOut).toHaveBeenCalledOnce();
  });

  it("renders profile choices beneath the fixed account row", () => {
    const onProfileChange = vi.fn();
    render(
      <DesktopAccountDock
        account={{
          displayName: "Mara Ellis",
          profiles: [{ id: "admin", title: "Admin" }, { id: "sales", title: "Sales" }],
          activeProfileId: "sales",
        }}
        theme="system"
        surface="#fff"
        border="#eee"
        onThemeChange={vi.fn()}
        onSignOut={vi.fn()}
        onProfileChange={onProfileChange}
        t={t}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: "Account menu" }));
    fireEvent.click(screen.getByRole("menuitemradio", { name: "Admin" }));
    expect(onProfileChange).toHaveBeenCalledWith("admin");
    expect(screen.getByText("ME")).toBeInTheDocument();
  });
});
