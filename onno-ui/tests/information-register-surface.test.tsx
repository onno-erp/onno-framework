import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { defaultNavigation, isEntitySurface, type AppModel } from "@/lib/app-model";
import { findEntity } from "@/lib/model-store";
import { buildNavigation } from "@/lib/app-config";
import { InformationRegisterSurface } from "@/components/lists/information-register-surface";
import type { ListDescriptor } from "@/components/lists/entity-list";

vi.mock("@/components/lists/entity-list", () => ({
  EntityListView: ({ list, queryParams, headerExtra }: { list: ListDescriptor; queryParams?: unknown; headerExtra?: import("react").ReactNode }) =>
    <div>{headerExtra}<output data-testid="query">{JSON.stringify({ filters: list.filters, queryParams })}</output></div>,
}));
const model: AppModel = {
  user: { username: "u", displayName: "User", avatarUrl: "", roles: [] },
  features: { comments: false, notifications: false }, entities: [], constants: [],
  informationRegisters: [{ logicalName: "Rates", name: "rates", route: "/information-registers/rates", title: "Rates", context: "Finance", periodic: true }],
};
const list: ListDescriptor = { kind: "registers", name: "rates", title: "Rates", columns: [], searchable: false,
  sort: { column: "_period", descending: true }, newUrl: null, canWrite: false, pageSize: 50,
  filters: [
    { key: "period", column: "_period", label: "Date", type: "dateRange", options: [] },
    { key: "book", column: "book", label: "Book", type: "multiOptions", optionsFrom: "Books", options: [] },
  ] };
afterEach(cleanup);

describe("information-register surfaces", () => {
  it("resolves readable registers in default and authored navigation and recognizes direct routes", () => {
    expect(defaultNavigation(model)).toEqual([{ title: "Finance", items: [{ label: "Rates", icon: "chart-column", path: "/information-registers/rates" }] }]);
    expect(findEntity(model, "Rates")?.route).toBe("/information-registers/rates");
    expect(buildNavigation({ navigation: [{ title: "Reports", items: ["Rates", "/information-registers/rates"] }] }, model, () => true)[0].items).toHaveLength(2);
    expect(buildNavigation({ navigation: [{ items: ["Rates", "/information-registers/rates"] }] }, { ...model, informationRegisters: [] }, () => true)).toEqual([]);
    expect(isEntitySurface("/information-registers/rates?sort=rate")).toBe(true);
  });
  it("switches periodic registers between history and a fixed end-of-day slice", () => {
    render(<InformationRegisterSurface register={{ periodic: true, list }} />);
    expect(screen.getByTestId("query").textContent).toContain('"_period"');
    fireEvent.click(screen.getByText("Latest values"));
    fireEvent.change(screen.getByLabelText("Date"), { target: { value: "2026-11-10" } });
    expect(JSON.parse(screen.getByTestId("query").textContent!)).toEqual({ filters: [list.filters![1]], queryParams: { at: ["2026-11-10T23:59:59.999999"] } });
    fireEvent.click(screen.getByText("History"));
    expect(screen.queryByLabelText("Date")).toBeNull();
    expect(screen.getByTestId("query").textContent).toContain('"_period"');
  });
  it("shows no slice controls for non-periodic registers", () => {
    render(<InformationRegisterSurface register={{ periodic: false, list }} />);
    expect(screen.queryByText("Latest values")).toBeNull();
  });
});
