import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";

// A record header action (Post, a record command) whose failure the api layer leaves to its caller
// still tells the user once. The real api.ts runs; only fetch and the toast sink are stubbed.
const toast = vi.hoisted(() => ({
  error: vi.fn(),
  success: vi.fn(),
  info: vi.fn(),
  warning: vi.fn(),
  loading: vi.fn(),
  dismiss: vi.fn(),
}));
vi.mock("@/components/ui/toast", () => ({ toast }));
vi.mock("@/lib/icons", () => ({ Icon: () => null }));

import { RecordActions, type RecordAction } from "@/components/actions/record-actions";
import { CommandFeedbackProvider } from "@/lib/command-feedback";

type Route = (url: string, method: string) => Response;

const response = (status: number, body: unknown) => new Response(JSON.stringify(body), { status });

const networkDown = (): Response => {
  throw new TypeError("Failed to fetch");
};

function stubFetch(route: Route) {
  vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) =>
    route(url, (init?.method ?? "GET").toUpperCase())));
}

const only = (path: string, method: string, answer: () => Response): Route => (url, m) => {
  if (url === path && m === method) return answer();
  throw new Error(`unexpected ${m} ${url}`);
};

const post: RecordAction = { label: "Post", url: "onno://post/orders/o1", placement: "primary" };
const advance: RecordAction = { label: "Advance", url: "onno://command/documents/orders/advance/o1", placement: "primary" };

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
});

describe("record header actions report a failure once", () => {
  it("post: reports a network failure once", async () => {
    stubFetch(only("/api/documents/orders/o1/post", "POST", networkDown));
    render(<RecordActions items={[post]} />);
    fireEvent.click(screen.getByRole("button", { name: "Post" }));

    expect(await toastsAfterFailure()).toEqual([["Failed to fetch"]]);
  });

  it("post: reports a posting rule bound to a field, which the api layer leaves to it, once", async () => {
    stubFetch(only("/api/documents/orders/o1/post", "POST", () => response(400, {
      error: "validation",
      message: "Quantity must be positive",
      fieldErrors: { quantity: ["Quantity must be positive"] },
    })));
    render(<RecordActions items={[post]} />);
    fireEvent.click(screen.getByRole("button", { name: "Post" }));

    expect(await toastsAfterFailure()).toEqual([["Quantity must be positive"]]);
  });

  it("post: shows the api layer's toast once when the server rejects it", async () => {
    stubFetch(only("/api/documents/orders/o1/post", "POST", () => response(409, { message: "Not enough stock" })));
    render(<RecordActions items={[post]} />);
    fireEvent.click(screen.getByRole("button", { name: "Post" }));

    expect(await toastsAfterFailure()).toEqual([["Not enough stock"]]);
  });

  it("command: reports a network failure once", async () => {
    stubFetch(only("/api/commands/documents/orders/advance", "POST", networkDown));
    render(<RecordActions items={[advance]} />);
    fireEvent.click(screen.getByRole("button", { name: "Advance" }));

    expect(await toastsAfterFailure()).toEqual([["Failed to fetch"]]);
  });

  it("command with a form: the dialog reports a network failure once and stays open", async () => {
    stubFetch(only("/api/commands/documents/orders/approve", "POST", networkDown));
    const approve: RecordAction = {
      label: "Approve",
      url: "onno://command/documents/orders/approve/o1",
      placement: "primary",
      form: [{ key: "reason", label: "Reason", type: "text" }],
    };
    render(<RecordActions items={[approve]} />);
    fireEvent.click(screen.getByRole("button", { name: "Approve" }));
    const dialog = await screen.findByRole("dialog", { name: "Approve" });
    fireEvent.click(within(dialog).getByRole("button", { name: "Approve" }));

    expect(await toastsAfterFailure()).toEqual([["Failed to fetch"]]);
    expect(screen.getByRole("dialog", { name: "Approve" })).toBeInTheDocument();
  });

  it("command: presents typed feedback in the dialog, not as a toast", async () => {
    stubFetch(only("/api/commands/documents/orders/advance", "POST", () => response(422, {
      severity: "error",
      title: "Advance blocked",
      message: "The order is on hold",
    })));
    render(<CommandFeedbackProvider><RecordActions items={[advance]} /></CommandFeedbackProvider>);
    fireEvent.click(screen.getByRole("button", { name: "Advance" }));

    expect(await screen.findByRole("alertdialog", { name: "Advance blocked" })).toBeInTheDocument();
    await settle();
    expect(toast.error).not.toHaveBeenCalled();
  });
});
