package com.onec.ui;

/**
 * Implement this interface and register as a Spring bean to configure
 * the UI layout (sidebar sections, ordering, icons, placement).
 */
public interface OneCUiConfigurer {
    void configure(UiLayoutBuilder layout);
}
