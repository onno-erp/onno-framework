/**
 * How each entity presents, wherever it shows: its default list and record form, lists embedded
 * in pages, and custom pages built from EntityList / RecordFields. One file per entity; a page only
 * passes what differs on that page.
 */
import { books } from "./Books";
import { categories } from "./Categories";
import { customers } from "./Customers";
import { employees } from "./Employees";
import { orders } from "./Orders";
import { scheduleEvents } from "./ScheduleEvents";
import { stockReceipts } from "./StockReceipts";
import { suppliers } from "./Suppliers";

export const entities = {
  Books: books,
  Categories: categories,
  Customers: customers,
  Employees: employees,
  Orders: orders,
  "Schedule Events": scheduleEvents,
  "Stock Receipts": stockReceipts,
  Suppliers: suppliers,
};
