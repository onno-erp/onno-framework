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
vi.mock("@/components/geo-picker", () => ({ GeoPicker: () => null }));
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

describe("EntityFormWidget nullable enums (#270)", () => {
  it("normalizes the optional no-selection choice to null in the saved payload", async () => {
    Element.prototype.scrollIntoView = vi.fn();
    updateCatalogItem.mockResolvedValue({ _id: "episode-1", status: null });
    validateRecord.mockResolvedValue({ fieldErrors: {}, formErrors: [] });
    const form: FormDescriptor = {
      kind: "catalogs",
      name: "episodes",
      id: "episode-1",
      title: "Edit Episode",
      submitLabel: "Save",
      meta: {
        name: "Episodes",
        attributes: [
          {
            fieldName: "status",
            columnName: "status",
            displayName: "Status",
            javaType: "EpisodeStatus",
            length: 0,
            scale: 0,
            required: false,
            visibleInForm: true,
            order: 0,
            isEnum: true,
            enumValues: [{ id: "active-id", name: "ACTIVE", label: "Active" }],
          } as never,
        ],
      },
      initial: { _id: "episode-1", status: "active-id" },
    };

    render(<EntityFormWidget form={form} />);

    const select = screen.getByRole("combobox");
    fireEvent.keyDown(select, { key: "ArrowDown" });
    fireEvent.click(await screen.findByRole("option", { name: "No selection" }));
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() =>
      expect(updateCatalogItem).toHaveBeenCalledWith(
        "episodes",
        "episode-1",
        expect.objectContaining({ status: null })
      )
    );
  });
});
