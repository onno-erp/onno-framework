import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";

vi.mock("@/lib/api", () => ({
  api: { validateRecord: vi.fn().mockResolvedValue({ fieldErrors: {}, formErrors: [] }) },
  ApiError: class ApiError extends Error {},
}));
vi.mock("@/components/fields/ref-select", () => ({ RefSelect: () => null }));
vi.mock("@/components/fields/date-picker", () => ({ DatePicker: () => null }));
vi.mock("@/components/maps/map-editor", () => ({ MapEditor: () => null }));
vi.mock("@/components/fields/image-picker", () => ({ ImagePicker: () => null, GalleryPicker: () => null }));
vi.mock("@/components/fields/file-picker", () => ({ FilePicker: () => null }));
vi.mock("@/components/actions/record-actions", () => ({ RecordActions: () => null }));

import { RecordForm, type FormDescriptor } from "@/components/entities/record-form";

afterEach(cleanup);

describe("RecordForm Cancel (#349)", () => {
  it("closes an existing-record form after discarding its draft", () => {
    const form: FormDescriptor = {
      kind: "catalogs",
      name: "products",
      id: "p-1",
      meta: { name: "Products", attributes: [] },
      initial: { _id: "p-1", description: "Tea" },
    };
    const close = vi.fn();
    window.addEventListener("onno:close", close);

    try {
      render(<RecordForm form={form} />);
      fireEvent.click(screen.getByRole("button", { name: "Cancel" }));

      expect(close).toHaveBeenCalledTimes(1);
      expect((close.mock.calls[0][0] as CustomEvent).detail).toBe("/catalogs/products/p-1");
    } finally {
      window.removeEventListener("onno:close", close);
    }
  });
});
