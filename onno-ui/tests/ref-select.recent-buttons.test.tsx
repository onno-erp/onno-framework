import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { ReactNode } from "react";

const { getCatalogItem, searchRefOptions, rememberRefPick } = vi.hoisted(() => ({
  getCatalogItem: vi.fn(),
  searchRefOptions: vi.fn(),
  rememberRefPick: vi.fn(),
}));

vi.mock("@/lib/api", () => ({
  api: { getCatalogItem, searchRefOptions, rememberRefPick },
}));

// The popover content renders inline so the options are queryable without Radix portals.
vi.mock("@/components/ui/popover", () => ({
  Popover: ({ children }: { children: ReactNode }) => <>{children}</>,
  PopoverTrigger: ({ children }: { children: ReactNode }) => <>{children}</>,
  PopoverContent: ({ children }: { children: ReactNode }) => <>{children}</>,
}));

import { RefSelect } from "@/components/fields/ref-select";

afterEach(() => {
  cleanup();
  getCatalogItem.mockReset();
  searchRefOptions.mockReset();
  rememberRefPick.mockReset();
});

function openPicker() {
  fireEvent.click(screen.getByRole("button", { name: /Select Customers/ }));
}

describe("RefSelect recent picks (#409)", () => {
  it("heads the server's recent options with a Recent label and rules them off from the rest", async () => {
    searchRefOptions.mockResolvedValue([
      { id: "c-2", description: "Bea Stone", _recent: true },
      { id: "c-9", description: "Ivo Kahn", _recent: true },
      { id: "c-1", description: "Ann Bell" },
    ]);
    render(<RefSelect targetName="Customers" onChange={vi.fn()} />);

    openPicker();

    await waitFor(() => expect(screen.getByText("Ann Bell")).toBeInTheDocument());
    expect(screen.getAllByText("Recent")).toHaveLength(1);
    const recent = document.querySelectorAll("[data-onno-recent]");
    expect(Array.from(recent).map((el) => el.textContent)).toEqual(["Bea Stone", "Ivo Kahn"]);
    expect(screen.getByText("Ann Bell").closest("div.border-t")).not.toBeNull();
    // An empty query does not opt out: the server decides whether there are recent picks.
    expect(searchRefOptions.mock.calls[0][0]).toMatchObject({ query: "", targetName: "Customers" });
    expect(searchRefOptions.mock.calls[0][0]).not.toHaveProperty("recent");
  });

  it("shows no Recent label when the server returns none", async () => {
    searchRefOptions.mockResolvedValue([{ id: "c-1", description: "Ann Bell" }]);
    render(<RefSelect targetName="Customers" onChange={vi.fn()} />);

    openPicker();

    await waitFor(() => expect(screen.getByText("Ann Bell")).toBeInTheDocument());
    expect(screen.queryByText("Recent")).toBeNull();
  });

  it("remembers a pick on the server with the target and the chosen id", async () => {
    searchRefOptions.mockResolvedValue([{ id: "c-1", description: "Ann Bell" }]);
    rememberRefPick.mockResolvedValue(undefined);
    const onChange = vi.fn();
    render(<RefSelect targetName="Customers" onChange={onChange} />);

    openPicker();
    fireEvent.click(await screen.findByText("Ann Bell"));

    expect(onChange).toHaveBeenCalledWith("c-1");
    expect(rememberRefPick).toHaveBeenCalledWith({ targetKind: "catalog", targetName: "Customers", id: "c-1" });
  });

  it("keeps the pick when remembering fails", async () => {
    searchRefOptions.mockResolvedValue([{ id: "d-1", number: "SO-1" }]);
    rememberRefPick.mockRejectedValue(new Error("offline"));
    const onChange = vi.fn();
    render(<RefSelect targetName="Orders" refKind="document" onChange={onChange} />);

    fireEvent.click(screen.getByRole("button", { name: /Select Orders/ }));
    fireEvent.click(await screen.findByText("SO-1"));

    expect(onChange).toHaveBeenCalledWith("d-1");
    expect(rememberRefPick).toHaveBeenCalledWith({ targetKind: "document", targetName: "Orders", id: "d-1" });
  });
});

describe("RefSelect open and clear buttons (#409)", () => {
  it("opens the selected record through the workspace", () => {
    getCatalogItem.mockReturnValue(new Promise(() => {}));
    searchRefOptions.mockResolvedValue([]);
    const seen: string[] = [];
    const listener = (event: Event) => seen.push(String((event as CustomEvent).detail));
    window.addEventListener("onno:open", listener);
    try {
      render(<RefSelect targetName="Customers" value="c-7" openButton onChange={vi.fn()} />);
      fireEvent.click(screen.getByRole("button", { name: "Open record" }));
    } finally {
      window.removeEventListener("onno:open", listener);
    }
    expect(seen).toEqual(["onno://catalogs/customers/c-7"]);
  });

  it("does not open the picker when an inline button is clicked", () => {
    getCatalogItem.mockReturnValue(new Promise(() => {}));
    render(<RefSelect targetName="Customers" value="c-7" openButton onChange={vi.fn()} />);

    fireEvent.click(screen.getByRole("button", { name: "Open record" }));

    expect(searchRefOptions).not.toHaveBeenCalled();
  });

  it("hides the open button without a value or when turned off", () => {
    getCatalogItem.mockReturnValue(new Promise(() => {}));
    const { rerender } = render(<RefSelect targetName="Customers" openButton onChange={vi.fn()} />);
    expect(screen.queryByRole("button", { name: "Open record" })).toBeNull();

    rerender(<RefSelect targetName="Customers" value="c-7" onChange={vi.fn()} />);
    expect(screen.queryByRole("button", { name: "Open record" })).toBeNull();
  });

  it("clears an optional value with the inline button", () => {
    getCatalogItem.mockReturnValue(new Promise(() => {}));
    const onChange = vi.fn();
    render(<RefSelect targetName="Customers" value="c-7" clearable onChange={onChange} />);

    fireEvent.click(screen.getByRole("button", { name: "Clear value" }));

    expect(onChange).toHaveBeenCalledWith(null);
  });

  it("offers no inline clear when the field is not clearable", () => {
    getCatalogItem.mockReturnValue(new Promise(() => {}));
    render(<RefSelect targetName="Customers" value="c-7" openButton onChange={vi.fn()} />);

    expect(screen.queryByRole("button", { name: "Clear value" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Clear selection" })).toBeNull();
  });

  it("keeps the label clear of the inline buttons without moving the chevron", () => {
    getCatalogItem.mockReturnValue(new Promise(() => {}));
    render(<RefSelect targetName="Customers" value="c-7" openButton clearable onChange={vi.fn()} />);

    const field = screen.getByRole("button", { name: "Open record" }).closest("div")!;
    const trigger = within(field).getAllByRole("button")[0];
    expect(trigger).toHaveClass("text-left");
    const gap = trigger.querySelector("[data-onno-inline-gap]");
    expect(gap).toHaveClass("w-[50px]");
    // The gap sits before the chevron, so the chevron stays at the trigger's right edge.
    expect(gap?.nextElementSibling).toHaveClass("lucide-chevrons-up-down");
  });
});
