package com.onec.ui;

/**
 * App-shell presentation config: the {@link NavStyle} this layout's navigation
 * uses. Since a {@link Layout} is now authored per {@link Viewport}, the shell
 * carries a single style — author a separate layout to present the nav
 * differently on another device. A {@code null} style means "let the renderer
 * pick a sensible default for the viewport". Authored via {@code UiLayoutBuilder.shell()}.
 */
public record ShellConfig(NavStyle nav) {

    public static ShellConfig defaults() {
        return new ShellConfig(null);
    }
}
