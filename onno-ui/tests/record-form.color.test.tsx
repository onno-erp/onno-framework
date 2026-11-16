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

vi.mock("@/components/fields/date-picker", () => ({ DatePicker: () => null }));
vi.mock("@/components/maps/map-editor", () => ({ MapEditor: () => null }));
vi.mock("@/components/fields/image-picker", () => ({ ImagePicker: () => null, GalleryPicker: () => null }));
vi.mock("@/components/fields/file-picker", () => ({ FilePicker: () => null }));
vi.mock("@/components/actions/record-actions", () => ({ RecordActions: () => null }));

import { RecordForm, type FormDescriptor } from "@/components/entities/record-form";

afterEach(() => {
  cleanup();
  updateCatalogItem.mockReset();
  validateRecord.mockReset();
});

describe("RecordForm color fields", () => {
  it("rejects invalid hex and saves a normalized picker value", async () => {
    Element.prototype.scrollIntoView = vi.fn();
    updateCatalogItem.mockResolvedValue({ _id: "palette-1", color: "#aabbcc" });
    validateRecord.mockResolvedValue({ fieldErrors: {}, formErrors: [] });
    const form: FormDescriptor = {
      kind: "catalogs",
      name: "palettes",
      id: "palette-1",
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
            input: "color",
          } as never,
        ],
      },
      initial: { _id: "palette-1", color: "#112233" },
    };

    render(<RecordForm form={form} />);

    const input = screen.getByRole("textbox", { name: "Hex color" });
    fireEvent.change(input, { target: { value: "wrong" } });
    fireEvent.click(screen.getByRole("button", { name: "Write" }));
    expect(await screen.findByText("Color must be a hex color such as #AABBCC")).toBeVisible();
    expect(updateCatalogItem).not.toHaveBeenCalled();

    fireEvent.change(input, { target: { value: "abc" } });
    fireEvent.blur(input);
    fireEvent.click(screen.getByRole("button", { name: "Write" }));

    await waitFor(() =>
      expect(updateCatalogItem).toHaveBeenCalledWith(
        "palettes",
        "palette-1",
        expect.objectContaining({ color: "#aabbcc" }),
        { silent: true }
      )
    );
  });
});
