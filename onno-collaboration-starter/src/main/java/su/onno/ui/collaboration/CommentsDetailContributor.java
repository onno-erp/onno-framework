package su.onno.ui.collaboration;

import su.onno.ui.EntityDetailUiContributor;
import su.onno.ui.UiViewResolver;
import su.onno.ui.comments.CommentProperties;
import su.onno.ui.divkit.Div;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class CommentsDetailContributor implements EntityDetailUiContributor {

    private final CommentProperties properties;
    private final UiViewResolver views;

    CommentsDetailContributor(CommentProperties properties, UiViewResolver views) {
        this.properties = properties;
        this.views = views;
    }

    @Override
    public Map<String, Object> contribute(Context context, Map<String, Object> content) {
        if (!properties.isEnabled() || !views.commentsEnabled(context.entityType())) {
            return content;
        }
        return append(content, context.kind(), context.name(), context.id().toString());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> append(Map<String, Object> content, String kind,
                                      String name, String id) {
        Object items = content.get("items");
        if (!(items instanceof List<?> existing)) {
            return content;
        }
        List<Map<String, Object>> next = new ArrayList<>((List<Map<String, Object>>) existing);
        Map<String, Object> panel = Div.custom("onno-comments",
                Map.of("payload", Map.of(
                        "target", Map.of("kind", kind, "name", name, "id", id))));
        Div.matchWidth(panel);
        next.add(panel);
        content.put("items", next);
        return content;
    }
}
