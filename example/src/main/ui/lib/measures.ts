import { count, sum } from "@onno/ui";
import { OrderStatus } from "../model.gen";

/** The shop's numbers, defined once and used across pages. */
export const openOrders = count("Orders", { open: true });
export const allOrders = count("Orders");
export const revenue = sum("Orders", "total", { posted: true });
export const postedOrders = count("Orders", { posted: true });
export const cancelledOrders = count("Orders", { status: OrderStatus.CANCELLED });

export const bookSalesRevenue = sum("Book Sales", "revenue");
export const unitsSold = sum("Book Sales", "quantity");
