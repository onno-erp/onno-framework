import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

vi.mock("@/lib/api", () => ({
  api: { validateRecord: vi.fn().mockResolvedValue({ fieldErrors: {}, formErrors: [] }) },
  ApiError: class ApiError extends Error {},
}));

vi.mock("@/components/ref-select", () => ({
  RefSelect: (props: {
    value?: string;
    excludedIds?: string[];
    optionDecorator?: string;
    optionContext?: unknown;
  }) => (
    <output
      data-testid={`ref-${props.value}`}
      data-excluded={JSON.stringify(props.excludedIds ?? [])}
      data-decorator={props.optionDecorator}
      data-context={JSON.stringify(props.optionContext)}
    />
  ),
}));
vi.mock("@/components/date-picker", () => ({ DatePicker: () => null }));
vi.mock("@/components/geo-picker", () => ({ GeoPicker: () => null }));
vi.mock("@/components/map-editor", () => ({ MapEditor: () => null }));
vi.mock("@/components/image-picker", () => ({ ImagePicker: () => null, GalleryPicker: () => null }));
vi.mock("@/components/file-picker", () => ({ FilePicker: () => null }));
vi.mock("@/components/related-list-panel", () => ({ RelatedListPanel: () => null }));
vi.mock("@/lib/actions-menu-bridge", () => ({ ActionsCluster: () => null }));

import { EntityFormWidget, type FormDescriptor } from "@/components/entity-form-widget";

afterEach(cleanup);

describe("EntityFormWidget unique contextual refs (#272)", () => {
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
      refOptionDecorator: "com.example.EmployeeAvailability",
      uniqueWithinSection: true,
    } as never;
    const form: FormDescriptor = {
      kind: "documents",
      name: "schedule_events",
      id: "event-1",
      title: "Edit Schedule event",
      submitLabel: "Save",
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

    render(<EntityFormWidget form={form} />);

    const first = screen.getByTestId("ref-employee-1");
    const second = screen.getByTestId("ref-employee-2");
    expect(first).toHaveAttribute("data-excluded", '["employee-2"]');
    expect(second).toHaveAttribute("data-excluded", '["employee-1"]');
    expect(first).toHaveAttribute("data-decorator", "com.example.EmployeeAvailability");
    expect(JSON.parse(first.getAttribute("data-context")!)).toMatchObject({
      fieldPath: "participants.employee",
      section: "participants",
      rowIndex: 0,
      rowValues: { employee: "employee-1" },
      documentId: "event-1",
    });
  });
});
