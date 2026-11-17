import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router";

const { runCommand, refreshed } = vi.hoisted(() => ({
  runCommand: vi.fn(),
  refreshed: vi.fn(),
}));

vi.mock("@/lib/api", () => ({
  api: { runCommand },
  ApiError: class ApiError extends Error {},
}));
vi.mock("@/lib/icons", () => ({ Icon: () => null }));
vi.mock("@/components/actions/command-form-dialog", () => ({
  CommandFormDialog: ({ onSubmit }: { onSubmit: (values: Record<string, string>) => Promise<void> }) => (
    <button onClick={() => void onSubmit({ note: "Test" })}>Submit action form</button>
  ),
}));
// The pane renders its view through RouteView; stand in one data-owning component per route that keeps
// its own view of the route's entity (as lists and forms do) and an uncontrolled draft a remount
// would wipe.
vi.mock("@/views/route-view", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/views/route-view")>();
  const { useRef } = await import("react");
  const { useEntityChanges } = await import("@/lib/query");
  const { Boundary } = await import("@/components/pages/boundary");
  function FakeList({ entity }: { entity: string }) {
    const ref = useRef<HTMLInputElement>(null);
    useEntityChanges(entity, (ids) => refreshed(entity, ids));
    return <input ref={ref} aria-label="Draft" defaultValue="Unsaved draft" />;
  }
  return {
    ...actual,
    RouteView: ({ view }: { view: { list: { entity: string } } }) =>
      <Boundary label="fake"><FakeList entity={view.list.entity} /></Boundary>,
  };
});

import { RecordActions, type RecordAction } from "@/components/actions/record-actions";
import { ContentPane } from "@/views/content-pane";
import { queryClient } from "@/lib/query";

const source = "/documents/orders/o1";
const other = "/catalogs/customers/c1";
let fetchMock: ReturnType<typeof vi.fn>;

function pane(path: string) {
  return <ContentPane path={path} onAction={vi.fn()} skeletonBg="white" />;
}

beforeEach(() => {
  runCommand.mockReset().mockResolvedValue({ refresh: true });
  refreshed.mockClear();
  // Each route's view names the entity its list shows.
  fetchMock = vi.fn().mockImplementation(async (url: string) => new Response(JSON.stringify({
    list: { entity: url.includes("/documents/orders") ? "documents/orders" : "catalogs/customers" },
  }), { status: 200, headers: { "Content-Type": "application/json" } }));
  vi.stubGlobal("fetch", fetchMock);
});
afterEach(() => {
  cleanup();
  queryClient.clear();
  vi.unstubAllGlobals();
});

describe("a command's refresh reaches its entity through the query cache (#447)", () => {
  it.each(["primary", "menu", "form"])("refreshes a %s record command's entity without SSE or a remount", async (placement) => {
    const item: RecordAction = {
      label: "Advance", url: "onno://command/documents/orders/advance/o1",
      placement: placement === "menu" ? "menu" : "primary",
      ...(placement === "form" ? { form: [{ key: "note", label: "Note", type: "text" }] as never } : {}),
    };
    render(<MemoryRouter>{pane(source)}{pane(other)}<RecordActions items={[item]} /></MemoryRouter>);
    await waitFor(() => expect(screen.getAllByLabelText("Draft")).toHaveLength(2));
    const [draft, otherDraft] = screen.getAllByLabelText("Draft");
    fireEvent.change(draft, { target: { value: "My unfinished work" } });

    if (placement === "menu") fireEvent.click(screen.getByRole("button", { name: "More actions" }));
    fireEvent.click(await screen.findByRole("button", { name: "Advance" }));
    if (placement === "form") fireEvent.click(screen.getByRole("button", { name: "Submit action form" }));

    // Only the command's entity hears about it, naming the record the command ran on.
    await waitFor(() => expect(refreshed).toHaveBeenCalledOnce());
    expect(refreshed).toHaveBeenCalledWith("documents/orders", ["o1"]);
    expect(runCommand).toHaveBeenCalledWith("documents", "orders", "advance", "o1",
      placement === "form" ? { note: "Test" } : undefined);
    // The panes neither refetch their views nor remount: drafts survive.
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(screen.getAllByLabelText("Draft")).toEqual([draft, otherDraft]);
    expect(draft).toHaveValue("My unfinished work");
  });

  it("leaves the entity alone when the command asks for no refresh", async () => {
    runCommand.mockResolvedValue({ refresh: false });
    const item: RecordAction = { label: "Advance", url: "onno://command/documents/orders/advance/o1", placement: "primary" };
    render(<MemoryRouter>{pane(source)}<RecordActions items={[item]} /></MemoryRouter>);
    await screen.findByLabelText("Draft");
    fireEvent.click(screen.getByRole("button", { name: "Advance" }));
    await waitFor(() => expect(runCommand).toHaveBeenCalledOnce());
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(refreshed).not.toHaveBeenCalled();
  });
});
