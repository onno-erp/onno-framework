import { afterEach, describe, expect, it, vi } from "vitest";
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
} from "@testing-library/react";
import {
  Navigation,
  NavigationGroup,
  NavigationLink,
  NavigationProvider,
  NavigationHost,
  DefaultNavigation,
} from "@/components/shell/navigation";
import { AppConfigProvider, buildNavigation } from "@/lib/app-config";
import { shellFromModel } from "@/lib/app-model";
import { setModel } from "@/lib/model-store";
import {
  bottomBarItems,
  MobileMenu,
  ShellContext,
} from "@/components/shell/mobile-shell";
import type { AppModel } from "@/lib/app-model";
import type { ShellNavSection } from "@/lib/view-types";

vi.mock("@/components/collaboration/nav-presence", () => ({
  NavPresenceIndicator: () => null,
}));
const model: AppModel = {
  user: { username: "u", displayName: "User", avatarUrl: "", roles: ["USER"] },
  features: { comments: false, notifications: false },
  informationRegisters: [],
  constants: [],
  entities: [
    {
      kind: "documents",
      logicalName: "Orders",
      name: "orders",
      title: "Orders",
      context: "Sales",
      route: "/documents/orders",
      canWrite: true,
    },
  ],
};
const Page = () => null;
const tree: ShellNavSection[] = [
  {
    title: "Sales",
    actions: <button>New order</button>,
    items: [
      {
        title: "Reports",
        items: [
          { title: "Daily", items: [{ label: "Revenue", path: "/revenue" }] },
        ],
      },
    ],
  },
];
afterEach(() => {
  cleanup();
  act(() => setModel(null));
});

describe("navigation trees", () => {
  it("prunes denied nested groups and preserves actions on visible sections", () => {
    const actions = <button>New order</button>;
    const app = {
      pages: [{ path: "/private", component: Page, roles: ["ADMIN"] }],
      navigation: [
        {
          title: "Sales",
          actions,
          items: [
            { title: "Hidden", items: ["/private", "Missing Entity"] },
            {
              title: "Work",
              items: [
                "Orders",
                { title: "Admin", roles: ["ADMIN"], items: ["Orders"] },
              ],
            },
          ],
        },
      ],
    };
    expect(buildNavigation(app, model, (page) => !page.roles?.length)).toEqual([
      {
        title: "Sales",
        icon: undefined,
        actions,
        items: [
          {
            title: "Work",
            icon: undefined,
            items: [
              { label: "Orders", icon: "file-text", path: "/documents/orders" },
            ],
          },
        ],
      },
    ]);
  });

  it("derives home, tab labels and mobile destinations from nested leaves", () => {
    const shell = shellFromModel(
      model,
      { brand: "App", logo: "", mark: "", markFramed: true },
      "sidebar",
      false,
      tree,
    );
    expect(shell.home).toBe("/revenue");
    expect(shell.titles?.["/revenue"]).toBe("Revenue");
    expect(bottomBarItems(tree).map((item) => item.path)).toEqual(["/revenue"]);
  });

  it("reveals nested active routes and leaves section actions independent", () => {
    const navigate = vi.fn();
    render(
      <NavigationProvider
        activePath="/revenue/record"
        mobile={false}
        onNavigate={navigate}
      >
        <DefaultNavigation sections={tree} />
      </NavigationProvider>,
    );
    expect(screen.getByRole("button", { name: "Reports" })).toHaveAttribute(
      "aria-expanded",
      "true",
    );
    expect(screen.getByRole("button", { name: "Daily" })).toHaveAttribute(
      "aria-expanded",
      "true",
    );
    expect(screen.getByRole("button", { name: "Revenue" })).toHaveAttribute(
      "aria-current",
      "page",
    );
    fireEvent.click(screen.getByRole("button", { name: "New order" }));
    expect(navigate).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Reports" }));
    expect(screen.getByRole("button", { name: "Reports" })).toHaveAttribute(
      "aria-expanded",
      "false",
    );
    expect(screen.queryByRole("button", { name: "Revenue" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Reports" }));
    fireEvent.click(screen.getByRole("button", { name: "Revenue" }));
    expect(navigate).toHaveBeenCalledWith("/revenue");
  });

  it("composes hand-authored links with page gates and active ancestor expansion", () => {
    act(() => setModel(model));
    const navigate = vi.fn();
    render(
      <AppConfigProvider
        app={{
          pages: [{ path: "/private", component: Page, roles: ["ADMIN"] }],
        }}
      >
        <NavigationProvider
          activePath="/documents/orders/123"
          mobile={false}
          onNavigate={navigate}
        >
          <Navigation>
            <NavigationGroup title="Work">
              <NavigationLink entity="Orders" />
              <NavigationLink entity="Missing Entity" />
              <NavigationLink path="/private" label="Private" />
            </NavigationGroup>
          </Navigation>
        </NavigationProvider>
      </AppConfigProvider>,
    );
    expect(screen.getByRole("button", { name: "Work" })).toHaveAttribute(
      "aria-expanded",
      "true",
    );
    expect(screen.queryByText("Private")).toBeNull();
    expect(screen.queryByText("Missing Entity")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Orders" }));
    expect(navigate).toHaveBeenCalledWith("/documents/orders");
  });

  it("renders the same nested actions and groups in the phone More menu", () => {
    const onAction = vi.fn();
    const shell = shellFromModel(
      model,
      { brand: "App", logo: "", mark: "", markFramed: true },
      "bottom_bar",
      false,
      tree,
    );
    render(
      <ShellContext.Provider
        value={{
          shell,
          activePath: "/other",
          onAction,
          resolvedTheme: "light",
          t: (key) => key,
        }}
      >
        <MobileMenu />
      </ShellContext.Provider>,
    );
    expect(
      screen.getByRole("button", { name: "New order" }),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Reports" }));
    fireEvent.click(screen.getByRole("button", { name: "Daily" }));
    fireEvent.click(screen.getByRole("button", { name: "Revenue" }));
    expect(onAction).toHaveBeenCalledWith("/revenue");
  });

  it("does not duplicate bottom tabs when a destination is linked in two groups", () => {
    expect(bottomBarItems([...tree, ...tree]).map((item) => item.path)).toEqual(
      ["/revenue"],
    );
  });

  it("mounts a custom navigation component with shell context on mobile", () => {
    const Custom = ({
      navigation,
      mobile,
    }: {
      navigation: ShellNavSection[];
      mobile: boolean;
    }) => (
      <Navigation>
        <p>{mobile ? "Mobile layout" : "Desktop layout"}</p>
        <DefaultNavigation sections={navigation} />
      </Navigation>
    );
    const navigate = vi.fn();
    render(
      <AppConfigProvider app={{ navigationComponent: Custom }}>
        <NavigationHost
          navigation={tree}
          activePath="/revenue"
          mobile={true}
          onNavigate={navigate}
        >
          <p>Default layout</p>
        </NavigationHost>
      </AppConfigProvider>,
    );
    expect(screen.getByText("Mobile layout")).toBeInTheDocument();
    expect(screen.queryByText("Default layout")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Revenue" }));
    expect(navigate).toHaveBeenCalledWith("/revenue");
  });
});
