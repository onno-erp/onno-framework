import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

vi.mock("@/lib/api", () => ({
  api: { validateRecord: vi.fn().mockResolvedValue({ fieldErrors: {}, formErrors: [] }) },
  ApiError: class ApiError extends Error {},
}));

vi.mock("@/components/fields/ref-select", () => ({
  RefSelect: (props: {
    value?: string;
    excludedIds?: string[];
    optionContext?: unknown;
  }) => (
    <output
      data-testid={`ref-${props.value}`}
      data-excluded={JSON.stringify(props.excludedIds ?? [])}
      data-context={JSON.stringify(props.optionContext)}
    />
  ),
}));
vi.mock("@/components/fields/date-picker", () => ({ DatePicker: () => null }));
vi.mock("@/components/maps/map-editor", () => ({ MapEditor: () => null }));
vi.mock("@/components/fields/image-picker", () => ({ ImagePicker: () => null, GalleryPicker: () => null }));
vi.mock("@/components/fields/file-picker", () => ({ FilePicker: () => null }));
vi.mock("@/components/actions/record-actions", () => ({ RecordActions: () => null }));

import { RecordForm, type FormDescriptor } from "@/components/entities/record-form";

afterEach(cleanup);

describe("RecordForm unique contextual refs (#272)", () => {
  it("passes live row context and only sibling ids to each picker", () => {
    const employee = {
      fieldName: "employee",
      columnName: "employee",
      displayName: "Employee",
      javaType: "Ref",
      length: 0,
      scale: 0,
      required: true,
      visibleInForm: true,
      order: 0,
      isRef: true,
      refTarget: "Employees",
      refKind: "catalog",
      uniqueWithinSection: true,
    } as never;
    const form: FormDescriptor = {
      kind: "documents",
      name: "schedule_events",
      id: "event-1",
      meta: {
        name: "Schedule events",
        attributes: [],
        tabularSections: [{
          name: "participants",
          tableName: "schedule_event_participants",
          attributes: [employee],
        }],
      },
      initial: {
        _id: "event-1",
        starts_at: "2026-07-20T10:00",
        participants: [{ employee: "employee-1" }, { employee: "employee-2" }],
      },
    };

    render(<RecordForm form={form} />);

    const first = screen.getByTestId("ref-employee-1");
    const second = screen.getByTestId("ref-employee-2");
    expect(first).toHaveAttribute("data-excluded", '["employee-2"]');
    expect(second).toHaveAttribute("data-excluded", '["employee-1"]');
    // The form names itself as the field's owner, selecting the field's PickerOptions server-side.
    expect(JSON.parse(first.getAttribute("data-context")!)).toMatchObject({
      ownerKind: "document",
      ownerName: "Schedule events",
      fieldPath: "participants.employee",
      section: "participants",
      rowIndex: 0,
      rowValues: { employee: "employee-1" },
      documentId: "event-1",
    });
  });
});
