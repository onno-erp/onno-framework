package su.onno.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * The builder a {@link Layout} configures: navigation sections (with per-field
 * hints), the shell/nav presentation, branding, identity link, and — for persona
 * layouts — the target roles and match priority. Reuses {@link UiLayoutBuilder}
 * for the section/shell/identity DSL; adds the persona metadata on top.
 */
public final class LayoutSpec {

    private final UiLayoutBuilder builder = new UiLayoutBuilder();
    private String title = "";
    private String theme = "";
    private int priority = 0;
    private final List<String> roles = new ArrayList<>();

    // ----- structure (delegates to UiLayoutBuilder) -----

    public UiLayoutBuilder.SectionBuilder section(String name) {
        return builder.section(name);
    }

    public UiLayoutBuilder.ShellBuilder shell() {
        return builder.shell();
    }

    /** Link login accounts to a directory record by matching a field. See {@link UiIdentityLink}. */
    public LayoutSpec identity(Class<?> directoryClass, String loginField) {
        builder.identity(directoryClass, loginField);
        return this;
    }

    // ----- persona metadata (ignored for the default layout) -----

    public LayoutSpec title(String title) {
        this.title = title;
        return this;
    }

    public LayoutSpec theme(String theme) {
        this.theme = theme;
        return this;
    }

    public LayoutSpec priority(int priority) {
        this.priority = priority;
        return this;
    }

    /** Roles that resolve a user into this persona layout. Empty = matches all. */
    public LayoutSpec roles(String... roles) {
        this.roles.addAll(List.of(roles));
        return this;
    }

    // ----- build accessors -----

    public String title() {
        return title;
    }

    public String theme() {
        return theme;
    }

    public int priority() {
        return priority;
    }

    public List<String> roles() {
        return List.copyOf(roles);
    }

    public List<UiLayout.Section> sections() {
        return builder.build();
    }

    public ShellConfig shellConfig() {
        return builder.buildShell();
    }

    public UiIdentityLink identity() {
        return builder.buildIdentity();
    }
}
