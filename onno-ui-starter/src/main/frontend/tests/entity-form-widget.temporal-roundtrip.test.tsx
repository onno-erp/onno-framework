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

vi.mock("@/components/ref-select", () => ({ RefSelect: () => null }));
vi.mock("@/components/date-picker", () => ({
  DatePicker: ({ value }: { value?: string }) => <span data-testid="date-value">{value}</span>,
}));
vi.mock("@/components/geo-picker", () => ({ GeoPicker: () => null }));
vi.mock("@/components/map-editor", () => ({ MapEditor: () => null }));
vi.mock("@/components/image-picker", () => ({ ImagePicker: () => null, GalleryPicker: () => null }));
vi.mock("@/components/file-picker", () => ({ FilePicker: () => null }));
vi.mock("@/components/related-list-panel", () => ({ RelatedListPanel: () => null }));
vi.mock("@/lib/actions-menu-bridge", () => ({ ActionsCluster: () => null }));

import { EntityFormWidget, type FormDescriptor } from "@/components/entity-form-widget";

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

describe("EntityFormWidget temporal round trips (#267)", () => {
  it("submits an unchanged catalog LocalDateTime without its transport offset", async () => {
    updateCatalogItem.mockResolvedValue({ _id: "episode-1", observed_at: "2026-01-16T04:00" });
    validateRecord.mockResolvedValue({ fieldErrors: {}, formErrors: [] });
    const form: FormDescriptor = {
      kind: "catalogs",
      name: "episodes",
      id: "episode-1",
      title: "Edit Episode",
      submitLabel: "Save",
      meta: {
        name: "Episodes",
        attributes: [temporalAttr("observedAt", "observed_at")],
      },
      initial: { _id: "episode-1", observed_at: "2026-01-16T04:00:00.000+00:00" },
    };

    render(<EntityFormWidget form={form} />);
    expect(screen.getByTestId("date-value")).toHaveTextContent("2026-01-16T04:00:00.000");
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() =>
      expect(updateCatalogItem).toHaveBeenCalledWith(
        "episodes",
        "episode-1",
        expect.objectContaining({ observedAt: "2026-01-16T04:00:00.000" })
      )
    );
  });

  it("normalizes document header and tabular-section LocalDateTimes before save", async () => {
    updateDocument.mockResolvedValue({ _id: "event-1" });
    validateRecord.mockResolvedValue({ fieldErrors: {}, formErrors: [] });
    const form: FormDescriptor = {
      kind: "documents",
      name: "events",
      id: "event-1",
      title: "Edit Event",
      submitLabel: "Save",
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
        _id: "event-1",
        starts_at: "2026-01-16T04:00:00.000+03:00",
        slots: [{ happens_at: "2026-01-16T05:30:00Z" }],
      },
    };

    render(<EntityFormWidget form={form} />);
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() =>
      expect(updateDocument).toHaveBeenCalledWith(
        "events",
        "event-1",
        expect.objectContaining({
          startsAt: "2026-01-16T04:00:00.000",
          slots: [expect.objectContaining({ happensAt: "2026-01-16T05:30:00" })],
        })
      )
    );
  });
});
