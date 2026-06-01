package com.onec.ui;

/**
 * How the app's navigation chrome presents. A renderer-agnostic hint the shell
 * emitter shapes the nav around and the client positions the nav card by:
 *
 * <ul>
 *   <li>{@code SIDEBAR} — a vertical rail beside the content (classic desktop).</li>
 *   <li>{@code TOPBAR} — a horizontal bar above the content.</li>
 *   <li>{@code BOTTOM_BAR} — a tab bar pinned below the content (mobile).</li>
 * </ul>
 */
public enum NavStyle {
    SIDEBAR,
    TOPBAR,
    BOTTOM_BAR
}
