import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import type { ViewModel } from "@/lib/view-types";
import { RouteView } from "@/views/route-view";
import { setModel } from "@/lib/model-store";
import { AppConfigProvider, type AppDefinition } from "@/lib/app-config";
import type { AppModel } from "@/lib/app-model";

// The surfaces' renderers are stubbed: these tests are about the page RouteView draws around them.
vi.mock("@/components/entities/record-form", () => ({
  RecordForm: () => <div data-testid="form" />,
  formTitle: () => "Order",
}));
vi.mock("@/components/lists/entity-list", () => ({ EntityListView: () => <div data-testid="list" /> }));
vi.mock("@/components/lists/register-surface", () => ({ RegisterSurface: () => <div data-testid="register" /> }));
vi.mock("@/components/collaboration/comments", () => ({
  CommentThread: ({ target }: { target: { id: string } }) => <div data-testid="comments">{target.id}</div>,
}));

function model(comments: boolean): AppModel {
  return {
    user: { username: "u", displayName: "U", avatarUrl: "", roles: [], permissions: [], unrestricted: false },
    roles: [],
    features: { comments, notifications: false },
    entities: [{ kind: "documents", name: "orders", logicalName: "Orders", title: "Orders", context: "",
      route: "/documents/orders", canWrite: true }],
    informationRegisters: [],
    constants: [],
  };
}

const formView = (id: string | null): ViewModel => ({
  form: { kind: "documents", name: "orders", id, meta: { name: "Orders", attributes: [] }, initial: {} },
});

function renderView(view: ViewModel, app: AppDefinition = {}) {
  return render(<AppConfigProvider app={app}><RouteView view={view} /></AppConfigProvider>);
}

afterEach(cleanup);

describe("RouteView", () => {
  it("renders each kind of default surface from its descriptor", () => {
    setModel(model(false));
    renderView({ list: { kind: "documents", name: "orders", columns: [] } });
    expect(screen.getByTestId("list")).toBeInTheDocument();
    cleanup();
    renderView({ register: { title: "Stock" } });
    expect(screen.getByTestId("register")).toBeInTheDocument();
  });

  it("shows a record's comments only when the entity's customization asks for them", () => {
    setModel(model(true));
    renderView(formView("o1"));
    expect(screen.getByTestId("form")).toBeInTheDocument();
    expect(screen.queryByTestId("comments")).not.toBeInTheDocument();
    cleanup();

    renderView(formView("o1"), { entities: { Orders: { comments: true } } });
    expect(screen.getByTestId("comments")).toHaveTextContent("o1");
    cleanup();

    // Not on a New form, and not when the comments module is off.
    renderView(formView(null), { entities: { Orders: { comments: true } } });
    expect(screen.queryByTestId("comments")).not.toBeInTheDocument();
    cleanup();
    setModel(model(false));
    renderView(formView("o1"), { entities: { Orders: { comments: true } } });
    expect(screen.queryByTestId("comments")).not.toBeInTheDocument();
  });
});
