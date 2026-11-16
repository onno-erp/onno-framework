import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({ list: vi.fn() }));
vi.mock("@/lib/api", () => ({ api: { listHistory: mocks.list }, ApiError: class extends Error {} }));

import { RecordHistory } from "../src/components/collaboration/record-history";

const id = "12345678-1234-1234-1234-123456789012";
const target = { kind: "documents" as const, name: "visits", id };

function change(over: Record<string, unknown>) {
  return { section: null, line: null, rowChange: null, field: "complaint", label: "Complaint", type: "text",
    secret: false, old: null, new: null, ...over };
}

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

it("loads on first expand and shows who changed what, with secrets and rows", async () => {
  mocks.list.mockResolvedValueOnce({
    rows: [
      { id: "e2", event: "UPDATED", at: "2026-11-16T10:00:00Z", actor: "vet@clinic.test", actorId: null, changes: [
        change({ old: "limping", new: "limping badly" }),
        change({ field: "accessCode", label: "Access code", secret: true }),
        change({ section: "procedures", line: 2, rowChange: "ADDED", field: "title", label: "Title", new: "Bandage" }),
      ] },
    ],
    nextCursor: "cursor-2",
    hasMore: true,
  });
  render(<RecordHistory target={target} />);
  expect(mocks.list).not.toHaveBeenCalled();

  fireEvent.click(screen.getByRole("button", { name: /History/ }));
  expect(await screen.findByText("vet@clinic.test")).toBeTruthy();
  expect(mocks.list).toHaveBeenCalledWith("documents", "visits", id);
  expect(screen.getByText("Changed")).toBeTruthy();
  expect(screen.getByText("limping")).toBeTruthy();
  expect(screen.getByText("limping badly")).toBeTruthy();
  expect(screen.getByText("changed (value hidden)")).toBeTruthy();
  expect(screen.getByText("procedures, line 2 added")).toBeTruthy();
  expect(screen.getByText("Bandage")).toBeTruthy();

  mocks.list.mockResolvedValueOnce({
    rows: [{ id: "e1", event: "CREATED", at: "2026-11-15T09:00:00Z", actor: "system", actorId: null, changes: [] }],
    nextCursor: null,
    hasMore: false,
  });
  fireEvent.click(screen.getByRole("button", { name: "Show older changes" }));
  expect(await screen.findByText("Created")).toBeTruthy();
  expect(screen.getByText("System")).toBeTruthy();
  expect(mocks.list).toHaveBeenLastCalledWith("documents", "visits", id, "cursor-2");
  await waitFor(() => expect(screen.queryByRole("button", { name: "Show older changes" })).toBeNull());
});

it("reports a failed load instead of spinning", async () => {
  mocks.list.mockRejectedValueOnce(new Error("403"));
  render(<RecordHistory target={target} />);
  fireEvent.click(screen.getByRole("button", { name: /History/ }));
  expect(await screen.findByText("Could not load the history.")).toBeTruthy();
});
