import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";

vi.mock("@/lib/api", () => ({
  api: { validateRecord: vi.fn().mockResolvedValue({ fieldErrors: {}, formErrors: [] }) },
  ApiError: class ApiError extends Error {},
}));
vi.mock("@/components/ref-select", () => ({ RefSelect: () => null }));
vi.mock("@/components/date-picker", () => ({ DatePicker: () => null }));
vi.mock("@/components/map-editor", () => ({ MapEditor: () => null }));
vi.mock("@/components/image-picker", () => ({ ImagePicker: () => null, GalleryPicker: () => null }));
vi.mock("@/components/file-picker", () => ({ FilePicker: () => null }));
vi.mock("@/components/related-list-panel", () => ({ RelatedListPanel: () => null }));
vi.mock("@/lib/actions-menu-bridge", () => ({ ActionsCluster: () => null }));

import { EntityFormWidget, type FormDescriptor } from "@/components/entity-form-widget";

afterEach(cleanup);

describe("EntityFormWidget Cancel (#349)", () => {
  it("closes an existing-record form after discarding its draft", () => {
    const form: FormDescriptor = {
      kind: "catalogs",
      name: "products",
      id: "p-1",
      title: "Edit Product",
      submitLabel: "Save",
      meta: { name: "Products", attributes: [] },
      initial: { _id: "p-1", description: "Tea" },
    };
    const close = vi.fn();
    window.addEventListener("onno:closepath", close);

    try {
      render(<EntityFormWidget form={form} />);
      fireEvent.click(screen.getByRole("button", { name: "Cancel" }));

      expect(close).toHaveBeenCalledTimes(1);
      expect((close.mock.calls[0][0] as CustomEvent).detail).toBe("/catalogs/products/p-1");
    } finally {
      window.removeEventListener("onno:closepath", close);
    }
  });
});
