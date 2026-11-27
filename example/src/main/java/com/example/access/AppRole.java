package com.example.access;

import su.onno.access.RoleKey;

/**
 * The bookstore's roles. An account (or an identity-provider token) holds them by name; what each
 * may do is declared once, in {@link AccessConfiguration}. {@code ADMIN} is an ordinary application
 * role here — it may do exactly what its grants say, nothing is implied by its name.
 */
public enum AppRole implements RoleKey {
    /** Back office: staff, settings, period close, print templates, CRM channels, every process task. */
    ADMIN,
    /** Runs the shop: books, customers, suppliers, orders, stock receipts and the team schedule. */
    MANAGER
}
