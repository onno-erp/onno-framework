import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";

const { searchCatalog, searchRefOptions } = vi.hoisted(() => ({
  searchCatalog: vi.fn(),
  searchRefOptions: vi.fn(),
}));

vi.mock("@/lib/api", () => ({
  api: {
    searchCatalog,
    searchRefOptions,
  },
}));

vi.mock("@/components/ui/popover", () => ({
  Popover: ({ children }: { children: ReactNode }) => <>{children}</>,
  PopoverTrigger: ({ children }: { children: ReactNode }) => <>{children}</>,
  PopoverContent: ({ children }: { children: ReactNode }) => <>{children}</>,
}));

import { RefSelect } from "@/components/ref-select";

const available = {
  _id: "employee-1",
  _description: "Alex Morgan",
  _optionBadge: "Available",
  _optionTone: "success",
};
const unavailable = {
  _id: "employee-2",
  _description: "Sam Lee",
  _optionBadge: "Unavailable",
  _optionTone: "danger",
  _optionDisabled: true,
  _optionReason: "Overlaps event EV-12",
};

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  searchCatalog.mockReset();
  searchRefOptions.mockReset();
});

describe("RefSelect contextual options (#272)", () => {
  it("posts live context and renders a disabled status badge with its reason", async () => {
    searchRefOptions.mockResolvedValue([available, unavailable]);

    render(
      <RefSelect
        targetName="Employees"
        optionDecorator="com.example.EmployeeAvailability"
        optionContext={{
          fieldPath: "participants.employee",
          formValues: {
            startsAt: "2026-07-20T10:00",
            endsAt: "2026-07-20T12:00",
          },
          section: "participants",
          rowIndex: 1,
          rowValues: { role: "HOST" },
          documentId: "event-1",
        }}
        onChange={vi.fn()}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: /Select Employees/ }));

    await waitFor(() => expect(searchRefOptions).toHaveBeenCalled());
    expect(searchRefOptions).toHaveBeenCalledWith(
      expect.objectContaining({
        targetKind: "catalog",
        targetName: "Employees",
        decorator: "com.example.EmployeeAvailability",
        fieldPath: "participants.employee",
        rowIndex: 1,
        documentId: "event-1",
      })
    );

    const disabled = await screen.findByRole("button", {
      name: /Sam Lee.*Overlaps event EV-12.*Unavailable/,
    });
    expect(disabled).toBeDisabled();
    expect(screen.getByText("Available")).toHaveClass("text-emerald-700");
  });

  it("immediately disables a sibling-row selection while leaving other options active", async () => {
    searchCatalog.mockResolvedValue([
      available,
      { _id: "employee-2", _description: "Sam Lee" },
    ]);
    const onChange = vi.fn();

    render(
      <RefSelect
        targetName="Employees"
        excludedIds={["employee-1"]}
        onChange={onChange}
      />
    );
    fireEvent.click(screen.getByRole("button", { name: /Select Employees/ }));

    const excluded = await screen.findByRole("button", {
      name: /Alex Morgan.*Selected in another row.*Already selected/,
    });
    expect(excluded).toBeDisabled();

    fireEvent.click(screen.getByRole("button", { name: /Sam Lee/ }));
    expect(onChange).toHaveBeenCalledWith("employee-2");
  });
});
