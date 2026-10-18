package su.onno.ui;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import su.onno.metadata.MetadataRegistry;
import su.onno.ui.comments.CommentProperties;
import su.onno.ui.notifications.NotificationProperties;
import su.onno.ui.divkit.ShellLayoutBuilder;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DivKitControllerHomeNavigationTest {
    @Test
    void authoredHomeIsAutomaticByDefault() {
        Map<String, Object> shell = shell(new LayoutSpec(), true);
        assertThat(items(shell)).extracting(ShellLayoutBuilder.NavItem::path).containsExactly("/");
        assertThat(items(shell).getFirst().label()).isEqualTo("Overview");
        assertThat(shell.get("home")).isEqualTo("/");
    }

    @Test
    void hiddenHomeRemainsTheLogoTargetEvenWithOtherNavigation() {
        LayoutSpec spec = new LayoutSpec().homeInNavigation(false);
        spec.section("Work").page("/tasks", "Tasks", "list");
        Map<String, Object> shell = shell(spec, true);
        assertThat(items(shell)).extracting(ShellLayoutBuilder.NavItem::path).containsExactly("/tasks");
        assertThat(shell.get("home")).isEqualTo("/");
    }

    @Test
    void explicitHomeReplacesAutomaticEntryAndKeepsAuthoredLabel() {
        LayoutSpec spec = new LayoutSpec();
        spec.section("Work").page("/tasks", "Tasks", "list").page("/", "My home", "star");
        Map<String, Object> shell = shell(spec, true);
        assertThat(items(shell)).extracting(ShellLayoutBuilder.NavItem::path).containsExactly("/tasks", "/");
        assertThat(items(shell).getLast().label()).isEqualTo("My home");
        assertThat(items(shell).getLast().icon()).isEqualTo("star");
        assertThat(shell.get("home")).isEqualTo("/");
    }

    @Test
    void disablingAutomaticEntryDoesNotRemoveExplicitHomeLink() {
        LayoutSpec spec = new LayoutSpec().homeInNavigation(false);
        spec.section("Work").page("/", "My home", "star");
        assertThat(items(shell(spec, true))).extracting(ShellLayoutBuilder.NavItem::path).containsExactly("/");
    }

    @Test
    void hiddenSectionDoesNotSuppressAutomaticHome() {
        LayoutSpec spec = new LayoutSpec();
        spec.section("Hidden").placement(UiLayout.Placement.HIDDEN).page("/", "Hidden home", "star");
        assertThat(items(shell(spec, true))).extracting(ShellLayoutBuilder.NavItem::label).containsExactly("Overview");
    }

    @Test
    void dashboardlessAppStillLandsOnFirstVisiblePage() {
        LayoutSpec spec = new LayoutSpec();
        spec.section("Work").page("/tasks", "Tasks", "list");
        Map<String, Object> shell = shell(spec, false);
        assertThat(items(shell)).extracting(ShellLayoutBuilder.NavItem::path).containsExactly("/tasks");
        assertThat(shell.get("home")).isEqualTo("/tasks");
    }

    private static Page page(String route, String title) {
        return new Page() {
            @Override public String route() { return route; }
            @Override public void compose(PageBuilder b) { b.title(title); }
        };
    }

    private static Map<String, Object> shell(LayoutSpec spec, boolean withHome) {
        UiLayout layout = new UiLayout(spec.sections(), List.of(), List.of(), null, spec.shellConfig());
        LayoutSet layouts = new LayoutSet(Map.of(Viewport.DESKTOP, layout), Set.of());
        UiAccessService access = mock(UiAccessService.class);
        when(access.roles(null)).thenReturn(Set.of());
        CurrentUserResolver users = mock(CurrentUserResolver.class);
        when(users.resolve(null)).thenReturn(new CurrentUserResolver.CurrentUser("user", "User", null, null, null));
        List<Page> pages = withHome ? List.of(page("/", "Overview"), page("/tasks", "Tasks"))
                : List.of(page("/tasks", "Tasks"));
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        DivKitController controller = new DivKitController(layouts, new UiLayoutResolver(new MetadataRegistry()),
                new UiProfileResolver(), access, users, mock(ResolvedMetadataService.class),
                new UiViewResolver(null, List.of()), new PageResolver(pages),
                mock(CatalogQueryService.class), mock(DocumentQueryService.class), mock(RegisterQueryService.class),
                mock(UiActionResolver.class), mock(RelatedListReader.class), new UiProperties(), new UiMessages(Map.of()),
                beans.getBeanProvider(CommentProperties.class), beans.getBeanProvider(NotificationProperties.class));
        try {
            return controller.shell(null, "desktop", "light", null);
        } finally {
            controller.destroy();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ShellLayoutBuilder.NavItem> items(Map<String, Object> shell) {
        return ((List<ShellLayoutBuilder.NavSection>) shell.get("navigation")).stream()
                .flatMap(section -> section.items().stream()).toList();
    }
}
