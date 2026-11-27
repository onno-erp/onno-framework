import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router";

const { deleteCatalogItem } = vi.hoisted(() => ({ deleteCatalogItem: vi.fn() }));
vi.mock("@/lib/api", async (original) => {
  const actual = await original<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, deleteCatalogItem } };
});
vi.mock("@/lib/presence-store", () => ({
  usePanePresence: vi.fn(), useRecordViewers: () => [], useEntityViewers: () => [], useViewersById: () => new Map(),
}));
vi.mock("@/providers/auth-provider", () => ({ useAuth: () => ({ logout: vi.fn() }) }));
vi.mock("@/providers/theme-provider", () => ({ useTheme: () => ({ theme: "light", setTheme: vi.fn() }) }));
vi.mock("@/providers/branding-provider", () => ({ useBranding: () => ({}) }));
vi.mock("@/hooks/use-ui-events", () => ({ useUiEvents: vi.fn() }));
vi.mock("@/lib/icons", () => ({ ICON_CUSTOM_COMPONENTS: new Map(), setIconActivePath: vi.fn(), Icon: () => null }));
vi.mock("@/components/ui/toast", () => ({
  toast: { success: vi.fn(), error: vi.fn(), loading: vi.fn(), dismiss: vi.fn() },
}));
vi.mock("@/views/content-pane", () => ({
  ContentPane: ({ path, onAction }: { path: string; onAction: (action: { url: string }) => void }) => (
    <div data-testid="record-route">
      {path}
      <button onClick={() => onAction({ url: "onno://delete/catalogs/categories/folder-1" })}>Delete folder</button>
    </div>
  ),
}));

import { ApiError } from "@/lib/api";
import { toast } from "@/components/ui/toast";
import { queryClient } from "@/lib/query";
import { AppShell, clearModelCache } from "@/views/app-shell";
import { appModel, entity } from "./app-shell-fixtures";

beforeEach(() => {
  Object.defineProperty(window, "innerWidth", { configurable: true, value: 1280 });
  vi.stubGlobal("matchMedia", vi.fn(() => ({ matches: false, addEventListener() {}, removeEventListener() {} })));
  vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify(appModel([entity("catalogs", "categories")])),
    { headers: { "Content-Type": "application/json" } })));
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  vi.clearAllMocks();
  deleteCatalogItem.mockReset();
  clearModelCache();
  queryClient.clear();
});

async function deleteFromRecord() {
  render(<MemoryRouter initialEntries={["/catalogs/categories/folder-1"]}><AppShell /></MemoryRouter>);
  fireEvent.click(await screen.findByRole("button", { name: "Delete folder" }));
  fireEvent.click(await screen.findByRole("button", { name: "Delete" }));
  await waitFor(() => expect(deleteCatalogItem).toHaveBeenCalledExactlyOnceWith("categories", "folder-1"));
}

it("shows a parent validation failure once and keeps the rejected folder open", async () => {
  const message = "A folder with active children cannot be deleted or changed to an item";
  deleteCatalogItem.mockRejectedValue(new ApiError(message, 400, { parent: [message] }));
  await deleteFromRecord();
  await waitFor(() => expect(toast.error).toHaveBeenCalledExactlyOnceWith(message));
  expect(screen.getByTestId("record-route")).toHaveTextContent("/catalogs/categories/folder-1");
});

it("does not duplicate a failure already reported by the API layer", async () => {
  const failure = new ApiError("Forbidden", 403);
  failure.notified = true;
  deleteCatalogItem.mockRejectedValue(failure);
  await deleteFromRecord();
  expect(toast.error).not.toHaveBeenCalled();
  expect(screen.getByTestId("record-route")).toHaveTextContent("/catalogs/categories/folder-1");
});
