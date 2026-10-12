package su.onno.crm.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import su.onno.crm.domain.Conversation;
import su.onno.crm.service.CrmInboxWorkspace;
import su.onno.ui.PageBuilder;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static su.onno.crm.ui.CrmInboxView.Breakpoint.*;
import static su.onno.crm.ui.CrmInboxView.HeaderItem.*;

class CrmInboxViewTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void preservesWireContractAndSerializesEveryRegion() throws Exception {
        var view = new CrmInboxView("support").search(true).filters("status").filterSidebar(true)
                .controls(CrmInboxView.Control.SEARCH, CrmInboxView.Control.FILTERS)
                .views(CrmInboxView.View.INBOX)
                .toolbarPlacement(CrmInboxView.ToolbarPlacement.IN_LIST)
                .header(h -> h.start(AVATAR).center(TITLE).end(CONTACT))
                .header(MOBILE, h -> h.start(BACK).center(TITLE).end(CONTACT))
                .header(NARROW, h -> h.start(AVATAR).center(TITLE).end(CONTACT).secondary(EXTENSIONS))
                .listHeader(h -> h.start(CrmInboxView.ListItem.TITLE))
                .listHeader(MOBILE, h -> h.start(CrmInboxView.ListItem.COUNT))
                .composer(h -> h.end(CrmInboxView.ComposerItem.SEND))
                .composer(MOBILE, h -> h.start(CrmInboxView.ComposerItem.TOOLS))
                .rows(r -> r.badge("customerDisplay", "A \"quoted\" name", "VIP"));
        var page = new PageBuilder().view(view);
        var widget = page.widgets().getFirst();
        assertThat(widget.type()).isEqualTo("crmInboxWorkspaces");
        assertThat(widget.extraConfig()).containsEntry("workspace", "support")
                .containsEntry("toolbarControls", "search,filters").containsEntry("toolbarFilters", "status")
                .containsEntry("toolbarSearch", "true").containsEntry("filterSidebar", "true")
                .containsEntry("toolbarViewOptions", "custom").containsEntry("toolbarPlacement", "renderer")
                .containsKeys("chatToolbar", "narrowChatToolbar", "mobileChatToolbar", "listToolbar",
                        "mobileListToolbar", "composerToolbar", "mobileComposerToolbar");
        assertThat(JSON.readTree(widget.extraConfig().get("mobileChatToolbar"))).isEqualTo(JSON.readTree(
                "{\"start\":[\"back\"],\"center\":[\"title\"],\"end\":[\"contact\"],\"secondary\":[],\"overflow\":[],\"hidden\":[]}"));
        assertThat(JSON.readTree(widget.extraConfig().get("narrowChatToolbar"))).isEqualTo(JSON.readTree(
                "{\"start\":[\"avatar\"],\"center\":[\"title\"],\"end\":[\"contact\"],\"secondary\":[\"extensions\"],\"overflow\":[],\"hidden\":[]}"));
        assertThat(JSON.readTree(widget.extraConfig().get("mobileComposerToolbar")).at("/start/0").asText()).isEqualTo("tools");
        assertThat(JSON.readTree(widget.extraConfig().get("conversationRow")).at("/badges/0/equals").asText())
                .isEqualTo("A \"quoted\" name");
    }

    @Test void derivesItemKeysAndKeepsRenamedWireValues() {
        assertThat(OPEN_CONTACT.key()).isEqualTo("openContact");
        assertThat(SCHEDULE_CALL.key()).isEqualTo("scheduleCall");
        assertThat(CrmInboxView.View.INBOX.key()).isEqualTo("custom");
        assertThat(CrmInboxView.ToolbarPlacement.PAGE_HEADER.key()).isEqualTo("header");
        assertThat(CrmInboxView.ToolbarPlacement.IN_LIST.key()).isEqualTo("renderer");
    }

    @Test void freezesOnFirstPlacement() {
        var view = new CrmInboxView("support");
        var first = new PageBuilder().view(view);
        assertThatIllegalStateException().isThrownBy(() -> view.search(false));
        assertThatIllegalStateException().isThrownBy(() -> view.header(h -> h.start(TITLE)));
        assertThatIllegalStateException().isThrownBy(() -> view.title("Other"));
        var second = new PageBuilder().aside(p -> p.view(view));
        assertThat(first.widgets().getFirst().extraConfig()).containsOnly(entry("workspace", "support"));
        assertThat(second.aside().widgets().getFirst().extraConfig()).isEqualTo(first.widgets().getFirst().extraConfig());
    }

    @Test void rejectsInvalidConfiguration() {
        assertThatIllegalArgumentException().isThrownBy(() -> new CrmInboxView(" "));
        assertThatIllegalArgumentException().isThrownBy(() -> new CrmInboxView("support").rows(r -> r.previewLines(3)));
        assertThatIllegalArgumentException().isThrownBy(() -> new CrmInboxView("support").listHeader(NARROW, h -> {}));
        assertThatIllegalArgumentException().isThrownBy(() -> new CrmInboxView("support").composer(NARROW, h -> {}));
        assertThatIllegalArgumentException().isThrownBy(() -> new CrmInboxView("support")
                .header(h -> h.start(TITLE).secondary(EXTENSIONS, TITLE)));
        assertThatIllegalArgumentException().isThrownBy(() -> new CrmInboxView("support")
                .header(h -> h.end(CONTACT, CONTACT)));
    }

    @Test void validatorRequiresKnownWorkspaceAndDeclaredFilters() {
        var workspace = new CrmInboxWorkspace("support", "Support", Set.of("R"), c -> true)
                .list(spec -> spec.filter(Conversation::getStatus).label("Status").options())
                .facet(new CrmInboxWorkspace.Facet("tags", "Tags", () -> {
                    throw new AssertionError("option sources are not evaluated at startup");
                }, (rows, principal) -> Map.of()));
        CrmInboxViewValidator.validate(List.of(new CrmInboxView("support").filters("status", "tags")), List.of(workspace));
        assertThatIllegalStateException().isThrownBy(() -> CrmInboxViewValidator.validate(
                List.of(new CrmInboxView("missing")), List.of(workspace))).withMessageContaining("missing");
        assertThatIllegalStateException().isThrownBy(() -> CrmInboxViewValidator.validate(
                List.of(new CrmInboxView("support").filters("status", "stage")), List.of(workspace)))
                .withMessageContaining("[stage]").withMessageContaining("does not declare");
    }
}
