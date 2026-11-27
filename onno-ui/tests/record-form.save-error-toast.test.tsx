import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";

// #505: a failed save shows exactly one message — the form's own "Couldn't save" toast, or the
// inline field errors — never the api layer's toast on top. The real api.ts runs; only fetch,
// the toast sink and heavy field widgets are stubbed.
const toast = vi.hoisted(() => ({
  error: vi.fn(),
  success: vi.fn(),
  info: vi.fn(),
  warning: vi.fn(),
  loading: vi.fn(),
  dismiss: vi.fn(),
}));
vi.mock("@/components/ui/toast", () => ({ toast }));

vi.mock("@/components/fields/ref-select", () => ({ RefSelect: () => null }));
vi.mock("@/components/fields/date-picker", () => ({ DatePicker: () => null }));
vi.mock("@/components/maps/map-editor", () => ({ MapEditor: () => null }));
vi.mock("@/components/fields/image-picker", () => ({ ImagePicker: () => null, GalleryPicker: () => null }));
vi.mock("@/components/fields/file-picker", () => ({ FilePicker: () => null }));
vi.mock("@/components/actions/record-actions", () => ({ RecordActions: () => null }));

import { RecordForm, type FormDescriptor } from "@/components/entities/record-form";

const response = (status: number, body: unknown) => new Response(JSON.stringify(body), { status });

/** Stub fetch: the record PUT answers `save`; live validation passes; anything else is a bug. */
function stubFetch(save: () => Response) {
  vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) => {
    const method = (init?.method ?? "GET").toUpperCase();
    if (url === "/api/catalogs/episodes/episode-1" && method === "PUT") return save();
    if (url.endsWith("/validate")) return response(200, { valid: true, fieldErrors: {}, formErrors: [] });
    if (url.startsWith("/api/checks/")) return response(200, []);
    throw new Error(`unexpected ${method} ${url}`);
  }));
}

const form: FormDescriptor = {
  kind: "catalogs",
  name: "episodes",
  id: "episode-1",
  meta: {
    name: "Episodes",
    attributes: [{
      fieldName: "title",
      columnName: "title",
      displayName: "Title",
      javaType: "String",
      length: 0,
      scale: 0,
      required: false,
      visibleInForm: true,
      order: 0,
    } as never],
  },
  initial: { id: "episode-1", title: "Pilot" },
};

/** Let any late second toast land before asserting there is exactly one. */
async function settle() {
  await new Promise((resolve) => setTimeout(resolve, 0));
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.clearAllMocks();
});

describe("RecordForm save failure (#505)", () => {
  it("shows only the form's own 'Couldn't save' toast", async () => {
    stubFetch(() => response(500, { message: "Database unavailable" }));

    render(<RecordForm form={form} />);
    fireEvent.click(screen.getByRole("button", { name: "Write" }));

    await waitFor(() => expect(toast.error).toHaveBeenCalled());
    await settle();
    expect(toast.error).toHaveBeenCalledTimes(1);
    expect(toast.error).toHaveBeenCalledWith("Couldn't save: Database unavailable");
  });

  it("shows field errors inline with no toast at all", async () => {
    stubFetch(() => response(400, {
      error: "validation",
      message: "Title is already taken",
      fieldErrors: { title: ["Title is already taken"] },
    }));

    render(<RecordForm form={form} />);
    fireEvent.click(screen.getByRole("button", { name: "Write" }));

    expect(await screen.findByText("Title is already taken")).toBeInTheDocument();
    await settle();
    expect(toast.error).not.toHaveBeenCalled();
  });

  it("keeps its 'Couldn't save' context for a network failure", async () => {
    stubFetch(() => {
      throw new TypeError("Failed to fetch");
    });

    render(<RecordForm form={form} />);
    fireEvent.click(screen.getByRole("button", { name: "Write" }));

    await waitFor(() => expect(toast.error).toHaveBeenCalled());
    await settle();
    expect(toast.error.mock.calls).toEqual([["Couldn't save: Failed to fetch"]]);
  });

  it("reports typed feedback, which the api layer leaves to the form, once under its own title", async () => {
    stubFetch(() => response(409, {
      severity: "error",
      title: "Record locked",
      message: "Locked by an import",
    }));

    render(<RecordForm form={form} />);
    fireEvent.click(screen.getByRole("button", { name: "Write" }));

    await waitFor(() => expect(toast.error).toHaveBeenCalled());
    await settle();
    expect(toast.error).toHaveBeenCalledOnce();
    const [title, options] = toast.error.mock.calls[0];
    expect(title).toBe("Record locked");
    render(<div data-testid="description">{options?.description}</div>);
    expect(screen.getByTestId("description")).toHaveTextContent(/^Locked by an import$/);
  });

  it("does not toast a lapsed session (401): the auth recovery takes over", async () => {
    stubFetch(() => response(401, { error: "unauthenticated" }));

    render(<RecordForm form={form} />);
    fireEvent.click(screen.getByRole("button", { name: "Write" }));

    await waitFor(() => expect(vi.mocked(fetch).mock.calls.some(([, init]) => init?.method === "PUT")).toBe(true));
    await settle();
    await settle();
    expect(toast.error).not.toHaveBeenCalled();
  });
});

describe("RecordForm post after save", () => {
  const receipt: FormDescriptor = {
    kind: "documents",
    name: "receipts",
    id: "receipt-1",
    meta: { name: "Receipts", postable: true, attributes: form.meta.attributes },
    initial: { id: "receipt-1", title: "Pilot" },
  };

  /** Stub fetch: the save goes through and the record re-reads; the post answers `post`. */
  function stubPost(post: () => Response) {
    vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) => {
      const method = (init?.method ?? "GET").toUpperCase();
      if (url === "/api/documents/receipts/receipt-1" && method === "PUT") return response(200, receipt.initial);
      if (url === "/api/documents/receipts/receipt-1/post" && method === "POST") return post();
      if (url === "/api/ui/record-refresh/documents/receipts/receipt-1") {
        return response(200, { record: receipt.initial, actions: [], readOnly: false, canPost: true });
      }
      if (url.endsWith("/validate")) return response(200, { valid: true, fieldErrors: {}, formErrors: [] });
      if (url.startsWith("/api/checks/")) return response(200, []);
      throw new Error(`unexpected ${method} ${url}`);
    }));
  }

  const postAfterSave = async () => {
    render(<RecordForm form={receipt} />);
    fireEvent.click(screen.getByRole("button", { name: "Post" }));
    await waitFor(() => expect(toast.error).toHaveBeenCalled());
    await settle();
  };

  it("reports a network failure once, the save having gone through", async () => {
    stubPost(() => {
      throw new TypeError("Failed to fetch");
    });
    await postAfterSave();

    expect(toast.error.mock.calls).toEqual([["Failed to fetch"]]);
    expect(toast.success).toHaveBeenCalledWith("Saved");
    // The failure comes after "Saved", so it is the toast in front of the stack.
    expect(toast.success.mock.invocationCallOrder[0]).toBeLessThan(toast.error.mock.invocationCallOrder[0]);
  });

  it("reports a posting rule bound to a field, which the api layer leaves to the form, once", async () => {
    stubPost(() => response(400, {
      error: "validation",
      message: "Quantity must be positive",
      fieldErrors: { quantity: ["Quantity must be positive"] },
    }));
    await postAfterSave();

    expect(toast.error.mock.calls).toEqual([["Quantity must be positive"]]);
  });

  it("on a new document, reports a network failure once and still opens the saved record", async () => {
    vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) => {
      const method = (init?.method ?? "GET").toUpperCase();
      if (url === "/api/documents/receipts" && method === "POST") return response(200, { id: "receipt-9", title: "Pilot" });
      if (url === "/api/documents/receipts/receipt-9/post" && method === "POST") throw new TypeError("Failed to fetch");
      if (url.endsWith("/validate")) return response(200, { valid: true, fieldErrors: {}, formErrors: [] });
      if (url.startsWith("/api/checks/")) return response(200, []);
      throw new Error(`unexpected ${method} ${url}`);
    }));
    const opened = vi.fn();
    const onOpen = (event: Event) => opened((event as CustomEvent<string>).detail);
    window.addEventListener("onno:open", onOpen);
    try {
      render(<RecordForm form={{ ...receipt, id: null, initial: { title: "Pilot" } }} />);
      fireEvent.click(screen.getByRole("button", { name: "Post" }));
      await waitFor(() => expect(toast.error).toHaveBeenCalled());
      await settle();
    } finally {
      window.removeEventListener("onno:open", onOpen);
    }

    expect(toast.error.mock.calls).toEqual([["Failed to fetch"]]);
    expect(opened).toHaveBeenCalledWith("onno://documents/receipts/receipt-9");
  });

  it("shows the api layer's toast for a rejected post only once", async () => {
    stubPost(() => response(409, { message: "Not enough stock" }));
    await postAfterSave();

    expect(toast.error.mock.calls).toEqual([["Not enough stock"]]);
    expect(toast.success.mock.invocationCallOrder[0]).toBeLessThan(toast.error.mock.invocationCallOrder[0]);
  });
});
