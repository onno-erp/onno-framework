package su.onno.crm.ui;

import org.junit.jupiter.api.Test;
import su.onno.crm.service.CrmConnectionAccounts;
import su.onno.ui.PageBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards against Java/TypeScript key drift: every typed key the Java views emit must be one the
 * renderers in this repo actually read. Reads the TS sources relative to the module directory. */
class CrmRendererContractTest {
    private static final Path INBOX = Path.of("src/main/widgets/CrmInbox.tsx");
    private static final Path CONNECTIONS = Path.of("src/main/widgets/CrmConnections.tsx");
    private static final Path ENTITY_LIST = Path.of("../onno-ui-starter/src/main/frontend/src/components/entity-list-widget.tsx");

    @Test void toolbarItemEnumsMatchRendererItemIds() throws IOException {
        Map<String, Set<String>> toolbars = toolbarItemIds(read(INBOX));
        assertThat(toolbars.get("chat")).as("chat header item ids").isEqualTo(keys(CrmInboxView.HeaderItem.values()));
        assertThat(toolbars.get("list")).as("list header item ids").isEqualTo(keys(CrmInboxView.ListItem.values()));
        assertThat(toolbars.get("composer")).as("composer item ids").isEqualTo(keys(CrmInboxView.ComposerItem.values()));
    }

    @Test void toolbarConfigKeysAreReadByRenderer() throws IOException {
        String inbox = read(INBOX);
        for (String key : List.of("chatToolbar", "narrowChatToolbar", "mobileChatToolbar", "listToolbar",
                "mobileListToolbar", "composerToolbar", "mobileComposerToolbar", "conversationRow",
                "toolbarControls", "toolbarViewOptions", "toolbarSecondary", "toolbarFilters", "toolbarSearch",
                "toolbarPlacement", "mobileToolbar", "filterSidebar", "connectionsRoute"))
            assertThat(inbox).as("CrmInbox.tsx reads %s", key).containsAnyOf("extraConfig?." + key, "\"" + key + "\"");
    }

    @Test void optionEnumsMatchRendererLiterals() throws IOException {
        String inbox = read(INBOX), list = read(ENTITY_LIST);
        Set<String> controls = new HashSet<>(matches(list, "showControl\\(\"(\\w+)\"\\)"));
        controls.addAll(matches(inbox, "showToolbarControl\\(\"(\\w+)\"\\)"));
        assertThat(controls).as("list toolbar controls").containsAll(keys(CrmInboxView.Control.values()));

        Matcher views = Pattern.compile("views\\?: Array<([^>]+)>").matcher(list);
        assertThat(views.find()).as("entity list declares toolbar views").isTrue();
        assertThat(matches(views.group(1), "\"(\\w+)\"")).containsExactlyInAnyOrderElementsOf(keys(CrmInboxView.View.values()));

        assertThat(matches(inbox, "secondaryControls\\.includes\\(\"(\\w+)\"\\)"))
                .containsExactlyInAnyOrderElementsOf(keys(CrmInboxView.Secondary.values()));
        assertThat(inbox).contains("toolbarPlacement === \"" + CrmInboxView.ToolbarPlacement.IN_LIST.key() + "\" ? \"renderer\" : \""
                + CrmInboxView.ToolbarPlacement.PAGE_HEADER.key() + "\"");
        assertThat(inbox).contains("mobileToolbar === \"" + CrmInboxView.MobileToolbar.SHEET.key() + "\" ? \"sheet\" : \""
                + CrmInboxView.MobileToolbar.INLINE.key() + "\"");
    }

    @Test void connectionsViewKeysAreReadByRenderer() throws IOException {
        String source = read(CONNECTIONS);
        var widget = new PageBuilder().view(new CrmConnectionsView()
                .scopes(CrmConnectionAccounts.Scope.values()).defaultScope(CrmConnectionAccounts.Scope.PERSONAL)
                .providers("gmail").sharedLabel("S").personalLabel("P").showUnavailable(false)).widgets().getFirst();
        assertThat(widget.type()).isEqualTo("crmConnections");
        assertThat(read(INBOX.resolveSibling("CrmWorkspace.tsx")))
                .contains("registerWidget(\"crmConnections\", CrmConnections)", "registerWidget(\"crmSettings\", CrmConnections)");
        for (String key : widget.extraConfig().keySet()) assertThat(source).as("CrmConnections reads %s", key).contains("config." + key);
        Matcher scope = Pattern.compile("type Scope = ([^;]+);").matcher(source);
        assertThat(scope.find()).isTrue();
        assertThat(matches(scope.group(1), "\"(\\w+)\"")).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(CrmConnectionAccounts.Scope.values()).map(Enum::name).toList());
    }

    /** {@code <WorkspaceToolbar ... layout={{..., ...chrome?.X}} ... items={[{id:"a"...}, ...]} />} → X → ids. */
    private static Map<String, Set<String>> toolbarItemIds(String source) {
        Map<String, Set<String>> result = new HashMap<>();
        int at = 0;
        while ((at = source.indexOf("<WorkspaceToolbar", at)) >= 0) {
            int end = source.indexOf("]} />", at);
            String block = source.substring(at, end);
            Matcher chrome = Pattern.compile("\\.\\.\\.chrome\\?\\.(\\w+)}").matcher(block);
            if (chrome.find()) result.put(chrome.group(1), new HashSet<>(matches(block, "\\{id:\"(\\w+)\"")));
            at = end;
        }
        assertThat(result).containsKeys("chat", "list", "composer");
        return result;
    }
    private static List<String> matches(String source, String regex) {
        Matcher m = Pattern.compile(regex).matcher(source);
        List<String> found = new ArrayList<>();
        while (m.find()) found.add(m.group(1));
        return found;
    }
    private static Set<String> keys(CrmInboxView.Item[] items) {
        return Arrays.stream(items).map(CrmInboxView.Item::key).collect(Collectors.toSet());
    }
    private static String read(Path path) throws IOException {
        assertThat(path).as("renderer source %s (run from the onno-crm-starter module)", path.toAbsolutePath()).exists();
        return Files.readString(path);
    }
}
