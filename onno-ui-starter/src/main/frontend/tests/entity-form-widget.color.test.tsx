import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";

const { updateCatalogItem, validateRecord } = vi.hoisted(() => ({
  updateCatalogItem: vi.fn(),
  validateRecord: vi.fn(),
}));

vi.mock("@/lib/api", () => ({
  api: {
    updateCatalogItem,
    validateRecord,
  },
  ApiError: class ApiError extends Error {},
}));

vi.mock("@/components/date-picker", () => ({ DatePicker: () => null }));
vi.mock("@/components/map-editor", () => ({ MapEditor: () => null }));
vi.mock("@/components/image-picker", () => ({ ImagePicker: () => null, GalleryPicker: () => null }));
vi.mock("@/components/file-picker", () => ({ FilePicker: () => null }));
vi.mock("@/components/related-list-panel", () => ({ RelatedListPanel: () => null }));
vi.mock("@/lib/actions-menu-bridge", () => ({ ActionsCluster: () => null }));

import { EntityFormWidget, type FormDescriptor } from "@/components/entity-form-widget";

afterEach(() => {
  cleanup();
  updateCatalogItem.mockReset();
  validateRecord.mockReset();
});

describe("EntityFormWidget color fields", () => {
  it("rejects invalid hex and saves a normalized picker value", async () => {
    Element.prototype.scrollIntoView = vi.fn();
    updateCatalogItem.mockResolvedValue({ _id: "palette-1", color: "#aabbcc" });
    validateRecord.mockResolvedValue({ fieldErrors: {}, formErrors: [] });
    const form: FormDescriptor = {
      kind: "catalogs",
      name: "palettes",
      id: "palette-1",
      title: "Edit Palette",
      submitLabel: "Save",
      meta: {
        name: "Palettes",
        attributes: [
          {
            fieldName: "color",
            columnName: "color",
            displayName: "Color",
            javaType: "String",
            length: 7,
            scale: 0,
            required: true,
            visibleInForm: true,
            order: 0,
            widget: "color",
          } as never,
        ],
      },
      initial: { _id: "palette-1", color: "#112233" },
    };

    render(<EntityFormWidget form={form} />);

    const input = screen.getByRole("textbox", { name: "Hex color" });
    fireEvent.change(input, { target: { value: "wrong" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByText("Color must be a hex color such as #AABBCC")).toBeVisible();
    expect(updateCatalogItem).not.toHaveBeenCalled();

    fireEvent.change(input, { target: { value: "abc" } });
    fireEvent.blur(input);
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() =>
      expect(updateCatalogItem).toHaveBeenCalledWith(
        "palettes",
        "palette-1",
        expect.objectContaining({ color: "#aabbcc" })
      )
    );
  });
});
