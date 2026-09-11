import { useState } from "react";
import { afterEach, describe, expect, it } from "vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { TabularSectionEditor } from "@/components/entity-form-widget";
import type { AttributeMeta, EntityRecord, TabularSectionMeta } from "@/lib/types";

afterEach(cleanup);
const attribute = (fieldName: string, extra = {}) => ({
  fieldName, displayName: fieldName, javaType: "String", length: 100, ...extra,
}) as AttributeMeta;
const section: TabularSectionMeta = {
  name: "items", tableName: "items", attributes: [attribute("product"), attribute("quantity", { javaType: "Integer" })],
};
function Editor({ initial = [], meta = section, readOnly = false }: {
  initial?: EntityRecord[]; meta?: TabularSectionMeta; readOnly?: boolean;
}) {
  const [rows, setRows] = useState(initial);
  return <><TabularSectionEditor section={meta} rows={rows} readOnly={readOnly} onRows={setRows}
    onCell={(index, field, value) => setRows(rows.map((row, i) => i === index ? { ...row, [field]: value } : row))} />
    <output data-testid="rows">{JSON.stringify(rows)}</output></>;
}
const field = (row: number, name: string) => within(screen.getByRole("group", { name: `Row ${row}: ${name}` }))
  .getByRole(name === "quantity" ? "spinbutton" : "textbox");

describe("Tabular section editing", () => {
  it("focuses added rows and continues through scalar cells with Enter, preserving edits", () => {
    render(<Editor />);
    fireEvent.click(screen.getByRole("button", { name: "Add row" }));
    expect(field(1, "product")).toHaveFocus();
    fireEvent.change(field(1, "product"), { target: { value: "Paper" } });
    fireEvent.keyDown(field(1, "product"), { key: "Enter" });
    expect(field(1, "quantity")).toHaveFocus();
    fireEvent.change(field(1, "quantity"), { target: { value: "12" } });
    fireEvent.keyDown(field(1, "quantity"), { key: "Enter" });
    expect(field(2, "product")).toHaveFocus();
    fireEvent.keyDown(field(2, "product"), { key: "Enter", shiftKey: true });
    expect(field(1, "quantity")).toHaveFocus();
    expect(screen.getByTestId("rows")).toHaveTextContent('[{"product":"Paper","quantity":12},{}]');
  });
  it("keeps surviving row controls mounted and restores removed values without discarding later edits", () => {
    render(<Editor initial={[{ product: "First" }, { product: "Second" }]} />);
    const second = field(2, "product");
    fireEvent.click(screen.getByRole("button", { name: "Remove row 1" }));
    expect(field(1, "product")).toBe(second);
    expect(second).toHaveFocus();
    fireEvent.change(second, { target: { value: "Edited" } });
    fireEvent.click(screen.getByRole("button", { name: "Undo remove" }));
    expect(field(1, "product")).toHaveValue("First");
    expect(field(2, "product")).toBe(second);
    expect(second).toHaveValue("Edited");
  });
  it("returns focus to Add row when the final row is removed", () => {
    render(<Editor initial={[{ product: "Paper" }]} />);
    fireEvent.click(screen.getByRole("button", { name: "Remove row 1" }));
    expect(screen.getByRole("button", { name: "Add row" })).toHaveFocus();
    fireEvent.click(screen.getByRole("button", { name: "Undo remove" }));
    expect(field(1, "product")).toHaveValue("Paper");
    expect(field(1, "product")).toHaveFocus();
  });
  it("leaves multiline and composing Enter alone", () => {
    render(<Editor meta={{ ...section, attributes: [attribute("product", { widget: "textarea" })] }} initial={[{ product: "Notes" }]} />);
    fireEvent.keyDown(field(1, "product"), { key: "Enter" });
    expect(screen.getAllByRole("row")).toHaveLength(2);
    cleanup();
    render(<Editor initial={[{}]} />);
    field(1, "product").focus();
    fireEvent.keyDown(field(1, "product"), { key: "Enter", isComposing: true });
    expect(field(1, "product")).toHaveFocus();
  });
  it("disables read-only cells and hides editing actions", () => {
    render(<Editor readOnly initial={[{ product: "Paper" }]} />);
    expect(field(1, "product")).toBeDisabled();
    expect(screen.queryByRole("button", { name: "Add row" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Remove row 1" })).not.toBeInTheDocument();
  });
});
