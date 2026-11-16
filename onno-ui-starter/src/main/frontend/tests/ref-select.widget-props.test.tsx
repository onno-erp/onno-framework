import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import type { ReactNode } from "react";

const { getCatalogItem, searchRefOptions } = vi.hoisted(() => ({
  getCatalogItem: vi.fn(),
  searchRefOptions: vi.fn(),
}));

vi.mock("@/lib/api", () => ({
  api: { getCatalogItem, searchRefOptions, rememberRefPick: vi.fn() },
}));

// Radix's portal/focus timers are covered by the shared popover tests; here only RefSelect's own
// props matter, so the popover renders inline.
vi.mock("@/components/ui/popover", () => ({
  Popover: ({ children }: { children: ReactNode }) => <>{children}</>,
  PopoverTrigger: ({ children }: { children: ReactNode }) => <>{children}</>,
  PopoverContent: ({ children }: { children: ReactNode }) => <>{children}</>,
}));

import { RefSelect } from "@/components/ref-select";

afterEach(() => {
  cleanup();
  getCatalogItem.mockReset();
  searchRefOptions.mockReset();
});

describe("RefSelect props for custom widgets", () => {
  it("names the trigger by id and aria-label and shows the placeholder", () => {
    render(<><label htmlFor="supplier">Supplier</label>
      <RefSelect id="supplier" targetName="Contacts" placeholder="Not chosen yet" onChange={vi.fn()} /></>);

    const trigger = screen.getByLabelText("Supplier");
    expect(trigger.tagName).toBe("BUTTON");
    expect(trigger).toHaveTextContent("Not chosen yet");
    expect(trigger).not.toHaveTextContent("Select Contacts");
  });

  it("merges a widget's trigger classes over the select-trigger look", () => {
    render(<RefSelect aria-label="Owner" targetName="TeamMembers" className="h-7 w-auto text-xs" onChange={vi.fn()} />);

    const trigger = screen.getByRole("button", { name: "Owner" });
    expect(trigger).toHaveClass("h-7", "w-auto", "text-xs");
    expect(trigger).not.toHaveClass("w-full");
  });

  it("offers + New by default and hides it when allowCreate is off", async () => {
    searchRefOptions.mockResolvedValue([{ id: "c-1", description: "Ann Bell" }]);
    const { unmount } = render(<RefSelect aria-label="Supplier" targetName="Contacts" onChange={vi.fn()} />);
    fireEvent.click(screen.getByRole("button", { name: "Supplier" }));
    expect(await screen.findByRole("button", { name: /New Contacts/ })).toBeInTheDocument();
    unmount();

    render(<RefSelect aria-label="Supplier" targetName="Contacts" allowCreate={false} onChange={vi.fn()} />);
    fireEvent.click(screen.getByRole("button", { name: "Supplier" }));
    expect(await screen.findByRole("button", { name: /Ann Bell/ })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /New Contacts/ })).toBeNull();
    expect(screen.getByRole("button", { name: "Show all…" })).toBeInTheDocument();
  });

  it("does not open or offer clearing while disabled", () => {
    getCatalogItem.mockResolvedValue({ id: "c-1", description: "Ann Bell" });
    render(<RefSelect aria-label="Supplier" targetName="Contacts" value="c-1" clearable openButton disabled onChange={vi.fn()} />);

    const trigger = screen.getByRole("button", { name: "Supplier" });
    expect(trigger).toBeDisabled();
    fireEvent.click(trigger);
    expect(searchRefOptions).not.toHaveBeenCalled();
    expect(screen.queryByRole("button", { name: "Clear value" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Open record" })).toBeNull();
  });

  it("shows the host's label for the value while the record loads, and keeps it if the read fails", async () => {
    getCatalogItem.mockRejectedValue(new Error("403"));
    render(<RefSelect aria-label="Owner" targetName="TeamMembers" value="tm-1" valueLabel="Marco Bellini" onChange={vi.fn()} />);

    const trigger = screen.getByRole("button", { name: "Owner" });
    expect(trigger).toHaveTextContent("Marco Bellini");
    await Promise.resolve();
    expect(trigger).toHaveTextContent("Marco Bellini");
  });
});
