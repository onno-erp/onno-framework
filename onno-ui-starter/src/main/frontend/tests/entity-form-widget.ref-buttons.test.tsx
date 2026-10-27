import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

vi.mock("@/lib/api", () => ({
  api: { validateRecord: vi.fn().mockResolvedValue({ fieldErrors: {}, formErrors: [] }) },
  ApiError: class ApiError extends Error {},
}));

vi.mock("@/components/ref-select", () => ({
  RefSelect: (props: { value?: string; clearable?: boolean; openButton?: boolean }) => (
    <output
      data-testid={`ref-${props.value}`}
      data-clearable={String(props.clearable)}
      data-open={String(props.openButton)}
    />
  ),
}));
vi.mock("@/components/date-picker", () => ({ DatePicker: () => null }));
vi.mock("@/components/map-editor", () => ({ MapEditor: () => null }));
vi.mock("@/components/image-picker", () => ({ ImagePicker: () => null, GalleryPicker: () => null }));
vi.mock("@/components/file-picker", () => ({ FilePicker: () => null }));
vi.mock("@/components/related-list-panel", () => ({ RelatedListPanel: () => null }));
vi.mock("@/lib/actions-menu-bridge", () => ({ ActionsCluster: () => null }));

import { EntityFormWidget, type FormDescriptor } from "@/components/entity-form-widget";

afterEach(cleanup);

function ref(fieldName: string, extra: Record<string, unknown>) {
  return {
    fieldName,
    columnName: fieldName,
    displayName: fieldName,
    javaType: "Ref",
    length: 0,
    scale: 0,
    required: false,
    visibleInForm: true,
    order: 0,
    isRef: true,
    refTarget: "Customers",
    refKind: "catalog",
    ...extra,
  } as never;
}

describe("EntityFormWidget ref picker buttons (#409)", () => {
  it("passes the resolved open/clear hints to header and row pickers", () => {
    const form: FormDescriptor = {
      kind: "documents",
      name: "orders",
      id: "order-1",
      title: "Edit Order",
      submitLabel: "Save",
      meta: {
        name: "Orders",
        attributes: [
          ref("customer", { refOpenButton: true, refClearButton: true }),
          ref("payer", { refOpenButton: false, refClearButton: false }),
          // Metadata from an older server without the hints: open on, clear follows `required`.
          ref("owner", { required: true }),
        ],
        tabularSections: [{
          name: "lines",
          tableName: "order_lines",
          attributes: [ref("book", { refOpenButton: false, refClearButton: true })],
        }],
      },
      initial: {
        _id: "order-1",
        customer: "c-1",
        payer: "c-2",
        owner: "c-3",
        lines: [{ book: "b-1" }],
      },
    };

    render(<EntityFormWidget form={form} />);

    expect(screen.getByTestId("ref-c-1")).toHaveAttribute("data-open", "true");
    expect(screen.getByTestId("ref-c-1")).toHaveAttribute("data-clearable", "true");
    expect(screen.getByTestId("ref-c-2")).toHaveAttribute("data-open", "false");
    expect(screen.getByTestId("ref-c-2")).toHaveAttribute("data-clearable", "false");
    expect(screen.getByTestId("ref-c-3")).toHaveAttribute("data-open", "true");
    expect(screen.getByTestId("ref-c-3")).toHaveAttribute("data-clearable", "false");
    expect(screen.getByTestId("ref-b-1")).toHaveAttribute("data-open", "false");
    expect(screen.getByTestId("ref-b-1")).toHaveAttribute("data-clearable", "true");
  });
});
