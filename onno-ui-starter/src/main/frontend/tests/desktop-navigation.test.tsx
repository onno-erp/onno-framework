import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { DesktopNavigation, type ShellNavSection } from "@/components/desktop-navigation";

vi.mock("@/lib/icon-bridge", () => ({
  DynamicLucide: ({ name }: { name: string }) => <svg data-testid={`icon-${name}`} />,
}));

vi.mock("@/lib/nav-presence-bridge", () => ({
  NavPresenceIndicator: ({ path }: { path: string }) => <span data-testid={`presence-${path}`} />,
}));

const navigation: ShellNavSection[] = [
  {
    title: "Inbox",
    icon: "messages-square",
    items: [
      { label: "Inbox", icon: "inbox", path: "/inbox" },
      { label: "Customers", icon: "users", path: "/catalogs/customers" },
    ],
  },
  {
    title: "Sales",
    icon: "chart-column",
    items: [{ label: "Pipeline", icon: "columns-3", path: "/pipeline" }],
  },
];

const t = (key: string) =>
  ({
    "shell.menu": "Menu",
    "shell.collapseNavigation": "Collapse navigation",
    "shell.expandNavigation": "Expand navigation",
    "nav.dashboard": "Dashboard",
  })[key] ?? key;

function renderNavigation(activePath = "/inbox", markFramed = true) {
  const onNavigate = vi.fn();
  const onSectionFocus = vi.fn();
  const result = render(
    <DesktopNavigation
      brand="Onno Desk"
      mark="/branding/mark.svg"
      markFramed={markFramed}
      navigation={navigation}
      activePath={activePath}
      home="/inbox"
      onNavigate={onNavigate}
      onSectionFocus={onSectionFocus}
      account={(compact) => <div>{compact ? "Compact account" : "Account"}</div>}
      notification={<div>Notifications</div>}
      surface="#fff"
      border="#eee"
      primary="#6757f5"
      primarySoft="#f0eeff"
      t={t}
    />
  );
  return { ...result, onNavigate, onSectionFocus };
}

function renderNavigationWithStandalone(activePath = "/inbox") {
  const onNavigate = vi.fn();
  const onSectionFocus = vi.fn();
  const result = render(
    <DesktopNavigation
      brand="Onno Desk"
      navigation={[
        ...navigation,
        {
          items: [{ label: "Dashboard", icon: "house", path: "/" }],
        },
      ]}
      activePath={activePath}
      home="/inbox"
      onNavigate={onNavigate}
      onSectionFocus={onSectionFocus}
      account={(compact) => <div>{compact ? "Compact account" : "Account"}</div>}
      notification={<div>Notifications</div>}
      surface="#fff"
      border="#eee"
      primary="#6757f5"
      primarySoft="#f0eeff"
      t={t}
    />
  );
  return { ...result, onNavigate, onSectionFocus };
}

describe("DesktopNavigation", () => {
  beforeEach(() => window.localStorage.clear());
  afterEach(cleanup);

  it("renders the active Layout section as a nested drawer and navigates its items", () => {
    const { onNavigate } = renderNavigation();

    expect(screen.getByTestId("desktop-navigation")).toHaveAttribute("data-expanded", "true");
    expect(screen.getByTestId("desktop-navigation")).toHaveStyle({ width: "280px" });
    expect(screen.getByTestId("desktop-navigation-drawer-header")).toHaveClass("h-11");
    expect(screen.getByRole("heading", { name: "Inbox" })).toBeInTheDocument();
    expect(screen.getByRole("navigation", { name: "Inbox" })).toBeInTheDocument();
    expect(screen.getByTestId("icon-inbox").parentElement).not.toHaveClass("text-muted-foreground");
    expect(screen.getByTestId("icon-users").parentElement).toHaveClass("text-muted-foreground");
    expect(screen.queryByText("Onno Desk")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Onno Desk" }).querySelector("img")).toHaveAttribute(
      "src",
      "/branding/mark.svg"
    );
    expect(screen.getByTestId("desktop-navigation-notifications")).toHaveTextContent("Notifications");
    expect(screen.getByRole("button", { name: "Collapse navigation" })).toBeInTheDocument();
    expect(screen.getByTestId("desktop-navigation-drawer")).not.toContainElement(
      screen.getByTestId("desktop-navigation-account")
    );
    fireEvent.click(screen.getByRole("button", { name: "Customers" }));
    expect(onNavigate).toHaveBeenCalledWith("/catalogs/customers");
  });

  it("falls back to the brand initial when the configured mark cannot load", () => {
    renderNavigation();

    const home = screen.getByRole("button", { name: "Onno Desk" });
    fireEvent.error(home.querySelector("img")!);

    expect(home).toHaveTextContent("O");
    expect(home.querySelector("img")).not.toBeInTheDocument();
  });

  it("can render supplied mark artwork without a shell frame", () => {
    renderNavigation("/inbox", false);

    expect(screen.getByRole("button", { name: "Onno Desk" })).not.toHaveClass("border");
  });

  it("switches drawers without marking a second workspace active and persists collapse", () => {
    const { onNavigate, onSectionFocus } = renderNavigation();

    fireEvent.click(screen.getByRole("button", { name: "Sales" }));
    expect(onNavigate).not.toHaveBeenCalled();
    expect(onSectionFocus).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("button", { name: "Inbox" })).toHaveStyle({
      background: "#f0eeff",
      color: "#6757f5",
    });
    expect(screen.getByRole("button", { name: "Sales" })).not.toHaveStyle({
      background: "#f0eeff",
      color: "#6757f5",
    });
    expect(screen.getByRole("button", { name: "Inbox" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("button", { name: "Sales" })).not.toHaveAttribute("aria-current");
    expect(screen.getByRole("heading", { name: "Sales" })).toBeInTheDocument();
    expect(screen.getByText("Pipeline")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Sales" }));
    expect(screen.getByTestId("desktop-navigation")).toHaveAttribute("data-expanded", "false");
    expect(screen.getByTestId("desktop-navigation")).toHaveStyle({ width: "64px" });
    expect(screen.getByTestId("desktop-navigation-drawer").parentElement).toHaveClass("invisible");
    expect(window.localStorage.getItem("onno.desktop-navigation.expanded")).toBe("false");
    expect(screen.getByRole("button", { name: "Inbox" })).not.toHaveStyle({
      background: "#f0eeff",
    });
    expect(screen.getByRole("button", { name: "Sales" })).not.toHaveStyle({
      background: "#f0eeff",
    });
    expect(screen.getByRole("button", { name: "Inbox" })).toHaveClass("text-muted-foreground");
    expect(screen.getByRole("button", { name: "Inbox" })).not.toHaveClass("text-primary");
    expect(screen.getByRole("button", { name: "Sales" })).toHaveClass("text-muted-foreground");
    expect(screen.getByRole("button", { name: "Sales" })).not.toHaveClass("text-primary");
  });

  it("opens on the section that owns a detail route", () => {
    renderNavigation("/pipeline/42");
    expect(screen.getByText("Pipeline")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Pipeline" })).toHaveAttribute("aria-current", "page");
  });

  it("navigates a standalone item directly without showing a drawer", () => {
    const { onNavigate, onSectionFocus } = renderNavigationWithStandalone();

    fireEvent.click(screen.getByRole("button", { name: "Dashboard" }));

    expect(onNavigate).toHaveBeenCalledWith("/");
    expect(onSectionFocus).not.toHaveBeenCalled();
    expect(screen.getByTestId("desktop-navigation")).toHaveAttribute("data-expanded", "false");
    expect(screen.getByTestId("desktop-navigation")).toHaveStyle({ width: "64px" });
    expect(screen.getByTestId("desktop-navigation-drawer").parentElement).toHaveClass("invisible");
    expect(screen.queryByRole("navigation", { name: "Dashboard" })).not.toBeInTheDocument();
    expect(screen.getByTestId("desktop-navigation-account")).toHaveTextContent("Compact account");
    expect(screen.queryByRole("button", { name: "Collapse navigation" })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Sales" }));

    expect(screen.getByTestId("desktop-navigation")).toHaveAttribute("data-expanded", "true");
    expect(screen.getByRole("heading", { name: "Sales" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Collapse navigation" })).toBeInTheDocument();
    expect(screen.getByTestId("desktop-navigation-account")).toHaveTextContent("Account");
  });

  it("falls back to highlighting the selected section when no active tab maps to navigation", () => {
    renderNavigation("");

    const inbox = screen.getByTestId("icon-messages-square").parentElement!;
    const sales = screen.getByTestId("icon-chart-column").parentElement!;
    expect(inbox).toHaveAttribute("data-navigation-state", "section-active");
    expect(inbox).toHaveStyle({ background: "#f0eeff", color: "#6757f5" });
    expect(sales).toHaveAttribute("data-navigation-state", "inactive");

    fireEvent.click(sales);

    expect(inbox).toHaveAttribute("data-navigation-state", "inactive");
    expect(sales).toHaveAttribute("data-navigation-state", "section-active");
    expect(sales).toHaveStyle({ background: "#f0eeff", color: "#6757f5" });
  });
});
