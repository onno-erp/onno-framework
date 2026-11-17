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

  it("reports typed feedback, which the api layer leaves to the form, once with its context", async () => {
    stubFetch(() => response(409, {
      severity: "error",
      title: "Record locked",
      message: "Locked by an import",
    }));

    render(<RecordForm form={form} />);
    fireEvent.click(screen.getByRole("button", { name: "Write" }));

    await waitFor(() => expect(toast.error).toHaveBeenCalled());
    await settle();
    expect(toast.error.mock.calls).toEqual([["Couldn't save: Locked by an import"]]);
  });
});
