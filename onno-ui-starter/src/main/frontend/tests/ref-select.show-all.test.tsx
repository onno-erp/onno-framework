import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import type { RefPickerDialogProps } from "@/components/ref-picker-dialog";

const { getCatalogItem, searchRefOptions, rememberRefPick, dialogProps } = vi.hoisted(() => ({
  getCatalogItem: vi.fn(),
  searchRefOptions: vi.fn(),
  rememberRefPick: vi.fn(),
  dialogProps: { current: null as RefPickerDialogProps | null },
}));

vi.mock("@/lib/api", () => ({
  api: { getCatalogItem, searchRefOptions, rememberRefPick },
}));

vi.mock("@/components/ui/popover", () => ({
  Popover: ({ children }: { children: ReactNode }) => <>{children}</>,
  PopoverTrigger: ({ children }: { children: ReactNode }) => <>{children}</>,
  PopoverContent: ({ children }: { children: ReactNode }) => <>{children}</>,
}));

// The dialog itself is covered by ref-picker-dialog.test.tsx; here only the hand-off matters.
vi.mock("@/components/ref-picker-dialog", () => ({
  RefPickerDialog: (props: RefPickerDialogProps) => {
    dialogProps.current = props;
    return props.open ? <div data-testid="picker-dialog" /> : null;
  },
}));

import { RefSelect } from "@/components/ref-select";

afterEach(() => {
  cleanup();
  getCatalogItem.mockReset();
  searchRefOptions.mockReset();
  rememberRefPick.mockReset();
  dialogProps.current = null;
});

describe("RefSelect \"Show all…\" (#411)", () => {
  it("opens the picker dialog with the field's filter, decorator, context, exclusions, value and query", async () => {
    searchRefOptions.mockResolvedValue([{ id: "c-1", description: "Ann Bell" }]);
    getCatalogItem.mockResolvedValue({ id: "c-7", description: "Current" });
    const context = { fieldPath: "lines.customer", formValues: { region: "n" }, section: "lines", rowIndex: 1 };
    render(<RefSelect targetName="Customers" fieldLabel="Customer" value="c-7" filter="region = n"
      optionDecorator="app.Availability" optionContext={context} excludedIds={["c-3"]} onChange={vi.fn()} />);

    fireEvent.click(await screen.findByRole("button", { name: /Current/ }));
    fireEvent.change(await screen.findByPlaceholderText(/Search/), { target: { value: "be" } });
    fireEvent.click(screen.getByRole("button", { name: "Show all…" }));

    expect(screen.getByTestId("picker-dialog")).toBeInTheDocument();
    expect(dialogProps.current).toMatchObject({
      fieldLabel: "Customer", targetKind: "catalog", targetName: "Customers", filter: "region = n",
      decorator: "app.Availability", optionContext: context, excludedIds: ["c-3"], value: "c-7", initialQuery: "be",
    });
  });

  it("takes a dialog pick exactly like a dropdown pick: same value, remembered as recent", async () => {
    searchRefOptions.mockResolvedValue([]);
    rememberRefPick.mockResolvedValue(undefined);
    const onChange = vi.fn();
    render(<RefSelect targetName="Customers" onChange={onChange} />);
    fireEvent.click(screen.getByRole("button", { name: /Select Customers/ }));
    fireEvent.click(await screen.findByRole("button", { name: "Show all…" }));

    dialogProps.current!.onPick("c-9", { id: "c-9", description: "Ivo Kahn" });

    expect(onChange).toHaveBeenCalledWith("c-9");
    expect(rememberRefPick).toHaveBeenCalledWith({ targetKind: "catalog", targetName: "Customers", id: "c-9" });
    await waitFor(() => expect(screen.getByText("Ivo Kahn")).toBeInTheDocument());
  });

  it("offers no link when the host turns it off (a polymorphic ref)", async () => {
    searchRefOptions.mockResolvedValue([{ id: "c-1", description: "Ann Bell" }]);
    render(<RefSelect targetName="Customers" showAll={false} onChange={vi.fn()} />);
    fireEvent.click(screen.getByRole("button", { name: /Select Customers/ }));
    await screen.findByText("Ann Bell");
    expect(screen.queryByRole("button", { name: "Show all…" })).toBeNull();
  });
});
