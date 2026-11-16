import { describe, expect, it } from "vitest";
import { applyForm, applyList, customizationsFor, type EntityCustomization } from "@/lib/entity-customizations";
import type { ListDescriptor } from "@/components/lists/entity-list";
import type { FormDescriptor } from "@/components/entities/record-form";
import { commandIcon, presentCommands, presentRecordActions } from "@/lib/commands";

const list = (name: string) =>
  ({
    kind: "documents",
    name,
    title: "Orders",
    columns: [
      { columnName: "_number", fieldName: "number", label: "Number", width: "", hint: "", type: "text" },
      { columnName: "customer", fieldName: "customer", label: "Customer", width: "", hint: "", type: "ref" },
      { columnName: "total", fieldName: "total", label: "Total", width: "", hint: "", type: "number" },
      { columnName: "_date", fieldName: "date", label: "Date", width: "", hint: "", type: "date" },
    ],
    filters: [{ key: "status", label: "Status", column: "status", type: "multiOptions", options: [{ value: "1", label: "New" }] }],
  }) as unknown as ListDescriptor;

const attr = (fieldName: string, extra: Record<string, unknown> = {}) => ({
  fieldName, displayName: fieldName, columnName: fieldName, javaType: "String", length: 0, required: false,
  isRef: false, isEnum: false, precision: 0, scale: 0, visibleInList: true, visibleInForm: true,
  visibleInDetail: true, order: 0, group: "", ...extra,
});

const form = (name: string) =>
  ({
    kind: "documents",
    name,
    id: "1",
    actions: [
      { key: "duplicate", url: `onno://documents/${name}/1/duplicate` },
      { key: "delete", url: `onno://delete/documents/${name}/1`, danger: true },
      { key: "print-invoice", title: "Invoice", url: `onno://download/api/print/documents/${name}/1/invoice` },
    ],
    commands: [{ key: "advance", title: "Advance", target: "record" }],
    meta: {
      name: "Orders",
      attributes: [attr("notes"), attr("customer", { isRef: true, required: true })],
      tabularSections: [{ name: "items", tableName: "t", attributes: [attr("book", { isRef: true })] }],
    },
  }) as unknown as FormDescriptor;

const listWith = (name: string, c: EntityCustomization) => applyList(list(name), [c]);
const formWith = (name: string, c: EntityCustomization) => applyForm(form(name), [c]);

describe("customizationsFor", () => {
  it("finds an entity's customization by logical name or route key", () => {
    const orders: EntityCustomization = { list: { columns: ["number"] } };
    expect(customizationsFor({ Orders: orders }, { kind: "documents", name: "orders", logicalName: "Orders" })).toEqual([orders]);
    expect(customizationsFor({ "documents/orders": orders }, { kind: "documents", name: "orders" })).toEqual([orders]);
    expect(customizationsFor({ Books: orders }, { kind: "documents", name: "orders", logicalName: "Orders" })).toEqual([]);
  });
});

describe("applying customizations", () => {
  it("narrows, orders and relabels list columns, and sets grouping", () => {
    const d = listWith("c_list", { list: { columns: ["total", "number"], groupBy: "status" }, fields: { total: { label: "Sum" } } }) as unknown as Record<string, unknown> & ListDescriptor;
    expect(d.columns.map((c) => c.label)).toEqual(["Sum", "Number"]);
    expect(d.defaultGroupBy).toBe("status");
  });

  it("leaves uncustomized entities untouched", () => {
    const d = list("c_none");
    expect(applyList(d, [])).toBe(d);
  });

  it("applies field hints to header and tabular-section attributes", () => {
    const f = formWith("c_form", {
      fields: {
        notes: { input: "textarea", width: "half", group: "Details" },
        customer: { refSecondary: "phone", clearButton: true },
        "items.book": { label: "Title", filterBy: { supplier: "supplier" } },
      },
    });
    const notes = f.meta.attributes.find((a) => a.fieldName === "notes")!;
    expect([notes.input, notes.width, notes.group]).toEqual(["textarea", "half", "Details"]);
    const customer = f.meta.attributes.find((a) => a.fieldName === "customer")!;
    expect(customer.refSecondary).toBe("phone");
    // A required ref is never clearable, whatever the customization says.
    expect(customer.refClearButton).toBe(false);
    const book = f.meta.tabularSections![0].attributes[0];
    expect([book.displayName, book.refFilterBy]).toEqual(["Title", { supplier: "supplier" }]);
  });

  it("reads list groupings, subtotals and the map as typed values", () => {
    const d = listWith("c_list_values", {
      list: {
        groupable: ["customer", { date: "year" }],
        aggregates: [{ sum: "total", label: "Total", format: { money: "USD" } }],
        map: { lat: "lat", lng: "lng", label: (r) => String(r.number) },
      },
    });
    expect(d.groupable).toEqual([
      { columnName: "customer", label: "Customer", date: false },
      { columnName: "_date", label: "Date", date: true },
    ]);
    expect(d.defaultGroupByDateGranularity).toBe("year");
    expect(d.aggregates).toEqual([{ columnName: "total", fn: "sum", label: "Total", format: { money: "USD" } }]);
    expect(d.map?.latField).toBe("lat");
    expect(d.map?.label?.({ number: "SO-1" })).toBe("SO-1");
  });

  it("gives a filter without a type the control its field's type implies", () => {
    const d = listWith("c_filters", {
      list: {
        filters: [
          { field: "status", label: "Status" },
          { field: "customer", label: "Customer" },
          { field: "date", label: "Date" },
          { field: "total", label: "Total" },
          { field: "number", label: "Number" },
          { field: "number", label: "Prefix", type: "startsWith" },
          { field: "city", label: "City", options: ["Paris"] },
        ],
      },
    });
    expect(d.filters!.map((f) => f.type)).toEqual([
      "multiOptions", "multiOptions", "dateRange", "numberRange", "contains", "startsWith", "multiOptions",
    ]);
    // An enum filter keeps the server's resolved options.
    expect(d.filters![0].options).toEqual([{ value: "1", label: "New" }]);
  });

  it("ANDs a customization's where with the one the list was opened on", () => {
    const opened = { ...list("c_where"), baseWhere: { posted: true } } as ListDescriptor;
    const d = applyList(opened, [{ list: { where: { open: true } } }]);
    expect(d.baseWhere).toEqual({ and: [{ posted: true }, { open: true }] });
  });

  it("passes a field's display format, input and render function to its list column", () => {
    const render = () => "x";
    const d = listWith("c_cells", { fields: { total: { format: { money: "EUR" }, render }, customer: { input: "avatar" } } });
    const total = d.columns.find((c) => c.fieldName === "total")!;
    expect([total.format, total.render]).toEqual([{ money: "EUR" }, render]);
    expect(d.columns.find((c) => c.fieldName === "customer")!.input).toBe("avatar");
  });

  it("places built-ins and commands by key", () => {
    const f = formWith("c_actions", {
      commands: { advance: "primary", delete: "hidden", duplicate: { placement: "primary", title: "Copy", icon: "copy-plus" } },
    });
    // Record actions are named by key (messages for built-ins, the server's title for contributions),
    // placed, retitled and iconed by the customization; a hidden one leaves the header.
    const header = presentRecordActions(f.actions, f.commandPresentation, (key) => `msg:${key}`);
    expect(header.map((a) => [a.key, a.label, a.icon, a.placement, a.tone])).toEqual([
      ["duplicate", "Copy", "copy-plus", "primary", "accent"],
      ["print-invoice", "Invoice", "circle-dot", "menu", "normal"],
    ]);
    // Commands keep their presentation for the header to resolve against the record.
    const [advance] = presentCommands(f.commands, f.commandPresentation);
    expect([advance.title, advance.placement]).toEqual(["Advance", "primary"]);
  });

  it("presents list commands: menu by default, row/hidden on request, icons per record", () => {
    const d = listWith("c_commands", {
      commands: {
        pause: { placement: "row", icon: (row) => (row.paused ? "play" : "pause"), title: "Toggle" },
        archive: "hidden",
      },
    });
    const commands = presentCommands(
      [
        { key: "pause", title: "Pause", target: "record" },
        { key: "archive", title: "Archive", target: "record" },
        { key: "cancel", title: "Cancel", target: "record" },
      ],
      d.commandPresentation,
    );
    expect(commands.map((c) => [c.key, c.title, c.placement])).toEqual([
      ["pause", "Toggle", "row"],
      ["archive", "Archive", "hidden"],
      ["cancel", "Cancel", "menu"],
    ]);
    expect(commandIcon(commands[0], { paused: true })).toBe("play");
    expect(commandIcon(commands[0], { paused: false })).toBe("pause");
    // No icon named: a neutral default.
    expect(commandIcon(commands[2])).toBe("circle-dot");
  });

  it("tints rows through rowStyle", () => {
    const d = listWith("c_rows", { rowStyle: (row) => (row.status === "CANCELLED" ? "muted" : null) });
    expect(d.rowStyle?.({ status: "CANCELLED" })).toBe("muted");
    expect(d.rowStyle?.({ status: "NEW" })).toBeNull();
    expect(list("c_other").rowStyle).toBeUndefined();
  });

  it("names and icons built-in record actions by default, keeping danger semantic", () => {
    const header = presentRecordActions(form("c_defaults").actions, undefined, (key) => `msg:${key}`);
    expect(header.map((a) => [a.label, a.icon, a.placement, a.tone])).toEqual([
      ["msg:duplicate", "copy", "menu", "normal"],
      ["msg:delete", "trash-2", "menu", "danger"],
      ["Invoice", "circle-dot", "menu", "normal"],
    ]);
  });
});

describe("components in customizations", () => {
  it("hands the list and the form the components themselves", () => {
    const Shelf = () => null;
    const Panel = () => null;
    const Selection = () => null;
    const c: EntityCustomization = {
      list: { custom: { component: Shelf, label: "Shelf" }, selection: Selection },
      recordPanels: [{ component: Panel, title: "Notes" }],
    };
    const l = applyList({ ...list("c_components"), kind: "catalogs" } as ListDescriptor, [c]);
    expect(l.custom).toMatchObject({ component: Shelf, label: "Shelf" });
    expect(l.selectionComponent).toBe(Selection);
    const f = applyForm({ ...form("c_components"), kind: "catalogs" } as FormDescriptor, [c]);
    expect(f.panels).toEqual([{ component: Panel, title: "Notes" }]);
  });
});

describe("list cell command groups and group bands", () => {
  it("turns a field's commandGroup into its cell menu and passes groupsExpanded through", () => {
    const d = applyList(list("c_cellmenu"), [{
      list: { groupsExpanded: true },
      fields: { customer: { commandGroup: "Change status" } },
    }]);
    expect(d.columns.find((c) => c.fieldName === "customer")?.cellMenu).toBe("Change status");
    expect(d.columns.find((c) => c.fieldName === "total")?.cellMenu).toBeUndefined();
    expect(d.groupsExpanded).toBe(true);
  });
});
