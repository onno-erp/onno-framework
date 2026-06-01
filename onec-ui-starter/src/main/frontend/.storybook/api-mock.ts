import type {
  AppConfig,
  AuthUser,
  CatalogMeta,
  DashboardWidgetMeta,
  DocumentMeta,
  EntityRecord,
  LayoutSection,
  RegisterMeta,
  UiEvent,
  AttributeMeta,
} from "@/lib/types";

export class ApiError extends Error {
  constructor(message: string, public status: number) {
    super(message);
  }
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

let currentUser: AuthUser = {
  authenticated: true,
  username: "admin",
  roles: ["ROLE_ADMIN"],
};

const layout: LayoutSection[] = [
  {
    name: "Catalogs",
    order: 1,
    icon: "book-open",
    placement: "sidebar",
    items: [
      { name: "Products", type: "catalog", href: "/catalogs/products" },
      { name: "Customers", type: "catalog", href: "/catalogs/customers" },
      { name: "Warehouses", type: "catalog", href: "/catalogs/warehouses" },
    ],
  },
  {
    name: "Documents",
    order: 2,
    icon: "file-text",
    placement: "sidebar",
    items: [
      { name: "Goods Receipts", type: "document", href: "/documents/goods_receipt" },
      { name: "Invoices", type: "document", href: "/documents/invoice" },
      { name: "Sales", type: "document", href: "/documents/sale" },
    ],
  },
  {
    name: "Registers",
    order: 3,
    icon: "bar-chart",
    placement: "sidebar",
    items: [
      { name: "Stock", type: "register", href: "/registers/stock" },
      { name: "Sales", type: "register", href: "/registers/sales" },
    ],
  },
];

function attr(over: Partial<AttributeMeta> & { fieldName: string; displayName: string }): AttributeMeta {
  return {
    fieldName: over.fieldName,
    displayName: over.displayName,
    columnName: over.columnName ?? over.fieldName,
    javaType: over.javaType ?? "String",
    length: over.length ?? 0,
    required: over.required ?? false,
    isRef: over.isRef ?? false,
    refTarget: over.refTarget,
    isEnum: over.isEnum ?? false,
    enumName: over.enumName,
    enumValues: over.enumValues,
    precision: over.precision ?? 0,
    scale: over.scale ?? 0,
    visibleInList: over.visibleInList ?? true,
    visibleInForm: over.visibleInForm ?? true,
    visibleInDetail: over.visibleInDetail ?? true,
    order: over.order ?? 0,
    group: over.group ?? "",
    widthHint: over.widthHint ?? "",
    widget: over.widget ?? "",
  };
}

const productAttrs: AttributeMeta[] = [
  attr({ fieldName: "name", displayName: "Name", required: true, length: 200, order: 0 }),
  attr({ fieldName: "category", displayName: "Category", length: 100, order: 1 }),
  attr({ fieldName: "price", displayName: "Price", javaType: "BigDecimal", precision: 12, scale: 2, order: 2 }),
];

const documentAttrs: AttributeMeta[] = [
  attr({ fieldName: "warehouse", displayName: "Warehouse", isRef: true, refTarget: "Warehouse", order: 0 }),
  attr({ fieldName: "total", displayName: "Total", javaType: "BigDecimal", precision: 12, scale: 2, order: 1 }),
];

const catalogs: CatalogMeta[] = [
  { name: "Products", tableName: "products", codeLength: 9, attributes: productAttrs },
  { name: "Customers", tableName: "customers", codeLength: 9, attributes: productAttrs },
  { name: "Warehouses", tableName: "warehouses", codeLength: 9, attributes: productAttrs },
];

const documents: DocumentMeta[] = [
  { name: "GoodsReceipt", tableName: "goods_receipts", numberLength: 9, attributes: documentAttrs, tabularSections: [] },
  { name: "Invoice", tableName: "invoices", numberLength: 9, attributes: documentAttrs, tabularSections: [] },
  { name: "Sale", tableName: "sales", numberLength: 9, attributes: documentAttrs, tabularSections: [] },
  { name: "Booking", tableName: "bookings", numberLength: 9, attributes: documentAttrs, tabularSections: [] },
];

const registers: RegisterMeta[] = [
  { name: "Stock", tableName: "stock_register", type: "BALANCE", dimensions: [], resources: [] },
  { name: "Sales", tableName: "sales_register", type: "TURNOVER", dimensions: [], resources: [] },
];

const dashboardWidgets: DashboardWidgetMeta[] = [
  {
    title: "Products",
    widgetType: "count",
    order: 0,
    width: "1/3",
    entityType: "catalog",
    entityName: "Products",
    maxItems: 0,
    dateField: "",
    titleField: "",
    extraConfig: {},
  },
  {
    title: "Open documents",
    widgetType: "count",
    order: 1,
    width: "1/3",
    entityType: "document",
    entityName: "Invoice",
    maxItems: 0,
    dateField: "",
    titleField: "",
    extraConfig: {},
  },
  {
    title: "Goods receipts",
    widgetType: "count",
    order: 2,
    width: "1/3",
    entityType: "document",
    entityName: "GoodsReceipt",
    maxItems: 0,
    dateField: "",
    titleField: "",
    extraConfig: {},
  },
  {
    title: "Recent invoices",
    widgetType: "list",
    order: 3,
    width: "1/2",
    entityType: "document",
    entityName: "Invoice",
    maxItems: 5,
    dateField: "_date",
    titleField: "_number",
    extraConfig: {},
  },
  {
    title: "Top products",
    widgetType: "list",
    order: 4,
    width: "1/2",
    entityType: "catalog",
    entityName: "Products",
    maxItems: 5,
    dateField: "",
    titleField: "_description",
    extraConfig: {},
  },
  {
    title: "Revenue by customer",
    widgetType: "chart",
    order: 5,
    width: "1/2",
    entityType: "document",
    entityName: "Invoice",
    maxItems: 0,
    dateField: "",
    titleField: "",
    extraConfig: {
      kind: "bar",
      groupBy: "customer_display",
      metric: "sum",
      metricField: "total",
    },
  },
  {
    title: "Invoices by status",
    widgetType: "chart",
    order: 6,
    width: "1/2",
    entityType: "document",
    entityName: "Invoice",
    maxItems: 0,
    dateField: "",
    titleField: "",
    extraConfig: { kind: "donut", groupBy: "_posted", metric: "count" },
  },
  {
    title: "Invoices by status",
    widgetType: "kanban",
    order: 5,
    width: "full",
    entityType: "document",
    entityName: "Invoice",
    maxItems: 8,
    dateField: "_date",
    titleField: "_number",
    extraConfig: { groupBy: "_posted" },
  },
  {
    title: "Invoice schedule",
    widgetType: "calendar",
    order: 6,
    width: "full",
    entityType: "document",
    entityName: "Invoice",
    maxItems: 0,
    dateField: "_date",
    titleField: "_number",
    extraConfig: {},
  },
];

const productRows: EntityRecord[] = [
  { _id: "p1", _code: "P-000000001", _description: "Apples — Granny Smith", name: "Apples — Granny Smith", category: "Produce", price: 2.49 },
  { _id: "p2", _code: "P-000000002", _description: "Bananas — Cavendish", name: "Bananas — Cavendish", category: "Produce", price: 1.19 },
  { _id: "p3", _code: "P-000000003", _description: "Cherries — Bing", name: "Cherries — Bing", category: "Produce", price: 6.99 },
  { _id: "p4", _code: "P-000000004", _description: "Sourdough loaf", name: "Sourdough loaf", category: "Bakery", price: 5.5 },
  { _id: "p5", _code: "P-000000005", _description: "Walnut bread", name: "Walnut bread", category: "Bakery", price: 6.0 },
  { _id: "p6", _code: "P-000000006", _description: "Almond milk 1L", name: "Almond milk 1L", category: "Dairy", price: 3.2 },
];

const customerRows: EntityRecord[] = [
  { _id: "c1", _code: "C-000000001", _description: "Acme Foods", name: "Acme Foods", category: "Wholesale" },
  { _id: "c2", _code: "C-000000002", _description: "Bistro Lumière", name: "Bistro Lumière", category: "Restaurant" },
  { _id: "c3", _code: "C-000000003", _description: "Corner Market", name: "Corner Market", category: "Retail" },
];

const warehouseRows: EntityRecord[] = [
  { _id: "w1", _code: "W-000000001", _description: "Main warehouse", name: "Main warehouse" },
  { _id: "w2", _code: "W-000000002", _description: "Cold storage", name: "Cold storage" },
];

const invoiceRows: EntityRecord[] = [
  { _id: "i1", _number: "INV-000001", _date: "2026-05-08T09:14:00Z", _posted: true,  customer_display: "Acme Foods",       property_display: "Main warehouse", warehouse_display: "Main warehouse", total: 1240.5 },
  { _id: "i2", _number: "INV-000002", _date: "2026-05-07T15:02:00Z", _posted: true,  customer_display: "Bistro Lumière", property_display: "Main warehouse", warehouse_display: "Main warehouse", total: 482.0 },
  { _id: "i3", _number: "INV-000003", _date: "2026-05-07T11:22:00Z", _posted: false, customer_display: "Corner Market",    property_display: "Cold storage",   warehouse_display: "Cold storage",   total: 210.75 },
  { _id: "i4", _number: "INV-000004", _date: "2026-05-06T16:48:00Z", _posted: true,  customer_display: "Acme Foods",       property_display: "Main warehouse", warehouse_display: "Main warehouse", total: 95.0 },
  { _id: "i5", _number: "INV-000005", _date: "2026-05-06T08:30:00Z", _posted: false, customer_display: "Corner Market",    property_display: "Cold storage",   warehouse_display: "Cold storage",   total: 1820.4 },
  { _id: "i6", _number: "INV-000006", _date: "2026-05-12T10:00:00Z", _posted: false, customer_display: "Bistro Lumière", property_display: "Main warehouse", warehouse_display: "Main warehouse", total: 760.0 },
  { _id: "i7", _number: "INV-000007", _date: "2026-05-15T14:30:00Z", _posted: true,  customer_display: "Acme Foods",       property_display: "Cold storage",   warehouse_display: "Cold storage",   total: 2240.0 },
  { _id: "i8", _number: "INV-000008", _date: "2026-05-20T09:00:00Z", _posted: false, customer_display: "Corner Market",    property_display: "Main warehouse", warehouse_display: "Main warehouse", total: 320.0 },
];

const goodsReceiptRows: EntityRecord[] = [
  { _id: "g1", _number: "GR-000001", _date: "2026-05-08T07:00:00Z", _posted: true, warehouse_display: "Main warehouse", total: 3200 },
  { _id: "g2", _number: "GR-000002", _date: "2026-05-07T07:00:00Z", _posted: true, warehouse_display: "Main warehouse", total: 1840 },
  { _id: "g3", _number: "GR-000003", _date: "2026-05-06T07:00:00Z", _posted: false, warehouse_display: "Cold storage", total: 720 },
];

const saleRows: EntityRecord[] = [
  { _id: "s1", _number: "SO-000001", _date: "2026-05-08T13:00:00Z", _posted: true, warehouse_display: "Main warehouse", total: 1500 },
  { _id: "s2", _number: "SO-000002", _date: "2026-05-07T14:00:00Z", _posted: false, warehouse_display: "Main warehouse", total: 95 },
];

const catalogRows: Record<string, EntityRecord[]> = {
  products: productRows,
  customers: customerRows,
  warehouses: warehouseRows,
};

const bookingRows: EntityRecord[] = [
  { _id: "b1", _number: "BK-000001", _date: "2026-05-04T15:00:00Z", _end_date: "2026-05-09T11:00:00Z", duration_days: 5,  _posted: true,  customer_display: "Anya Kuznetsova",   property_display: "Lakeside cabin",     total: 980,
    assigned_to_display: "Marc Dubois",  assigned_to_avatar: "https://i.pravatar.cc/96?u=marc" },
  { _id: "b2", _number: "BK-000002", _date: "2026-05-06T15:00:00Z", _end_date: "2026-05-20T11:00:00Z", duration_days: 14, _posted: true,  customer_display: "Brian Okafor",      property_display: "Downtown loft",      total: 2940,
    assigned_to_display: "Lucía Roca",   assigned_to_avatar: "https://i.pravatar.cc/96?u=lucia" },
  { _id: "b3", _number: "BK-000003", _date: "2026-05-11T15:00:00Z", _end_date: "2026-05-13T11:00:00Z", duration_days: 2,  _posted: false, customer_display: "Claire Liu",        property_display: "Beach house",        total: 540,
    assigned_to_display: "Cynthia Reyes", assigned_to_avatar: "https://i.pravatar.cc/96?u=cynthia" },
  { _id: "b4", _number: "BK-000004", _date: "2026-05-15T15:00:00Z", _end_date: "2026-05-29T11:00:00Z", duration_days: 14, _posted: true,  customer_display: "David Salazar",     property_display: "Mountain chalet",    total: 4060,
    assigned_to_display: "Tomás Pérez",  assigned_to_avatar: "https://i.pravatar.cc/96?u=tomas" },
  { _id: "b5", _number: "BK-000005", _date: "2026-05-23T15:00:00Z", _end_date: "2026-05-26T11:00:00Z", duration_days: 3,  _posted: false, customer_display: "Esme Nakamura",     property_display: "Garden studio",      total: 690,
    assigned_to_display: "Sheri Wallace", assigned_to_avatar: "https://i.pravatar.cc/96?u=sheri" },
];

const documentRows: Record<string, EntityRecord[]> = {
  invoice: invoiceRows,
  goods_receipt: goodsReceiptRows,
  sale: saleRows,
  booking: bookingRows,
};

export function __setMockUser(user: AuthUser | null) {
  currentUser = user ?? { authenticated: false, username: "", roles: [] };
}

export async function streamUiEvents(
  _onEvent: (event: UiEvent) => void,
  signal: AbortSignal
): Promise<void> {
  await new Promise<void>((resolve) => {
    if (signal.aborted) return resolve();
    signal.addEventListener("abort", () => resolve());
  });
}

export const api = {
  getCurrentUser: async () => {
    await sleep(50);
    return currentUser;
  },
  login: async (username: string, password: string) => {
    await sleep(120);
    if (password === "admin" || password === "sales" || password === "warehouse") {
      currentUser = {
        authenticated: true,
        username,
        roles: [`ROLE_${username.toUpperCase()}`],
      };
      return currentUser;
    }
    throw new ApiError("Login failed", 401);
  },
  logout: async () => {
    await sleep(50);
    currentUser = { authenticated: false, username: "", roles: [] };
  },

  getConfig: async (): Promise<AppConfig> => ({ readOnly: false, basePath: "/ui" }),
  getTheme: async (): Promise<Record<string, string>> => ({}),

  getLayout: async () => layout,
  getCatalogs: async () => catalogs,
  getDocuments: async () => documents,
  getRegisters: async () => registers,
  getDashboardWidgets: async () => dashboardWidgets,

  listCatalog: async (name: string) => catalogRows[name] ?? [],
  getCatalogItem: async (name: string, id: string) => {
    const rows = catalogRows[name] ?? [];
    return rows.find((r) => r._id === id) ?? rows[0];
  },
  createCatalogItem: async (_name: string, data: EntityRecord) => ({ _id: "new", ...data }),
  updateCatalogItem: async (_name: string, id: string, data: EntityRecord) => ({ _id: id, ...data }),
  deleteCatalogItem: async (_name: string, _id: string) => undefined,

  listDocuments: async (name: string) => [...(documentRows[name] ?? [])],
  getDocument: async (name: string, id: string) => {
    const rows = documentRows[name] ?? [];
    return rows.find((r) => r._id === id) ?? rows[0];
  },
  createDocument: async (_name: string, data: EntityRecord) => ({ _id: "new", ...data }),
  updateDocument: async (name: string, id: string, data: EntityRecord) => {
    const rows = documentRows[name] ?? [];
    const row = rows.find((r) => r._id === id);
    if (row) {
      // The framework returns columns prefixed with `_`. Map a few common write fields.
      if (typeof data.date === "string") row._date = data.date;
      if (typeof data.number === "string") row._number = data.number;
      Object.assign(row, data);
    }
    return row ?? { _id: id, ...data };
  },
  deleteDocument: async (_name: string, _id: string) => undefined,
  postDocument: async (name: string, id: string) => {
    const rows = documentRows[name] ?? [];
    const row = rows.find((r) => r._id === id);
    if (row) row._posted = true;
    return row ?? { _id: id, _posted: true };
  },
  unpostDocument: async (name: string, id: string) => {
    const rows = documentRows[name] ?? [];
    const row = rows.find((r) => r._id === id);
    if (row) row._posted = false;
    return row ?? { _id: id, _posted: false };
  },

  getMovements: async (_name: string) => [] as EntityRecord[],
  getBalance: async (_name: string) => [] as EntityRecord[],
  getTurnover: async (_name: string) => [] as EntityRecord[],
};
