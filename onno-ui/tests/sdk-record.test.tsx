import { afterEach, describe, expect, it, vi } from "vitest";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
const { updateDocument, validateRecord, postDocument } = vi.hoisted(() => ({
  updateDocument: vi.fn(),
  validateRecord: vi.fn(),
  postDocument: vi.fn(),
}));
vi.mock("@/lib/api", () => ({
  api: { updateDocument, validateRecord, postDocument },
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
  useRecordForm,
  useRecordField,
  RecordField,
  type FormDescriptor,
} from "@onno/ui";

const attr = (fieldName: string, extra = {}) =>
  ({
    fieldName,
    columnName: fieldName,
    displayName: fieldName,
    javaType: "String",
    visibleInForm: true,
    required: false,
    ...extra,
  }) as never;
const descriptor = (): FormDescriptor => ({
  kind: "documents",
  name: "receipts",
  id: "receipt-1",
  meta: {
    name: "Receipts",
    postable: true,
    attributes: [
      attr("note", { required: true }),
      attr("region"),
      attr("supplier", {
        isRef: true,
        refTarget: "Suppliers",
        refFilterBy: { region: "region" },
        required: true,
      }),
    ],
  },
  initial: {
    id: "receipt-1",
    note: "Original",
    region: "north",
    supplier: "supplier-1",
  },
});
function CustomRecord({ descriptor }: { descriptor: FormDescriptor }) {
  const form = useRecordForm(descriptor);
  const note = useRecordField(form, "note", { label: "Custom notes" });
  const supplier = useRecordField(form, "supplier");
  return (
    <section aria-label="Own record page">
      <h1>{form.title}</h1>
      <input
        aria-label={note.label}
        value={String(note.value ?? "")}
        disabled={note.disabled}
        onChange={(event) => note.onChange(event.target.value)}
      />
      {note.error ? <p role="alert">{note.error}</p> : null}
      <output>{form.dirty ? "Unsaved draft" : "Clean record"}</output>
      <output data-testid="picker">{JSON.stringify(supplier.reference)}</output>
      <RecordField form={form} field="region">
        {(field) => (
          <button onClick={() => field.onChange("south")}>Change region</button>
        )}
      </RecordField>
      <button onClick={() => void form.save()}>Custom save</button>
    </section>
  );
}
afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});
describe("public record SDK", () => {
  it("runs a completely custom page with the same dirty and save lifecycle", async () => {
    updateDocument.mockResolvedValue({
      id: "receipt-1",
      note: "Edited",
      region: "north",
      supplier: "supplier-1",
    });
    validateRecord.mockResolvedValue({ fieldErrors: {}, formErrors: [] });
    render(<CustomRecord descriptor={descriptor()} />);
    expect(screen.queryByRole("button", { name: "Write" })).toBeNull();
    fireEvent.change(screen.getByLabelText("Custom notes"), {
      target: { value: "Edited" },
    });
    expect(screen.getByText("Unsaved draft")).toBeInTheDocument();
    fireEvent.click(screen.getByText("Custom save"));
    await waitFor(() =>
      expect(updateDocument).toHaveBeenCalledWith(
        "receipts",
        "receipt-1",
        expect.objectContaining({ note: "Edited" }),
        { silent: true },
      ),
    );
    await screen.findByText("Clean record");
  });
  it("exposes validation to custom editors and refuses read-only mutations", () => {
    const form = descriptor();
    const { rerender } = render(<CustomRecord descriptor={form} />);
    fireEvent.change(screen.getByLabelText("Custom notes"), {
      target: { value: "" },
    });
    fireEvent.click(screen.getByText("Custom save"));
    expect(screen.getByRole("alert")).toHaveTextContent(/required/i);
    expect(updateDocument).not.toHaveBeenCalled();
    rerender(<CustomRecord descriptor={{ ...form, readOnly: true }} />);
    fireEvent.click(screen.getByText("Change region"));
    fireEvent.click(screen.getByText("Custom save"));
    expect(screen.getByLabelText("Custom notes")).toBeDisabled();
    expect(screen.getByTestId("picker")).toHaveTextContent('"region":"north"');
    expect(updateDocument).not.toHaveBeenCalled();
  });
  it("keeps restricted reference bindings disabled and hides their picker context", () => {
    const form = descriptor();
    form.initial = { ...form.initial, supplier: null, supplierRestricted: true };
    function RestrictedHost() {
      const state = useRecordForm(form);
      const supplier = useRecordField(state, "supplier");
      return <>
        <output>{supplier.restricted ? "Restricted binding" : "Readable binding"}</output>
        <output>{supplier.disabled ? "Disabled binding" : "Enabled binding"}</output>
        <output>{supplier.reference === null ? "No picker" : "Has picker"}</output>
        <button onClick={() => supplier.onChange("other")}>Try replacement</button>
        <output>{String(supplier.value)}</output>
      </>;
    }
    render(<RestrictedHost />);
    fireEvent.click(screen.getByText("Try replacement"));
    expect(screen.getByText("Restricted binding")).toBeInTheDocument();
    expect(screen.getByText("Disabled binding")).toBeInTheDocument();
    expect(screen.getByText("No picker")).toBeInTheDocument();
    expect(screen.queryByText("other")).toBeNull();
  });

  it("sends an explicitly cleared dependent reference in the save payload", async () => {
    const form = descriptor();
    form.meta.attributes = form.meta.attributes.map((attr) => attr.fieldName === "supplier" ? { ...attr, required: false } : attr);
    updateDocument.mockResolvedValue({ id: "receipt-1", note: "Original", region: "south", supplier: null });
    validateRecord.mockResolvedValue({ fieldErrors: {}, formErrors: [] });
    render(<CustomRecord descriptor={form} />);
    fireEvent.click(screen.getByText("Change region"));
    fireEvent.click(screen.getByText("Custom save"));
    await waitFor(() => expect(updateDocument).toHaveBeenCalledWith("receipts", "receipt-1",
      expect.objectContaining({ region: "south", supplier: null }), { silent: true }));
  });

  it("shares cascading reference filters and server picker context", () => {
    render(<CustomRecord descriptor={descriptor()} />);
    const initial = JSON.parse(screen.getByTestId("picker").textContent!);
    expect(initial).toMatchObject({
      targetName: "Suppliers",
      clearable: false,
      where: { region: "north" },
      optionContext: {
        ownerKind: "document",
        ownerName: "Receipts",
        fieldPath: "supplier",
        documentId: "receipt-1",
      },
      value: "supplier-1",
    });
    fireEvent.click(screen.getByText("Change region"));
    const changed = JSON.parse(screen.getByTestId("picker").textContent!);
    expect(changed).toMatchObject({ where: { region: "south" }, value: null });
  });
});
