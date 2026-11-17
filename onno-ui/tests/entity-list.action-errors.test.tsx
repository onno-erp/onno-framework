import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";

// A list action whose failure the api layer leaves to its caller (a network error, field errors,
// typed feedback, a 401) still tells the user once. The real api.ts runs; only fetch, the toast
// sink and heavy list dependencies are stubbed.
const toast = vi.hoisted(() => ({
  error: vi.fn(),
  success: vi.fn(),
  info: vi.fn(),
  warning: vi.fn(),
  loading: vi.fn(() => "loading-1"),
  dismiss: vi.fn(),
}));
vi.mock("@/components/ui/toast", () => ({ toast }));
vi.mock("@/lib/presence-store", () => ({ useViewersById: () => new Map() }));
vi.mock("@/components/maps/list-map-view", () => ({ ListMapView: () => null }));
vi.mock("maplibre-gl", () => ({ default: {} }));

import { EntityListView, type ListDescriptor } from "@/components/lists/entity-list";
import { CommandFeedbackProvider } from "@/lib/command-feedback";

type Route = (url: string, method: string) => Response;

class ResizeObserverMock { observe() {} unobserve() {} disconnect() {} }

const response = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

const networkDown = (): Response => {
  throw new TypeError("Failed to fetch");
};

const rows = [{
  id: "one",
  description: "First",
  _actions: {
    review: { visible: true, enabled: true, title: "Review" },
    approve: { visible: true, enabled: true, title: "Approve" },
  },
}];

/** Stub fetch: every read serves the list's one row; `route` answers the action under test. */
function stubFetch(route: Route) {
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const method = (init?.method ?? "GET").toUpperCase();
    if (method === "GET") return response(200, { rows, total: 1, hasMore: false, nextCursor: null });
    return route(String(input), method);
  }));
}

const only = (path: string, method: string, answer: () => Response): Route => (url, m) => {
  if (url === path && m === method) return answer();
  throw new Error(`unexpected ${m} ${url}`);
};

function list(): ListDescriptor {
  return {
    kind: "catalogs", name: "orders", title: "Orders",
    columns: [{ columnName: "description", label: "Name", width: "" }],
    searchable: false, sort: { column: null, descending: false }, newUrl: "onno://catalogs/orders/new",
    canWrite: true, pageSize: 20, embedded: true, selectionCheckboxes: true,
    commands: [
      { key: "review", title: "Review", target: "record" },
      { key: "approve", title: "Approve", target: "record", form: [{ key: "reason", label: "Reason", type: "text" }] },
    ],
    commandPresentation: { review: { placement: "row" }, approve: { placement: "row" } },
  };
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

/** Select the row and return the selection toolbar. */
async function selectFirst() {
  fireEvent.click(await screen.findByRole("checkbox", { name: "Select First" }));
  return screen.getByRole("group", { name: "Actions for selected" });
}

/** Paste copies of `ids` the way ⌘V hands them to the list. */
function paste(ids: string[]) {
  fireEvent.paste(document, {
    clipboardData: {
      getData: (type: string) =>
        type === "application/x-onno-rows" ? JSON.stringify({ kind: "catalogs", name: "orders", ids }) : "",
    },
  });
}

describe("list actions report a failure once", () => {
  beforeEach(() => {
    vi.stubGlobal("ResizeObserver", ResizeObserverMock);
    vi.stubGlobal("requestAnimationFrame", (cb: FrameRequestCallback) => window.setTimeout(() => cb(0), 0));
    vi.stubGlobal("cancelAnimationFrame", (id: number) => window.clearTimeout(id));
  });
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    vi.clearAllMocks();
  });

  describe("row command", () => {
    it("reports a network failure once", async () => {
      stubFetch(only("/api/commands/catalogs/orders/review", "POST", networkDown));
      render(<EntityListView list={list()} />);
      fireEvent.click(await screen.findByRole("button", { name: "Review" }));

      expect(await toastsAfterFailure()).toEqual([["Failed to fetch"]]);
    });

    it("shows the api layer's toast once when the server rejects it", async () => {
      stubFetch(only("/api/commands/catalogs/orders/review", "POST", () => response(500, { message: "Review service is down" })));
      render(<EntityListView list={list()} />);
      fireEvent.click(await screen.findByRole("button", { name: "Review" }));

      expect(await toastsAfterFailure()).toEqual([["Review service is down"]]);
    });

    it("presents typed feedback in the dialog, not as a toast", async () => {
      stubFetch(only("/api/commands/catalogs/orders/review", "POST", () => response(422, {
        severity: "error",
        title: "Review blocked",
        message: "The order is locked",
      })));
      render(<CommandFeedbackProvider><EntityListView list={list()} /></CommandFeedbackProvider>);
      fireEvent.click(await screen.findByRole("button", { name: "Review" }));

      expect(await screen.findByRole("alertdialog", { name: "Review blocked" })).toBeInTheDocument();
      await settle();
      expect(toast.error).not.toHaveBeenCalled();
    });
  });

  describe("batch command", () => {
    it("reports a network failure once and retires the loading toast", async () => {
      stubFetch(only("/api/commands/catalogs/orders/review/batch", "POST", networkDown));
      render(<EntityListView list={list()} />);
      fireEvent.click(within(await selectFirst()).getByRole("button", { name: "Review" }));

      expect(await toastsAfterFailure()).toEqual([["Failed to fetch"]]);
      expect(toast.dismiss).toHaveBeenCalledWith("loading-1");
    });

    it("shows the api layer's toast once when the server rejects it", async () => {
      stubFetch(only("/api/commands/catalogs/orders/review/batch", "POST", () => response(500, { message: "Review service is down" })));
      render(<EntityListView list={list()} />);
      fireEvent.click(within(await selectFirst()).getByRole("button", { name: "Review" }));

      expect(await toastsAfterFailure()).toEqual([["Review service is down"]]);
    });
  });

  describe("command with a form", () => {
    /** Submit the open command dialog. */
    const submitDialog = async () => {
      const dialog = await screen.findByRole("dialog", { name: "Approve" });
      fireEvent.click(within(dialog).getByRole("button", { name: "Approve" }));
    };

    it("row: the dialog reports a network failure once and stays open", async () => {
      stubFetch(only("/api/commands/catalogs/orders/approve", "POST", networkDown));
      render(<EntityListView list={list()} />);
      fireEvent.click(await screen.findByRole("button", { name: "Approve" }));
      await submitDialog();

      expect(await toastsAfterFailure()).toEqual([["Failed to fetch"]]);
      expect(screen.getByRole("dialog", { name: "Approve" })).toBeInTheDocument();
    });

    it("batch: the dialog reports a network failure once and stays open", async () => {
      stubFetch(only("/api/commands/catalogs/orders/approve/batch", "POST", networkDown));
      render(<EntityListView list={list()} />);
      fireEvent.click(within(await selectFirst()).getByRole("button", { name: "Approve" }));
      await submitDialog();

      expect(await toastsAfterFailure()).toEqual([["Failed to fetch"]]);
      expect(toast.dismiss).toHaveBeenCalledWith("loading-1");
      expect(screen.getByRole("dialog", { name: "Approve" })).toBeInTheDocument();
    });
  });

  describe("batch delete", () => {
    const deleteSelected = async () => {
      render(<EntityListView list={list()} />);
      const toolbar = await selectFirst();
      fireEvent.click(within(toolbar).getByRole("button", { name: "Delete" }));
      fireEvent.click(within(toolbar).getByRole("button", { name: "Sure? Delete 1" }));
    };

    it("reports a network failure once and retires the loading toast", async () => {
      stubFetch(only("/api/catalogs/orders/batch-delete", "POST", networkDown));
      await deleteSelected();

      expect(await toastsAfterFailure()).toEqual([["Failed to fetch"]]);
      expect(toast.dismiss).toHaveBeenCalledWith("loading-1");
    });

    it("shows the api layer's toast once when the server rejects it", async () => {
      stubFetch(only("/api/catalogs/orders/batch-delete", "POST", () => response(403, { message: "Deleting orders is not allowed" })));
      await deleteSelected();

      expect(await toastsAfterFailure()).toEqual([["Deleting orders is not allowed"]]);
    });
  });

  describe("paste copies", () => {
    it("explains a network failure in the one summary toast", async () => {
      stubFetch((url, method) => {
        if (method === "POST" && /^\/api\/catalogs\/orders\/(one|two)\/duplicate$/.test(url)) return networkDown();
        throw new Error(`unexpected ${method} ${url}`);
      });
      render(<EntityListView list={list()} />);
      await screen.findByText("First");
      paste(["one", "two"]);

      const calls = await toastsAfterFailure();
      expect(calls).toHaveLength(1);
      const [title, options] = calls[0];
      expect(title).toBe("Pasted 0/2");
      render(<div data-testid="reason">{options?.description}</div>);
      expect(screen.getByTestId("reason")).toHaveTextContent(/^Failed to fetch$/);
    });

    it("counts the copies that went through and names only the failure", async () => {
      stubFetch((url, method) => {
        if (method === "POST" && url === "/api/catalogs/orders/one/duplicate") return response(200, { id: "copy-1" });
        if (method === "POST" && url === "/api/catalogs/orders/two/duplicate") return networkDown();
        throw new Error(`unexpected ${method} ${url}`);
      });
      render(<EntityListView list={list()} />);
      await screen.findByText("First");
      paste(["one", "two"]);

      const calls = await toastsAfterFailure();
      expect(calls).toHaveLength(1);
      const [title, options] = calls[0];
      expect(title).toBe("Pasted 1/2");
      render(<div data-testid="reason">{options?.description}</div>);
      expect(screen.getByTestId("reason")).toHaveTextContent(/^Failed to fetch$/);
    });

    it("names at most three distinct reasons and elides the rest", async () => {
      const ids = ["a", "b", "c", "d", "e"];
      stubFetch((url, method) => {
        const id = /^\/api\/catalogs\/orders\/(\w)\/duplicate$/.exec(url)?.[1];
        if (method === "POST" && id) {
          return response(400, { error: "validation", message: `Copy of ${id} is invalid`, fieldErrors: { code: ["Taken"] } });
        }
        throw new Error(`unexpected ${method} ${url}`);
      });
      render(<EntityListView list={list()} />);
      await screen.findByText("First");
      paste(ids);

      const calls = await toastsAfterFailure();
      expect(calls).toHaveLength(1);
      expect(calls[0][0]).toBe("Pasted 0/5");
      render(<div data-testid="reason">{calls[0][1]?.description}</div>);
      expect([...screen.getByTestId("reason").querySelectorAll("p")].map((p) => p.textContent)).toEqual([
        "Copy of a is invalid",
        "Copy of b is invalid",
        "Copy of c is invalid",
        "…",
      ]);
    });

    it("does not repeat a failure the api layer has toasted", async () => {
      stubFetch(only("/api/catalogs/orders/one/duplicate", "POST", () => response(500, { message: "Copy failed" })));
      render(<EntityListView list={list()} />);
      await screen.findByText("First");
      paste(["one"]);

      await waitFor(() => expect(toast.error).toHaveBeenCalledTimes(2));
      await settle();
      const [apiToast, summary] = toast.error.mock.calls;
      expect(apiToast).toEqual(["Copy failed"]);
      expect(summary[0]).toBe("Pasted 0/1");
      expect(summary[1]?.description).toBeUndefined();
    });
  });
});
