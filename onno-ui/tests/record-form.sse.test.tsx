import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { UiEvent } from "@/lib/types";

// Hoisted so the vi.mock factory (evaluated before module init) can close over it.
const { getCatalogItem } = vi.hoisted(() => ({ getCatalogItem: vi.fn() }));

// Only getCatalogItem is exercised here; ApiError is referenced by the module's save path.
vi.mock("@/lib/api", () => ({
  api: { getCatalogItem },
  ApiError: class ApiError extends Error {},
}));

// The form pulls in several heavy field editors it doesn't need for a plain string attribute — stub
// them so the test stays focused on the live-refresh logic (and doesn't drag in leaflet/react-aria).
vi.mock("@/components/fields/ref-select", () => ({ RefSelect: () => null }));
vi.mock("@/components/fields/date-picker", () => ({ DatePicker: () => null }));
vi.mock("@/components/maps/map-editor", () => ({ MapEditor: () => null }));
vi.mock("@/components/fields/image-picker", () => ({ ImagePicker: () => null, GalleryPicker: () => null }));
vi.mock("@/components/fields/file-picker", () => ({ FilePicker: () => null }));
vi.mock("@/components/actions/record-actions", () => ({ RecordActions: () => null }));

// The server's live stream, driven by the test.
let emit: (event: UiEvent) => void = () => {};
vi.mock("@/lib/ui-event-bus", () => ({
  subscribeUiEvents: (listener: (event: UiEvent) => void) => {
    emit = listener;
    return () => {};
  },
}));

import { RecordForm, type FormDescriptor } from "@/components/entities/record-form";
import { invalidateEntity, queryClient, startQueryInvalidation } from "@/lib/query";
import { setModel } from "@/lib/model-store";
import type { AppModel } from "@/lib/app-model";

// A minimal editable catalog form: one plain String attribute rendered as a text input.
function makeForm(overrides?: Partial<FormDescriptor>): FormDescriptor {
  return {
    kind: "catalogs",
    name: "rooms",
    id: "r1",
    meta: {
      name: "Rooms",
      attributes: [
        {
          fieldName: "label",
          columnName: "label",
          displayName: "Label",
          javaType: "String",
          length: 200,
          scale: 0,
          required: false,
          visibleInForm: true,
          order: 0,
        } as never,
      ],
    },
    initial: { _id: "r1", label: "Old value" },
    ...overrides,
  };
}

// The server reports a change to the open record; it reaches the form through the query cache.
function dataEvent() {
  act(() => emit({ type: "updated", entityType: "catalog", entityName: "Rooms", id: "r1" }));
}

let stopInvalidation: () => void = () => {};
beforeEach(() => {
  setModel({
    entities: [{ kind: "catalogs", name: "rooms", logicalName: "Rooms", title: "Rooms", context: "", route: "/catalogs/rooms", canWrite: true }],
    informationRegisters: [],
    constants: [],
  } as unknown as AppModel);
  stopInvalidation = startQueryInvalidation();
});

afterEach(() => {
  cleanup();
  stopInvalidation();
  queryClient.clear();
  getCatalogItem.mockReset();
});

describe("RecordForm live refresh (#244)", () => {
  it("refetches and updates fields in place on an external change when there are no local edits", async () => {
    getCatalogItem.mockResolvedValue({ _id: "r1", label: "Server value" });
    render(<RecordForm form={makeForm()} />);

    const input = () => screen.getByDisplayValue("Old value") as HTMLInputElement;
    expect(input()).toBeTruthy();

    dataEvent();

    await waitFor(() => expect(getCatalogItem).toHaveBeenCalledWith("rooms", "r1"));
    await waitFor(() => expect(screen.getByDisplayValue("Server value")).toBeTruthy());
  });

  it("ignores a change to another record of the entity", async () => {
    getCatalogItem.mockResolvedValue({ _id: "r1", label: "Server value" });
    render(<RecordForm form={makeForm()} />);
    act(() => emit({ type: "updated", entityType: "catalog", entityName: "Rooms", id: "r2" }));
    await new Promise((resolve) => setTimeout(resolve, 250));
    expect(getCatalogItem).not.toHaveBeenCalled();
  });

  it("refreshes a clean form quietly when a command refreshes its entity", async () => {
    getCatalogItem.mockResolvedValue({ _id: "r1", label: "Server value" });
    render(<RecordForm form={makeForm()} />);
    act(() => invalidateEntity("catalogs/rooms"));
    await waitFor(() => expect(screen.getByDisplayValue("Server value")).toBeTruthy());
    expect(screen.queryByText("This record changed elsewhere.")).toBeNull();
  });

  it("does not clobber unsaved edits — shows a reload banner instead", async () => {
    getCatalogItem.mockResolvedValue({ _id: "r1", label: "Server value" });
    render(<RecordForm form={makeForm()} />);

    // Edit locally → the form is now dirty.
    const field = screen.getByDisplayValue("Old value") as HTMLInputElement;
    fireEvent.change(field, { target: { value: "My draft" } });

    dataEvent();

    // Dirty → no silent refetch; a non-destructive banner appears offering a reload.
    await waitFor(() => expect(screen.getByText("This record changed elsewhere.")).toBeTruthy());
    expect(getCatalogItem).not.toHaveBeenCalled();
    expect((screen.getByDisplayValue("My draft") as HTMLInputElement).value).toBe("My draft");

    // Explicit Reload discards the draft and takes the stored record.
    fireEvent.click(screen.getByText("Reload"));
    await waitFor(() => expect(getCatalogItem).toHaveBeenCalledWith("rooms", "r1"));
    await waitFor(() => expect(screen.getByDisplayValue("Server value")).toBeTruthy());
    expect(screen.queryByText("This record changed elsewhere.")).toBeNull();
  });
});
