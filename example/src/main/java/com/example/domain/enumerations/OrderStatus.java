package com.example.domain.enumerations;

import su.onno.annotations.EnumLabel;
import su.onno.annotations.Enumeration;

/**
 * Lifecycle of a customer {@link com.example.domain.documents.Order}. An {@code @Enumeration} is a
 * fixed list <em>controlled by code</em> (contrast a {@code @Catalog}, which users edit at runtime):
 * put the annotation on a plain Java {@code enum} and the framework treats the constants as the
 * allowed values, rendered as a dropdown.
 *
 * <p>Each {@code @EnumLabel} gives a value a human label <em>and</em> a {@code color}, so the
 * framework paints it as a colored status pill in list cells, the form dropdown, and the detail view
 * (the color also rides the read API as {@code status_color}). No text-mirror column or hand-built
 * legend is needed — the enum is the single source of truth, and {@link OrderView}'s row actions and
 * {@link Order#handlePosting} branch on the constant directly.</p>
 */
@Enumeration(name = "Order Statuses", title = "Order status")
public enum OrderStatus {

    /** Placed, not yet confirmed. */
    @EnumLabel(value = "New", color = "#6B7280") NEW,

    /** Confirmed by a bookseller; ready to fulfil. */
    @EnumLabel(value = "Confirmed", color = "#D97706") CONFIRMED,

    /** Books picked and shipped to the customer. */
    @EnumLabel(value = "Shipped", color = "#4F46E5") SHIPPED,

    /** Delivered and settled — terminal, successful. */
    @EnumLabel(value = "Completed", color = "#059669") COMPLETED,

    /** Cancelled — terminal. A cancelled order posts no stock or sales movements. */
    @EnumLabel(value = "Cancelled", color = "#DC2626") CANCELLED
}
