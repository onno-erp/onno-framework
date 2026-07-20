import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";

const { validateForm } = vi.hoisted(() => ({
  validateForm: vi.fn().mockResolvedValue([
    { severity: "WARNING", field: "subject", message: "Overlaps another event" },
    { severity: "INFO", field: "", message: "Availability was checked" },
  ]),
}));

vi.mock("@/lib/api", () => ({
  api: {
    validateRecord: vi.fn().mockResolvedValue({ fieldErrors: {}, formErrors: [] }),
    validateForm,
  },
  ApiError: class ApiError extends Error {},
}));
vi.mock("@/components/ref-select", () => ({ RefSelect: () => null }));
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
  validateForm.mockClear();
});

describe("EntityFormWidget authored async feedback (#274)", () => {
  it("debounces dependency checks, targets field/form feedback, and preserves edits", async () => {
    const form: FormDescriptor = {
      kind: "documents",
      name: "schedule_events",
      id: null,
      title: "New schedule event",
      submitLabel: "Save",
      meta: {
        name: "Schedule events",
        attributes: [{
          fieldName: "subject",
          columnName: "subject",
          displayName: "Subject",
          javaType: "String",
          length: 200,
          scale: 0,
          required: true,
          visibleInForm: true,
          order: 0,
        } as never],
        formValidations: [{
          key: "schedule-conflicts",
          dependencies: ["subject"],
          debounceMillis: 10,
        }],
      },
      initial: { subject: "Draft" },
    };

    render(<EntityFormWidget form={form} />);
    const input = screen.getByDisplayValue("Draft");
    fireEvent.change(input, { target: { value: "Changed" } });

    await waitFor(() => expect(validateForm).toHaveBeenCalled());
    expect(validateForm).toHaveBeenLastCalledWith(
      "documents", "schedule_events", "schedule-conflicts", null,
      expect.objectContaining({ subject: "Changed" })
    );
    expect(screen.getByText("Overlaps another event")).toBeInTheDocument();
    expect(screen.getByText("Availability was checked")).toBeInTheDocument();
    expect(input).toHaveValue("Changed");
  });
});
