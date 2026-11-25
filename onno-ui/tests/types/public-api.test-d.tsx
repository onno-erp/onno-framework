/**
 * Type-level tests of `@onno/ui`'s public API against a typed model (`./model.ts`): what an app may
 * write type-checks, and what it must not (`@ts-expect-error`) does not. Checked by
 * `tsc -p tsconfig.types.json` (part of `npm run typecheck`); nothing here runs.
 */
import { expectTypeOf } from "vitest";
import {
  Chart, EntityList, Row, Stat, count, formatRecordNumber, links, openRoute, sum, useRecords,
  type AppDefinition, type DateField, type EntityCustomization, type LucideIcon, type Measure,
  type NumericField, type ValueFormat, type Where,
} from "@onno/ui";
import { Icon as IconByName, Truck, ArrowRight, Activity, ShoppingCart, type LucideIcon as IconsEntryName } from "@onno/ui/icons";
import { OrderStatus } from "./model";
import "./model";

// ---- Where -------------------------------------------------------------------------------------

expectTypeOf<{ posted: true; total: { gte: 100 }; status: { in: OrderStatus[] } }>().toMatchTypeOf<Where<"Orders">>();
expectTypeOf<{ id: { in: string[] } }>().toMatchTypeOf<Where<"Orders">>();
expectTypeOf<{ or: [{ status: OrderStatus }, { note: { contains: string } }] }>().toMatchTypeOf<Where<"Orders">>();
// @ts-expect-error an unknown field
const badField: Where<"Orders"> = { nope: 1 };
// @ts-expect-error a number field compared with text
const badValue: Where<"Orders"> = { total: "a lot" };
// @ts-expect-error an enum field takes the generated constants, not arbitrary text
const badEnum: Where<"Orders"> = { status: "SHIPPED" };
void [badField, badValue, badEnum];

// ---- NumericField / DateField / Measure --------------------------------------------------------

expectTypeOf<NumericField<"Orders">>().toEqualTypeOf<"total">();
expectTypeOf<NumericField<"Customers">>().toEqualTypeOf<"latitude" | "longitude">();
expectTypeOf<DateField<"Orders">>().toEqualTypeOf<"date" | "shippedAt">();
expectTypeOf(sum("Orders", "total", { posted: true })).toEqualTypeOf<Measure<"Orders">>();
expectTypeOf(count("Customers")).toEqualTypeOf<Measure<"Customers">>();
// @ts-expect-error only numeric fields can be summed
sum("Orders", "note");
// @ts-expect-error an entity the model does not have
count("Invoices");

// ---- widget props ------------------------------------------------------------------------------

const revenue = sum("Orders", "total", { status: OrderStatus.SHIPPED });
void <Stat title="Revenue" measure={revenue} sparkline={{ date: "week" }} />;
void <Chart title="By status" measure={revenue} by="status" />;
// @ts-expect-error grouping by a date bucket needs a date field
void <Chart title="Bad" measure={revenue} by={{ note: "week" }} />;
void <Row widths={[2, 1, { px: 320 }]}>{null}</Row>;
// @ts-expect-error widths are flex weights or pixels, not fraction strings
void <Row widths={["2/3", "1/3"]}>{null}</Row>;

// ---- EntityList, useRecords, links -------------------------------------------------------------

void <EntityList entity="Orders" columns={["number", "total"]} where={{ posted: false }} defaultSort="-date" />;
// @ts-expect-error entities are named by logical name, not route
void <EntityList entity="/documents/orders" />;
// @ts-expect-error the descriptor escape hatch is gone
void <EntityList entity="Orders" customize={(l: unknown) => l} />;

declare const ids: string[];
function useSelected() {
  const { data } = useRecords("Orders", { where: { id: { in: ids } }, sort: "-total", limit: 20 });
  expectTypeOf(data).toEqualTypeOf<import("./model").Order[] | undefined>();
  // @ts-expect-error sort names a field
  useRecords("Orders", { sort: "-nope" });
}
void useSelected;

openRoute(links.list("Orders", { where: { status: OrderStatus.NEW } }));
openRoute(links.record("Orders", "id-1"));
openRoute(links.newRecord("Orders", { customer: "c-1" }));
openRoute("/ops");
// @ts-expect-error a route is a page path or an onno:// link
openRoute("documents/orders");

// ---- EntityCustomization -----------------------------------------------------------------------

const orders: EntityCustomization<"Orders"> = {
  list: {
    columns: ["number", "date", "customer", "total"],
    groupable: ["status", { date: "month" }],
    aggregates: [{ sum: "total", label: "Total", format: { money: "USD" } }],
    filters: [{ field: "status", label: "Status" }, { field: "note", label: "Note", type: "startsWith" }],
  },
  fields: {
    total: { format: { money: "USD" }, render: (value, order) => `${value ?? 0} ${order.number}` },
    date: { format: { date: "medium" }, width: "half" },
    note: { input: "textarea" },
    "items.book": { filterBy: { supplier: "customer" } },
  },
  commands: { advance: { placement: "row", icon: ArrowRight } },
};
void orders;

const customers: EntityCustomization<"Customers"> = { list: { map: { lat: "latitude", lng: "longitude", label: (c) => c.email } } };
void customers;

// Calendar and timeline views place records by date fields; the lane is a reference field.
void <EntityList entity="Orders" calendar={{ start: "date", end: "shippedAt", title: "note", mode: "week" }}
  timeline={{ start: "date", end: "shippedAt", lane: "customer", laneEntity: "Customers", startHour: 8 }} defaultView="timeline" />;
// @ts-expect-error a calendar starts on a date field, not a number
void <EntityList entity="Orders" calendar={{ start: "total" }} />;
// @ts-expect-error a timeline needs an end
void <EntityList entity="Orders" timeline={{ start: "date" }} />;

// @ts-expect-error a field the entity does not have
const badFields: EntityCustomization<"Orders"> = { fields: { nope: {} } };
// @ts-expect-error inputs are named controls, one name each
const badInput: EntityCustomization<"Orders"> = { fields: { note: { input: "multiline" } } };
// @ts-expect-error formats are typed values, not pattern strings
const badFormat: EntityCustomization<"Orders"> = { fields: { total: { format: "currency:USD" } } };
// @ts-expect-error widths are "full" or "half"
const badWidth: EntityCustomization<"Orders"> = { fields: { total: { width: "1/2" } } };
// @ts-expect-error subtotals measure a numeric field
const badAggregate: EntityCustomization<"Orders"> = { list: { aggregates: [{ sum: "note", label: "x" }] } };
// @ts-expect-error a date grouping needs a date field
const badGrouping: EntityCustomization<"Orders"> = { list: { groupable: [{ note: "month" }] } };
// @ts-expect-error icons are imported components
const badIcon: EntityCustomization<"Orders"> = { commands: { advance: { icon: "not-an-icon" } } };
// @ts-expect-error a cascade's source is one of this entity's fields
const badCascade: EntityCustomization<"Orders"> = { fields: { "items.book": { filterBy: { supplier: "nope" } } } };
// @ts-expect-error the list/form escape-hatch functions are gone
const badList: EntityCustomization<"Orders"> = { list: (l: unknown) => l };
void [badFields, badInput, badFormat, badWidth, badAggregate, badGrouping, badIcon, badCascade, badList];

expectTypeOf<typeof Truck>().toMatchTypeOf<LucideIcon>();
// `@onno/ui/icons` ships all of lucide: named components plus the by-name `Icon`, which is onno's.
expectTypeOf<IconsEntryName>().toEqualTypeOf<LucideIcon>();
const truck = <Truck className="size-4" />;
const byName = <IconByName icon={ArrowRight} size={14} />;
void [truck, byName];
expectTypeOf<{ money: "EUR" }>().toMatchTypeOf<ValueFormat>();

// ---- AppDefinition: navigation links only declared pages ---------------------------------------

declare function defineAppTyped<P extends `/${string}` = never>(app: AppDefinition<P>): void;
const Page = () => null;
defineAppTyped({
  pages: [{ path: "/ops", component: Page, label: "Ops", icon: Activity }],
  navigation: [{ title: "Sales", icon: ShoppingCart, items: ["Orders", "/ops", { entity: "Customers", label: "Clients" }] }],
});
defineAppTyped({
  pages: [{ path: "/ops", component: Page }],
  // @ts-expect-error "/tasks" is not one of the app's pages
  navigation: [{ items: ["/tasks"] }],
});


// ---- Entity pages receive the right descriptor/controller ---------------------------------------
import { RecordPage, RecordFields, RecordLines, type RecordPageProps, type ListPageProps } from "@onno/ui";
function OrderPage({ form }: RecordPageProps<"Orders">) {
  form.set("note", "hello");
  form.set("items", [{ id: "line", parentId: "order", lineNumber: 1, book: null, quantity: 2 }]);
  // @ts-expect-error field does not exist
  form.set("typo", "hello");
  // @ts-expect-error incorrect value type
  form.set("total", "hello");
  const fields = <RecordFields form={form} fields={["customer", "note"]} overrides={{ note: { input: "textarea" } }} />;
  // @ts-expect-error wrong field
  const badFields = <RecordFields form={form} fields={["email"]} />;
  // @ts-expect-error selection is an array; restyling goes in `overrides`
  const objectFields = <RecordFields form={form} fields={{ note: { input: "textarea" } }} />;
  // @ts-expect-error wrong override field
  const badOverride = <RecordFields form={form} overrides={{ email: {} }} />;
  const lines = <RecordLines form={form} section="items" overrides={{ quantity: { label: "Qty" } }} />;
  // @ts-expect-error wrong section
  const badSection = <RecordLines form={form} section="lines" />;
  // @ts-expect-error wrong section column
  const badColumn = <RecordLines form={form} section="items" overrides={{ email: {} }} />;
  return <RecordPage form={form}>{fields}{lines}</RecordPage>;
}
function OrdersPage({ list }: ListPageProps<"Orders">) {
  // @ts-expect-error wrong list field
  const bad = <EntityList list={list} columns={["email"]} />;
  return <EntityList list={list} columns={["number", "total"]} sort="-date" overrides={{ total: { format: { money: "USD" } } }} />;
}
defineAppTyped({ pages: [{ list: "Orders", component: OrdersPage, icon: Truck }, { record: "Orders", component: OrderPage }] });

// ---- One customization per entity: selection is an array, `fields` is presentation only ---------
const ordersCustomization = {
  icon: Truck,
  list: { columns: ["number", "date", "total"] },
  form: { fields: ["customer", "note"] },
  fields: { total: { format: { money: "USD" } }, "items.quantity": { label: "Qty" } },
} satisfies EntityCustomization<"Orders">;
defineAppTyped({ entities: { Orders: ordersCustomization } });
// @ts-expect-error form fields are the entity's own (header) fields
const badFormField: EntityCustomization<"Orders"> = { form: { fields: ["email"] } };
// @ts-expect-error a document's number/date sit in the form header, not among its fields
const headerInForm: EntityCustomization<"Orders"> = { form: { fields: ["date"] } };
// @ts-expect-error a tabular section renders below the fields, not among them
const sectionInForm: EntityCustomization<"Orders"> = { form: { fields: ["items"] } };
// @ts-expect-error a line column is not a header field
const lineInForm: EntityCustomization<"Orders"> = { form: { fields: ["items.quantity"] } };
// @ts-expect-error order is chosen by `list.columns` / `form.fields`, not per field
const perFieldOrder: EntityCustomization<"Orders"> = { fields: { note: { order: 1 } } };
// @ts-expect-error component is bound to another entity
const wrongRecordPage: AppDefinition = { pages: [{ record: "Customers", component: OrderPage }] };
// @ts-expect-error registration must choose one addressing mode
const ambiguousPage: AppDefinition = { pages: [{ path: "/ops", list: "Orders", component: OrdersPage }] };
// @ts-expect-error valid names are also rejected: use imported components
const namedIcon: AppDefinition = { pages: [{ path: "/ops", component: Page, icon: "truck" }] };

// ---- Navigation composition -------------------------------------------------------------------
import { Navigation, NavigationGroup, NavigationLink, DefaultNavigation, type NavigationProps } from "@onno/ui";
function CustomNavigation({ navigation, header, footer }: NavigationProps) {
  return <Navigation>{header}<NavigationGroup title="Sales" icon={Truck} actions={<button>New</button>}>
    <NavigationLink entity="Orders" />
    <NavigationLink path="/ops" />
  </NavigationGroup><DefaultNavigation sections={navigation} />{footer}</Navigation>;
}
defineAppTyped({ navigationComponent: CustomNavigation, pages: [{ path: "/ops", component: Page }], navigation: [
  { title: "Sales", actions: <button>New order</button>, items: [{ title: "Work", items: ["Orders", { title: "Reports", items: ["/ops"] }] }] },
] });
// @ts-expect-error nested paths must name registered pages
const badNested: AppDefinition<"/ops"> = { navigation: [{ title: "Sales", items: [{ title: "Work", items: ["/typo"] }] }] };
// @ts-expect-error unknown entity
const badNavEntity = <NavigationLink entity="Typo" />;
// @ts-expect-error imported components are required for group icons
const badNavIcon = <NavigationGroup title="Sales" icon="truck"><span /></NavigationGroup>;

// ---- Replacements use public controllers and bindings ------------------------------------------
import { useRecordForm, useRecordField, RecordField, type FormDescriptor, type RefSelectProps, type DatePickerProps } from "@onno/ui";
function OwnRecord({ descriptor }: { descriptor: FormDescriptor }) {
  const form = useRecordForm<"Orders">(descriptor);
  const note = useRecordField(form, "note");
  expectTypeOf(note.value).toEqualTypeOf<string | null | undefined>();
  note.onChange("text");
  // @ts-expect-error wrong field value
  note.onChange(123);
  // @ts-expect-error unknown field
  useRecordField(form, "typo");
  const items = useRecordField(form, "items");
  expectTypeOf(items.value).toEqualTypeOf<import("./model").OrderLine[] | undefined>();
  return <RecordField form={form} field="note">{(field) => <textarea value={field.value ?? ""} disabled={field.disabled} onChange={(event) => field.onChange(event.target.value)} />}</RecordField>;
}
void OwnRecord;

expectTypeOf(formatRecordNumber("CON-00000001")).toEqualTypeOf<string>();
expectTypeOf(formatRecordNumber(null)).toEqualTypeOf<string>();
