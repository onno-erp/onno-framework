import { afterEach, describe, expect, it, vi } from "vitest";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { Truck } from "lucide-react";

const { updateDocument, postDocument, validateRecord } = vi.hoisted(() => ({
  updateDocument: vi.fn(),
  postDocument: vi.fn(),
  validateRecord: vi.fn(),
}));
vi.mock("@/lib/api", () => ({
  api: { updateDocument, postDocument, validateRecord },
  ApiError: class extends Error {},
}));
vi.mock("@/components/fields/ref-select", () => ({ RefSelect: () => null }));
vi.mock("@/components/maps/map-editor", () => ({ MapEditor: () => null }));
vi.mock("@/components/fields/image-picker", () => ({
  ImagePicker: () => null,
  GalleryPicker: () => null,
}));
vi.mock("@/components/fields/file-picker", () => ({ FilePicker: () => null }));

import {
  RecordForm,
  RecordPage,
  RecordFields,
  RecordLines,
  type RecordFormState,
  type FormDescriptor,
} from "@/components/entities/record-form";
import { Icon } from "@/lib/icons";
import {
  DirtyFormsProvider,
  useDirtyForms,
  type DirtyForms,
} from "@/lib/dirty-forms";

const attr = (fieldName: string) =>
  ({
    fieldName,
    columnName: fieldName,
    displayName: fieldName,
    javaType: "String",
    visibleInForm: true,
    required: false,
  }) as never;
const descriptor = (readOnly = false): FormDescriptor => ({
  kind: "documents",
  name: "receipts",
  id: "receipt-1",
  readOnly,
  meta: {
    name: "Receipts",
    postable: true,
    attributes: [attr("supplier"), attr("note")],
    tabularSections: [
      { name: "lines", tableName: "receipt_lines", attributes: [attr("book")] },
    ],
  },
  initial: {
    id: "receipt-1",
    supplier: "old-supplier",
    note: "old",
    lines: [{ book: "old-book" }],
  },
});
function CustomPage({ form }: { form: RecordFormState }) {
  return (
    <RecordPage form={form}>
      <RecordFields form={form} fields={{ supplier: { label: "Vendor" } }} />
      <RecordLines
        form={form}
        section="lines"
        fields={{
          book: { label: "Title", filterBy: { supplier: "supplier" } },
        }}
      />
      <textarea
        aria-label="Custom editor"
        value={String(form.values.note ?? "")}
        onChange={(event) => form.set("note", event.target.value)}
      />
      {form.errors.note ? <p role="alert">{form.errors.note}</p> : null}
      <button onClick={() => form.set("lines", [{ book: "replacement" }])}>
        Replace lines
      </button>
      <button onClick={() => void form.save(true)}>Save custom</button>
    </RecordPage>
  );
}
afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("composed record pages", () => {
  it("shares custom inputs and line edits with the standard save/post pipeline", async () => {
    updateDocument.mockResolvedValue({ id: "receipt-1" });
    postDocument.mockResolvedValue({ id: "receipt-1" });
    validateRecord.mockResolvedValue({ fieldErrors: {}, formErrors: [] });
    let tracker: DirtyForms;
    function Probe() {
      tracker = useDirtyForms();
      return null;
    }
    render(
      <DirtyFormsProvider>
        <Probe />
        <RecordForm form={descriptor()} component={CustomPage} />
      </DirtyFormsProvider>,
    );
    expect(tracker!.isDirty("/documents/receipts/receipt-1")).toBe(false);
    expect(screen.getByText("Vendor")).toBeInTheDocument();
    expect(screen.getByText("Title")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Custom editor"), {
      target: { value: "custom text" },
    });
    expect(tracker!.isDirty("/documents/receipts/receipt-1")).toBe(true);
    fireEvent.click(screen.getByText("Replace lines"));
    fireEvent.click(screen.getByText("Save custom"));
    await waitFor(() =>
      expect(updateDocument).toHaveBeenCalledWith(
        "receipts",
        "receipt-1",
        expect.objectContaining({
          note: "custom text",
          lines: [{ book: "replacement" }],
        }),
        { silent: true },
      ),
    );
    await waitFor(() =>
      expect(postDocument).toHaveBeenCalledWith("receipts", "receipt-1"),
    );
    await waitFor(() =>
      expect(tracker!.isDirty("/documents/receipts/receipt-1")).toBe(false),
    );
  });

  it("clears line pickers when a custom header field changes their filter source", async () => {
    updateDocument.mockResolvedValue({ id: "receipt-1" });
    validateRecord.mockResolvedValue({ fieldErrors: {}, formErrors: [] });
    render(<RecordForm form={descriptor()} component={CustomPage} />);
    fireEvent.change(screen.getByDisplayValue("old-supplier"), {
      target: { value: "new-supplier" },
    });
    expect(screen.queryByDisplayValue("old-book")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Write" }));
    await waitFor(() =>
      expect(updateDocument).toHaveBeenCalledWith(
        "receipts",
        "receipt-1",
        expect.objectContaining({ supplier: "new-supplier", lines: [] }),
        { silent: true },
      ),
    );
  });

  it("exposes validation errors to a custom editor", () => {
    const form = descriptor();
    form.meta.attributes = form.meta.attributes.map((attr) =>
      attr.fieldName === "note" ? { ...attr, required: true } : attr,
    );
    render(<RecordForm form={form} component={CustomPage} />);
    fireEvent.change(screen.getByLabelText("Custom editor"), {
      target: { value: "" },
    });
    fireEvent.click(screen.getByText("Save custom"));
    expect(screen.getByRole("alert")).toHaveTextContent(/required/i);
    expect(updateDocument).not.toHaveBeenCalled();
  });

  it("keeps custom controls and controller mutations read-only", () => {
    render(<RecordForm form={descriptor(true)} component={CustomPage} />);
    expect(screen.getByLabelText("Custom editor")).toBeDisabled();
    fireEvent.click(screen.getByText("Save custom"));
    expect(updateDocument).not.toHaveBeenCalled();
  });

  it("renders imported Lucide components", () => {
    const { container } = render(<Icon icon={Truck} />);
    expect(container.querySelector("svg.lucide-truck")).toBeInTheDocument();
  });
});
