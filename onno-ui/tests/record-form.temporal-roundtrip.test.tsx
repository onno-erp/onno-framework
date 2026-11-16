import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";

const { updateCatalogItem, updateDocument, validateRecord } = vi.hoisted(() => ({
  updateCatalogItem: vi.fn(),
  updateDocument: vi.fn(),
  validateRecord: vi.fn(),
}));

vi.mock("@/lib/api", () => ({
  api: { updateCatalogItem, updateDocument, validateRecord },
  ApiError: class ApiError extends Error {},
}));

vi.mock("@/components/fields/ref-select", () => ({ RefSelect: () => null }));
vi.mock("@/components/fields/date-picker", () => ({
  DatePicker: ({ value }: { value?: string }) => <span data-testid="date-value">{value}</span>,
}));
vi.mock("@/components/maps/map-editor", () => ({ MapEditor: () => null }));
vi.mock("@/components/fields/image-picker", () => ({ ImagePicker: () => null, GalleryPicker: () => null }));
vi.mock("@/components/fields/file-picker", () => ({ FilePicker: () => null }));
vi.mock("@/components/actions/record-actions", () => ({ RecordActions: () => null }));

import { RecordForm, type FormDescriptor } from "@/components/entities/record-form";

const temporalAttr = (fieldName: string, columnName: string) => ({
  fieldName,
  columnName,
  displayName: fieldName,
  javaType: "LocalDateTime",
  length: 0,
  scale: 0,
  required: false,
  visibleInForm: true,
  order: 0,
}) as never;

afterEach(() => {
  cleanup();
  updateCatalogItem.mockReset();
  updateDocument.mockReset();
  validateRecord.mockReset();
});

describe("RecordForm temporal round trips (#267)", () => {
  it("submits an unchanged catalog LocalDateTime without its transport offset", async () => {
    updateCatalogItem.mockResolvedValue({ id: "episode-1", observedAt: "2026-01-16T04:00" });
    validateRecord.mockResolvedValue({ fieldErrors: {}, formErrors: [] });
    const form: FormDescriptor = {
      kind: "catalogs",
      name: "episodes",
      id: "episode-1",
      meta: {
        name: "Episodes",
        attributes: [temporalAttr("observedAt", "observed_at")],
      },
      initial: { id: "episode-1", observedAt: "2026-01-16T04:00:00.000+00:00" },
    };

    render(<RecordForm form={form} />);
    expect(screen.getByTestId("date-value")).toHaveTextContent("2026-01-16T04:00:00.000");
    fireEvent.click(screen.getByRole("button", { name: "Write" }));

    await waitFor(() =>
      expect(updateCatalogItem).toHaveBeenCalledWith(
        "episodes",
        "episode-1",
        expect.objectContaining({ observedAt: "2026-01-16T04:00:00.000" }),
        { silent: true }
      )
    );
  });

  it("edits catalog rows and sends them in the same save payload", async () => {
    updateCatalogItem.mockResolvedValue({ id: "profile-1" });
    validateRecord.mockResolvedValue({ fieldErrors: {}, formErrors: [] });
    const form: FormDescriptor = {
      kind: "catalogs", name: "Profiles", id: "profile-1",
      meta: { name: "Profiles", attributes: [], tabularSections: [{
        name: "filters", tableName: "catalog_profiles_filters", label: "Event filters",
        attributes: [{ fieldName: "property", columnName: "property", displayName: "Property",
          javaType: "String", visibleInForm: true } as never],
      }] },
      initial: { id: "profile-1", filters: [{ property: "event" }] },
    };
    render(<RecordForm form={form} />);
    expect(screen.getByRole("table", { name: "Event filters" })).toBeInTheDocument();
    fireEvent.change(screen.getByDisplayValue("event"), { target: { value: "duration" } });
    fireEvent.click(screen.getByRole("button", { name: "Add row" }));
    const inputs = screen.getAllByRole("textbox");
    fireEvent.change(inputs[inputs.length - 1], { target: { value: "level" } });
    expect(updateCatalogItem).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Write" }));
    await waitFor(() => expect(updateCatalogItem).toHaveBeenCalledWith(
      "Profiles", "profile-1", expect.objectContaining({ filters: [{ property: "duration" }, { property: "level" }] }),
      { silent: true }
    ));
  });

  it("normalizes document header and tabular-section LocalDateTimes before save", async () => {
    updateDocument.mockResolvedValue({ id: "event-1" });
    validateRecord.mockResolvedValue({ fieldErrors: {}, formErrors: [] });
    const form: FormDescriptor = {
      kind: "documents",
      name: "events",
      id: "event-1",
      meta: {
        name: "Events",
        attributes: [temporalAttr("startsAt", "starts_at")],
        tabularSections: [{
          name: "slots",
          tableName: "event_slots",
          attributes: [temporalAttr("happensAt", "happens_at")],
        }],
      },
      initial: {
        id: "event-1",
        startsAt: "2026-01-16T04:00:00.000+03:00",
        slots: [{ happensAt: "2026-01-16T05:30:00Z" }],
      },
    };

    render(<RecordForm form={form} />);
    fireEvent.click(screen.getByRole("button", { name: "Write" }));

    await waitFor(() =>
      expect(updateDocument).toHaveBeenCalledWith(
        "events",
        "event-1",
        expect.objectContaining({
          startsAt: "2026-01-16T04:00:00.000",
          slots: [expect.objectContaining({ happensAt: "2026-01-16T05:30:00" })],
        }),
        { silent: true }
      )
    );
  });
});
