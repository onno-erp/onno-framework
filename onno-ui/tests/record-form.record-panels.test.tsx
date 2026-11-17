import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

vi.mock("@/lib/api", () => ({
  api: {},
  ApiError: class ApiError extends Error {},
}));
vi.mock("@/components/fields/ref-select", () => ({ RefSelect: () => null }));
vi.mock("@/components/fields/date-picker", () => ({ DatePicker: () => null }));
vi.mock("@/components/maps/map-editor", () => ({ MapEditor: () => null }));
vi.mock("@/components/fields/image-picker", () => ({ ImagePicker: () => null, GalleryPicker: () => null }));
vi.mock("@/components/fields/file-picker", () => ({ FilePicker: () => null }));
vi.mock("@/components/actions/record-actions", () => ({ RecordActions: () => null }));

import { RecordForm, type FormDescriptor } from "@/components/entities/record-form";
import type { RecordContext } from "@/lib/entity-customizations";

afterEach(cleanup);

function Summary({ record, title }: { record: RecordContext; title?: string }) {
  return (
    <div data-testid="record-panel">
      {title}:{record.kind}:{record.name}:{record.id}:{String(record.data.description)}:{String(record.readOnly)}
    </div>
  );
}

function form(id: string | null): FormDescriptor {
  return {
    kind: "catalogs",
    name: "products",
    id,
    readOnly: true,
    meta: { name: "Products", attributes: [] },
    initial: id ? { _id: id, description: "Tea" } : null,
    panels: [{ component: Summary, title: "Product summary" }],
  };
}

describe("record panels", () => {
  it("renders each panel component with the saved record", () => {
    render(<RecordForm form={form("p-1")} />);
    expect(screen.getByTestId("record-panel").textContent).toBe("Product summary:catalogs:products:p-1:Tea:true");
  });

  it("does not render a panel before the record is saved", () => {
    render(<RecordForm form={form(null)} />);
    expect(screen.queryByTestId("record-panel")).toBeNull();
  });
});
