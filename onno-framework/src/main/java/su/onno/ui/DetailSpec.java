package su.onno.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Composes custom widgets below the fields on a saved catalog/document record surface. */
public final class DetailSpec<E> {

    private final List<WidgetBuilder<E>> widgets = new ArrayList<>();

    /** Add a record-aware widget, resolved through the same registry as page widgets. */
    public WidgetBuilder<E> widget(String title) {
        WidgetBuilder<E> widget = new WidgetBuilder<>(title);
        widgets.add(widget);
        return widget;
    }

    /** Built declarations in authored order, with {@link WidgetBuilder#order(int)} applied. */
    public List<DetailWidget> build() {
        return widgets.stream()
                .map(WidgetBuilder::build)
                .sorted(java.util.Comparator.comparingInt(DetailWidget::order))
                .toList();
    }

    /** Builder for one record-aware custom widget. */
    public static final class WidgetBuilder<E> {
        private final String title;
        private String type = "";
        private int order;
        private String width = "full";
        private final Map<String, String> extraConfig = new LinkedHashMap<>();
        private String hint = "";

        private WidgetBuilder(String title) {
            this.title = title == null ? "" : title;
        }

        /** Registered widget type, e.g. {@code "orderTimeline"}. */
        public WidgetBuilder<E> type(String type) {
            this.type = type == null ? "" : type;
            return this;
        }

        public WidgetBuilder<E> order(int order) {
            this.order = order;
            return this;
        }

        /** {@code "half"} or {@code "full"}; unknown values degrade to full width. */
        public WidgetBuilder<E> width(String width) {
            this.width = width == null ? "full" : width;
            return this;
        }

        public WidgetBuilder<E> config(String key, String value) {
            extraConfig.put(key, value);
            return this;
        }

        public WidgetBuilder<E> hint(String hint) {
            this.hint = hint == null ? "" : hint;
            return this;
        }

        private DetailWidget build() {
            if (type.isBlank()) {
                throw new IllegalStateException("A detail widget must declare a non-blank type");
            }
            return new DetailWidget(title, type, order, width, Map.copyOf(extraConfig), hint);
        }
    }
}
