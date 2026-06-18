package com.example.desktop;

import su.onno.desktop.DesktopApp;
import su.onno.desktop.DesktopSpec;

import org.springframework.stereotype.Component;

/**
 * The native desktop presentation of the rentals app — the structural peer of
 * {@link com.example.ui.layouts.MainLayout}, but for the window rather than the
 * in-app navigation. The Tauri shell reads this (via {@code /api/desktop/manifest})
 * at launch to size and title the window around the live DivKit UI.
 */
@Component
public class RentalsDesktop implements DesktopApp {

    @Override
    public void configure(DesktopSpec app) {
        app.title("Rentals ERP")
                .window(w -> w.size(1400, 900).minSize(1024, 720))
                .singleInstance(true)
                .tray(t -> t.tooltip("Rentals ERP").quit("Quit"))
                .splash("Starting Rentals ERP…");
    }
}
