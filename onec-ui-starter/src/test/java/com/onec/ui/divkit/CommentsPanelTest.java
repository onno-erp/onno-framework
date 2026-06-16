package com.onec.ui.divkit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SurfaceDivBuilder#withComments} appends the {@code onec-comments} {@code div-custom} panel
 * to a built detail surface, carrying the entity triple the React bridge loads the thread from — and
 * leaves a surface without an {@code items} list untouched.
 */
class CommentsPanelTest {

    @Test
    @SuppressWarnings("unchecked")
    void appendsCommentsPanelCarryingTheEntityTriple() {
        Map<String, Object> content = Div.vertical(new ArrayList<>(List.of(Div.custom("x", Map.of()))));
        Div.id(content, "onec-content");

        SurfaceDivBuilder.withComments(content, "documents", "Invoices", "abc-123");

        List<Map<String, Object>> items = (List<Map<String, Object>>) content.get("items");
        Map<String, Object> panel = items.get(items.size() - 1);
        assertThat(panel.get("custom_type")).isEqualTo("onec-comments");
        Map<String, Object> props = (Map<String, Object>) panel.get("custom_props");
        Map<String, Object> targetProp = (Map<String, Object>) props.get("target");
        assertThat(targetProp).containsEntry("kind", "documents")
                .containsEntry("name", "Invoices")
                .containsEntry("id", "abc-123");
    }

    @Test
    void leavesContentWithoutItemsUntouched() {
        Map<String, Object> content = new java.util.HashMap<>();
        content.put("type", "text");

        Map<String, Object> result = SurfaceDivBuilder.withComments(content, "catalogs", "Properties", "id");

        assertThat(result).isSameAs(content);
        assertThat(result).doesNotContainKey("items");
    }
}
