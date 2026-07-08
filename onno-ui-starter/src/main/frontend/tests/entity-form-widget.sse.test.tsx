import { afterEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";

// Hoisted so the vi.mock factory (evaluated before module init) can close over it.
const { getCatalogItem } = vi.hoisted(() => ({ getCatalogItem: vi.fn() }));

// Only getCatalogItem is exercised here; ApiError is referenced by the module's save path.
vi.mock("@/lib/api", () => ({
  api: { getCatalogItem },
  ApiError: class ApiError extends Error {},
}));

// The form pulls in several heavy field editors it doesn't need for a plain string attribute — stub
// them so the test stays focused on the live-refresh logic (and doesn't drag in leaflet/react-aria).
vi.mock("@/components/ref-select", () => ({ RefSelect: () => null }));
vi.mock("@/components/date-picker", () => ({ DatePicker: () => null }));
vi.mock("@/components/geo-picker", () => ({ GeoPicker: () => null }));
vi.mock("@/components/map-editor", () => ({ MapEditor: () => null }));
vi.mock("@/components/image-picker", () => ({ ImagePicker: () => null, GalleryPicker: () => null }));
vi.mock("@/components/file-picker", () => ({ FilePicker: () => null }));
vi.mock("@/components/related-list-panel", () => ({ RelatedListPanel: () => null }));
vi.mock("@/lib/actions-menu-bridge", () => ({ ActionsCluster: () => null }));

import { EntityFormWidget, type FormDescriptor } from "@/components/entity-form-widget";

// A minimal editable catalog form: one plain String attribute rendered as a text input.
function makeForm(overrides?: Partial<FormDescriptor>): FormDescriptor {
  return {
    kind: "catalogs",
    name: "rooms",
    id: "r1",
    title: "Edit Room",
    submitLabel: "Save",
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

function dataEvent() {
  // toSnake("Rooms") === "rooms" === form.name, so this event targets the open record.
  act(() => {
    window.dispatchEvent(
      new CustomEvent("onno:dataevent", {
        detail: { type: "updated", entityType: "catalog", entityName: "Rooms", id: "r1" },
      })
    );
  });
}

afterEach(() => {
  cleanup();
  getCatalogItem.mockReset();
});

describe("EntityFormWidget live refresh (#244)", () => {
  it("refetches and updates fields in place on an external change when there are no local edits", async () => {
    getCatalogItem.mockResolvedValue({ _id: "r1", label: "Server value" });
    render(<EntityFormWidget form={makeForm()} />);

    const input = () => screen.getByDisplayValue("Old value") as HTMLInputElement;
    expect(input()).toBeTruthy();

    dataEvent();

    await waitFor(() => expect(getCatalogItem).toHaveBeenCalledWith("rooms", "r1"));
    await waitFor(() => expect(screen.getByDisplayValue("Server value")).toBeTruthy());
  });

  it("does not clobber unsaved edits — shows a reload banner instead", async () => {
    getCatalogItem.mockResolvedValue({ _id: "r1", label: "Server value" });
    render(<EntityFormWidget form={makeForm()} />);

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
