import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import type { ReactNode } from "react";

const { getCatalogItem, searchCatalog } = vi.hoisted(() => ({
  getCatalogItem: vi.fn(),
  searchCatalog: vi.fn(),
}));

vi.mock("@/lib/api", () => ({
  api: {
    getCatalogItem,
    searchCatalog,
  },
}));

// Keep this unit test on RefSelect's nullable contract; Radix's portal/focus timers are covered by
// the shared popover tests and otherwise add unrelated async focus noise here.
vi.mock("@/components/ui/popover", () => ({
  Popover: ({ children }: { children: ReactNode }) => <>{children}</>,
  PopoverTrigger: ({ children }: { children: ReactNode }) => <>{children}</>,
  PopoverContent: ({ children }: { children: ReactNode }) => <>{children}</>,
}));

import { RefSelect } from "@/components/ref-select";

afterEach(() => {
  cleanup();
  getCatalogItem.mockReset();
  searchCatalog.mockReset();
});

describe("RefSelect nullable values (#270)", () => {
  it("clears an existing optional value to null", () => {
    getCatalogItem.mockReturnValue(new Promise(() => {}));
    searchCatalog.mockResolvedValue([]);
    const onChange = vi.fn();

    render(
      <RefSelect
        targetName="Shows"
        value="show-1"
        clearable
        onChange={onChange}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: "Clear selection" }));

    expect(onChange).toHaveBeenCalledWith(null);
  });

  it("does not offer clearing when the field is required", () => {
    getCatalogItem.mockReturnValue(new Promise(() => {}));
    searchCatalog.mockResolvedValue([]);

    render(
      <RefSelect
        targetName="Shows"
        value="show-1"
        onChange={vi.fn()}
      />
    );

    expect(screen.queryByRole("button", { name: "Clear selection" })).toBeNull();
  });
});
