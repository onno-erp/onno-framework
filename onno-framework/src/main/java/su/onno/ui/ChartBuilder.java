package su.onno.ui;

import su.onno.annotations.AccumulationRegister;
import su.onno.annotations.Catalog;
import su.onno.annotations.Document;
import su.onno.fields.Field;
import su.onno.fields.Fields;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Typed, progressively-disclosed authoring API for the built-in chart widget.
 *
 * <p>The common path stays compact ({@link #time(Field)}, {@link #sum(Field)}, {@link #area()});
 * presentation details such as axes, thresholds and per-measure colours are optional. The builder
 * deliberately exposes chart semantics rather than Recharts properties so authored pages remain
 * stable if the client renderer changes. {@link UiLayoutBuilder.WidgetBuilder#config} remains the
 * compatibility and custom-widget escape hatch.</p>
 */
public final class ChartBuilder<E> {

    public enum Kind { BAR, LINE, AREA, DONUT, PIE }
    public enum Aggregate { COUNT, SUM, AVG, MIN, MAX }
    public enum TimeBucket { AUTO, MINUTE, HOUR, DAY, WEEK, MONTH }
    public enum Axis { LEFT, RIGHT }
    public enum AxisScale { LINEAR, LOG }
    public enum Legend { TOP, RIGHT, BOTTOM, LEFT, HIDDEN }
    public enum Curve { MONOTONE, LINEAR, STEP }
    public enum DataLabels { HIDDEN, AUTO, ALL }
    public enum LineStyle { SOLID, DASHED, DOTTED }
    public enum Control { TYPE, SCALE, SERIES }

    private final UiLayoutBuilder.WidgetBuilder<E> widget;
    private int thresholdIndex;
    private Kind primaryKind = Kind.BAR;
    private boolean hasSecondary;

    ChartBuilder(UiLayoutBuilder.WidgetBuilder<E> widget) {
        this.widget = widget;
    }

    static <E> ChartBuilder<E> create(UiLayoutBuilder.WidgetBuilder<Void> raw, Class<E> source) {
        Objects.requireNonNull(source, "source");
        UiLayoutBuilder.WidgetBuilder<E> typed;
        if (source.isAnnotationPresent(Document.class)) typed = raw.document(source);
        else if (source.isAnnotationPresent(Catalog.class)) typed = raw.catalog(source);
        else if (source.isAnnotationPresent(AccumulationRegister.class)) typed = raw.register(source);
        else throw new IllegalArgumentException("Chart source must be a @Document, @Catalog, or @AccumulationRegister: " + source.getName());
        typed.type("chart");
        return new ChartBuilder<>(typed);
    }

    public ChartBuilder<E> order(int order) { widget.order(order); return this; }
    public ChartBuilder<E> width(String width) { widget.width(width); return this; }
    public ChartBuilder<E> rowBreak() { widget.rowBreak(); return this; }
    public ChartBuilder<E> hint(String hint) { widget.hint(hint); return this; }
    public ChartBuilder<E> filter(String filter) { put("filter", filter); return this; }

    /** Use a categorical x axis. */
    public <V> ChartBuilder<E> category(Field<E, V> field) {
        widget.groupBy(field);
        put("bucketMode", "fixed");
        return this;
    }

    /** Use a time x axis with automatic granularity. */
    public <V> ChartBuilder<E> time(Field<E, V> field) { return time(field, TimeBucket.AUTO); }

    /** Use a time x axis with automatic or explicitly fixed granularity. */
    public <V> ChartBuilder<E> time(Field<E, V> field, TimeBucket bucket) {
        widget.groupBy(field).dateField(field);
        TimeBucket value = Objects.requireNonNull(bucket, "bucket");
        put("groupByDate", value == TimeBucket.AUTO ? "day" : key(value));
        put("bucketMode", value == TimeBucket.AUTO ? "auto" : "fixed");
        return this;
    }

    public <V> ChartBuilder<E> seriesBy(Field<E, V> field) { widget.seriesBy(field); return this; }

    public ChartBuilder<E> maxSeries(int maximum) {
        if (maximum < 1) throw new IllegalArgumentException("maxSeries must be positive");
        put("maxSeries", Integer.toString(maximum));
        return this;
    }

    /** Pin a business series/slice label to a colour; unmatched labels use the theme palette. */
    public ChartBuilder<E> seriesColor(String series, String color) {
        put("seriesColor." + requireKey(series, "series"), requireText(color, "color"));
        return this;
    }

    public ChartBuilder<E> count() { primary(Aggregate.COUNT, null); return this; }
    public <N extends Number> ChartBuilder<E> sum(Field<E, N> field) { primary(Aggregate.SUM, field); return this; }
    public <N extends Number> ChartBuilder<E> average(Field<E, N> field) { primary(Aggregate.AVG, field); return this; }
    public <N extends Number> ChartBuilder<E> minimum(Field<E, N> field) { primary(Aggregate.MIN, field); return this; }
    public <N extends Number> ChartBuilder<E> maximum(Field<E, N> field) { primary(Aggregate.MAX, field); return this; }

    public ChartBuilder<E> label(String label) { put("label", label); return this; }
    public ChartBuilder<E> color(String color) { put("color", color); return this; }
    public ChartBuilder<E> strokeWidth(int pixels) { put("strokeWidth", encodeWidth(pixels)); return this; }
    public ChartBuilder<E> lineStyle(LineStyle style) { put("lineStyle", key(style)); return this; }
    public ChartBuilder<E> opacity(double opacity) { put("opacity", encodeOpacity(opacity)); return this; }
    public ChartBuilder<E> currency(String currency) { put("currency", currency); return this; }
    public ChartBuilder<E> unit(String unit) { put("unit", unit); return this; }
    public ChartBuilder<E> locale(String locale) { put("locale", locale); return this; }
    public ChartBuilder<E> format(String format) { put("format", format); return this; }

    public ChartBuilder<E> bar() { return kind(Kind.BAR); }
    public ChartBuilder<E> line() { return kind(Kind.LINE); }
    public ChartBuilder<E> area() { return kind(Kind.AREA); }
    public ChartBuilder<E> donut() { return kind(Kind.DONUT); }
    public ChartBuilder<E> pie() { return kind(Kind.PIE); }
    public ChartBuilder<E> kind(Kind kind) {
        Kind value = Objects.requireNonNull(kind, "kind");
        if (hasSecondary && (value == Kind.DONUT || value == Kind.PIE)) {
            throw new IllegalStateException("A combo chart's primary measure must be bar, line, or area");
        }
        primaryKind = value;
        put("kind", key(value));
        return this;
    }
    public ChartBuilder<E> stacked() { put("stacked", "true"); return this; }

    /** Add the optional right-axis measure. */
    public ChartBuilder<E> secondary(String label, Consumer<MeasureBuilder<E>> configurer) {
        if (primaryKind == Kind.DONUT || primaryKind == Kind.PIE) {
            throw new IllegalStateException("A combo chart's primary measure must be bar, line, or area");
        }
        MeasureBuilder<E> measure = new MeasureBuilder<>(widget);
        measure.label(label);
        configurer.accept(measure);
        measure.validate();
        hasSecondary = true;
        return this;
    }

    public ChartBuilder<E> axis(Axis side, Consumer<AxisBuilder> configurer) {
        AxisBuilder axis = new AxisBuilder(widget, side == Axis.LEFT ? "y" : "y2");
        configurer.accept(axis);
        return this;
    }

    public ChartBuilder<E> threshold(double value) { return threshold("", value, t -> {}); }

    public ChartBuilder<E> threshold(String label, double value, Consumer<ThresholdBuilder> configurer) {
        String prefix = "threshold." + thresholdIndex++ + ".";
        ThresholdBuilder threshold = new ThresholdBuilder(widget, prefix, value);
        if (label != null && !label.isBlank()) threshold.label(label);
        configurer.accept(threshold);
        return this;
    }

    public ChartBuilder<E> legend(Legend legend) { put("legend", key(legend)); return this; }
    public ChartBuilder<E> dataLabels(DataLabels labels) { put("dataLabels", key(labels)); return this; }
    public ChartBuilder<E> curve(Curve curve) { put("curve", key(curve)); return this; }
    public ChartBuilder<E> points(boolean visible) { put("points", Boolean.toString(visible)); return this; }
    public ChartBuilder<E> grid(boolean visible) { put("grid", Boolean.toString(visible)); return this; }

    public ChartBuilder<E> height(int pixels) {
        if (pixels < 120 || pixels > 800) throw new IllegalArgumentException("Chart height must be between 120 and 800 pixels");
        put("height", Integer.toString(pixels));
        return this;
    }

    public ChartBuilder<E> barSize(int pixels) {
        if (pixels < 2 || pixels > 160) throw new IllegalArgumentException("Bar size must be between 2 and 160 pixels");
        put("barSize", Integer.toString(pixels));
        return this;
    }

    public ChartBuilder<E> donutHole(int percent) {
        if (percent < 0 || percent > 90) throw new IllegalArgumentException("Donut hole must be between 0 and 90 percent");
        put("donutHole", Integer.toString(percent));
        return this;
    }

    public ChartBuilder<E> controls(Control... controls) {
        Set<String> values = new LinkedHashSet<>();
        for (Control control : controls) values.add(key(control));
        put("controls", String.join(",", values));
        return this;
    }

    /** Typed charts can still opt into a new/experimental client option without abandoning the DSL. */
    public ChartBuilder<E> config(String key, String value) { widget.config(key, value); return this; }

    private <N extends Number> void primary(Aggregate aggregate, Field<E, N> field) {
        put("metric", key(aggregate));
        if (field != null) widget.metricField(field);
    }

    private void put(String key, String value) { widget.config(key, requireText(value, key)); }
    private static String key(Enum<?> value) { return Objects.requireNonNull(value, "value").name().toLowerCase(Locale.ROOT); }
    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
    private static String requireKey(String value, String name) {
        return requireText(value, name).replace(".", "\\.");
    }

    /** Appearance and aggregation for the optional second measure. */
    public static final class MeasureBuilder<E> {
        private final UiLayoutBuilder.WidgetBuilder<E> widget;
        private boolean aggregate;

        MeasureBuilder(UiLayoutBuilder.WidgetBuilder<E> widget) { this.widget = widget; }

        public MeasureBuilder<E> count() { metric(Aggregate.COUNT, null); return this; }
        public <N extends Number> MeasureBuilder<E> sum(Field<E, N> field) { metric(Aggregate.SUM, field); return this; }
        public <N extends Number> MeasureBuilder<E> average(Field<E, N> field) { metric(Aggregate.AVG, field); return this; }
        public <N extends Number> MeasureBuilder<E> minimum(Field<E, N> field) { metric(Aggregate.MIN, field); return this; }
        public <N extends Number> MeasureBuilder<E> maximum(Field<E, N> field) { metric(Aggregate.MAX, field); return this; }
        public MeasureBuilder<E> bar() { return kind(Kind.BAR); }
        public MeasureBuilder<E> line() { return kind(Kind.LINE); }
        public MeasureBuilder<E> area() { return kind(Kind.AREA); }
        public MeasureBuilder<E> kind(Kind kind) {
            Kind value = Objects.requireNonNull(kind, "kind");
            if (value == Kind.DONUT || value == Kind.PIE) {
                throw new IllegalArgumentException("A secondary measure must be bar, line, or area");
            }
            widget.config("kind2", key(value));
            return this;
        }
        public MeasureBuilder<E> label(String label) { widget.config("label2", requireText(label, "label")); return this; }
        public MeasureBuilder<E> color(String color) { widget.config("color2", requireText(color, "color")); return this; }
        public MeasureBuilder<E> strokeWidth(int pixels) { widget.config("strokeWidth2", encodeWidth(pixels)); return this; }
        public MeasureBuilder<E> lineStyle(LineStyle style) { widget.config("lineStyle2", key(style)); return this; }
        public MeasureBuilder<E> opacity(double opacity) { widget.config("opacity2", encodeOpacity(opacity)); return this; }
        public MeasureBuilder<E> currency(String currency) { widget.config("currency2", requireText(currency, "currency")); return this; }
        public MeasureBuilder<E> unit(String unit) { widget.config("unit2", requireText(unit, "unit")); return this; }
        public MeasureBuilder<E> format(String format) { widget.config("format2", requireText(format, "format")); return this; }

        private <N extends Number> void metric(Aggregate value, Field<E, N> field) {
            widget.config("measure2", key(value));
            if (field != null) widget.config("field2", Fields.name(field));
            aggregate = true;
        }

        void validate() {
            if (!aggregate) throw new IllegalStateException("A secondary measure must declare count/sum/average/minimum/maximum");
        }
    }

    /** Stable semantic Y-axis controls. */
    public static final class AxisBuilder {
        private final UiLayoutBuilder.WidgetBuilder<?> widget;
        private final String prefix;
        AxisBuilder(UiLayoutBuilder.WidgetBuilder<?> widget, String prefix) { this.widget = widget; this.prefix = prefix; }
        public AxisBuilder minimum(double value) { put("Min", value); return this; }
        public AxisBuilder maximum(double value) { put("Max", value); return this; }
        public AxisBuilder label(String label) { widget.config(prefix + "Label", requireText(label, "axis label")); return this; }
        public AxisBuilder scale(AxisScale scale) { widget.config(prefix + "Scale", key(scale)); return this; }
        public AxisBuilder visible(boolean visible) { widget.config(prefix + "Visible", Boolean.toString(visible)); return this; }
        private void put(String suffix, double value) { widget.config(prefix + suffix, Double.toString(value)); }
    }

    /** A horizontal reference line/threshold. */
    public static final class ThresholdBuilder {
        private final UiLayoutBuilder.WidgetBuilder<?> widget;
        private final String prefix;
        ThresholdBuilder(UiLayoutBuilder.WidgetBuilder<?> widget, String prefix, double value) {
            this.widget = widget; this.prefix = prefix;
            widget.config(prefix + "value", Double.toString(value));
        }
        public ThresholdBuilder label(String label) { put("label", label); return this; }
        public ThresholdBuilder color(String color) { put("color", color); return this; }
        public ThresholdBuilder axis(Axis axis) { put("axis", key(axis)); return this; }
        public ThresholdBuilder style(LineStyle style) { put("style", key(style)); return this; }
        public ThresholdBuilder width(int pixels) { put("width", ChartBuilder.encodeWidth(pixels)); return this; }
        private void put(String key, String value) { widget.config(prefix + key, requireText(value, key)); }
    }

    private static String encodeWidth(int pixels) {
        if (pixels < 1 || pixels > 12) throw new IllegalArgumentException("Stroke width must be between 1 and 12 pixels");
        return Integer.toString(pixels);
    }

    private static String encodeOpacity(double value) {
        if (!Double.isFinite(value) || value < 0 || value > 1) throw new IllegalArgumentException("Opacity must be between 0 and 1");
        return Double.toString(value);
    }
}
