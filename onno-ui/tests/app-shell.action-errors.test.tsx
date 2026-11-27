import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router";

// The shell's onno:// record actions (delete after confirmation, a command or post link) tell the
// user once about a failure the api layer leaves to its caller. The real api.ts runs; only fetch,
// the toast sink and the shell's heavy collaborators are stubbed.
const toast = vi.hoisted(() => ({
  error: vi.fn(),
  success: vi.fn(),
  info: vi.fn(),
  warning: vi.fn(),
  loading: vi.fn(() => "loading-1"),
  dismiss: vi.fn(),
}));
vi.mock("@/components/ui/toast", () => ({ toast }));
vi.mock("@/lib/presence-store", () => ({
  usePanePresence: vi.fn(), useRecordViewers: () => [], useEntityViewers: () => [],
  useViewersById: () => new Map()
}));
vi.mock("@/providers/auth-provider", () => ({ useAuth: () => ({ logout: vi.fn() }) }));
vi.mock("@/providers/theme-provider", () => ({ useTheme: () => ({ theme: "light", setTheme: vi.fn() }) }));
vi.mock("@/providers/branding-provider", () => ({ useBranding: () => ({}) }));
vi.mock("@/hooks/use-ui-events", () => ({ useUiEvents: vi.fn() }));
vi.mock("@/lib/icons", () => ({
  ICON_CUSTOM_COMPONENTS: new Map(), setIconActivePath: vi.fn(), Icon: () => null,
}));
// The open record hands the shell its onno:// actions, as a record surface's header does.
vi.mock("@/views/content-pane", () => ({
  ContentPane: ({ onAction }: { onAction: (action: { url: string }) => void }) => <div>
    {[
      "onno://delete/documents/orders/o1",
      "onno://delete/catalogs/customers/c1",
      "onno://command/documents/orders/advance/o1",
      "onno://post/orders/o1",
    ].map((url) => <button key={url} onClick={() => onAction({ url })}>{url}</button>)}
  </div>,
}));

import { AppShell, clearModelCache } from "@/views/app-shell";
import { CommandFeedbackProvider } from "@/lib/command-feedback";
import { queryClient } from "@/lib/query";
import { appModel } from "./app-shell-fixtures";

type Route = (url: string, method: string) => Response;

const response = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

const networkDown = (): Response => {
  throw new TypeError("Failed to fetch");
};

const only = (path: string, method: string, answer: () => Response): Route => (url, m) => {
  if (url === path && m === method) return answer();
  throw new Error(`unexpected ${m} ${url}`);
};

const record = "/documents/orders/o1";

/** Render the shell on an open record; the model serves every read, `route` the action under test. */
async function renderShell(route: Route) {
  Object.defineProperty(window, "innerWidth", { configurable: true, value: 1280 });
  vi.stubGlobal("matchMedia", vi.fn(() => ({ matches: false, addEventListener() {}, removeEventListener() {} })));
  vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) => {
    const method = (init?.method ?? "GET").toUpperCase();
    return method === "GET" ? response(200, appModel()) : route(String(url), method);
  }));
  render(
    <MemoryRouter initialEntries={[record]}>
      <CommandFeedbackProvider><AppShell /></CommandFeedbackProvider>
    </MemoryRouter>
  );
  await waitFor(() => expect(document.querySelector(`[data-tab="${record}"]`)).not.toBeNull());
}

/** Run one of the record's onno:// actions. */
function runAction(url: string) {
  fireEvent.click(screen.getAllByRole("button", { name: url })[0]);
}

/** Let any late second toast land before asserting there is exactly one. */
async function settle() {
  await new Promise((resolve) => setTimeout(resolve, 0));
}

/** Wait for the first error toast, then make sure no second one follows. */
async function toastsAfterFailure() {
  await waitFor(() => expect(toast.error).toHaveBeenCalled());
  await settle();
  return toast.error.mock.calls;
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.clearAllMocks();
  clearModelCache();
  queryClient.clear();
});

describe("shell record actions report a failure once", () => {
  it("delete document: reports a network failure once", async () => {
    await renderShell(only("/api/documents/orders/o1", "DELETE", networkDown));
    runAction("onno://delete/documents/orders/o1");
    fireEvent.click(await screen.findByRole("button", { name: "Delete" }));

    expect(await toastsAfterFailure()).toEqual([["Failed to fetch"]]);
  });

  it("delete item: reports a network failure once", async () => {
    await renderShell(only("/api/catalogs/customers/c1", "DELETE", networkDown));
    runAction("onno://delete/catalogs/customers/c1");
    fireEvent.click(await screen.findByRole("button", { name: "Delete" }));

    expect(await toastsAfterFailure()).toEqual([["Failed to fetch"]]);
  });

  it("delete: shows the api layer's toast once when the server refuses", async () => {
    await renderShell(only("/api/documents/orders/o1", "DELETE", () => response(409, { message: "Unpost the document first" })));
    runAction("onno://delete/documents/orders/o1");
    fireEvent.click(await screen.findByRole("button", { name: "Delete" }));

    expect(await toastsAfterFailure()).toEqual([["Unpost the document first"]]);
  });

  it("command link: reports a network failure once and retires the loading toast", async () => {
    await renderShell(only("/api/commands/documents/orders/advance", "POST", networkDown));
    runAction("onno://command/documents/orders/advance/o1");

    expect(await toastsAfterFailure()).toEqual([["Failed to fetch"]]);
    expect(toast.dismiss).toHaveBeenCalledWith("loading-1");
  });

  it("post link: reports a network failure once", async () => {
    await renderShell(only("/api/documents/orders/o1/post", "POST", networkDown));
    runAction("onno://post/orders/o1");

    expect(await toastsAfterFailure()).toEqual([["Failed to fetch"]]);
  });

  it("post link: presents typed feedback in the dialog, not as a toast", async () => {
    await renderShell(only("/api/documents/orders/o1/post", "POST", () => response(422, {
      severity: "error",
      title: "Posting blocked",
      message: "The period is closed",
    })));
    runAction("onno://post/orders/o1");

    expect(await screen.findByRole("alertdialog", { name: "Posting blocked" })).toBeInTheDocument();
    await settle();
    expect(toast.error).not.toHaveBeenCalled();
  });
});
