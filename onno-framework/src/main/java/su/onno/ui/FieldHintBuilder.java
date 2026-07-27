package su.onno.ui;

import su.onno.fields.Field;
import su.onno.fields.Fields;

/**
 * Per-field hint builder. Obtained from {@link EntityConfigBuilder#field(String)}
 * inside a lambda passed to {@code SectionBuilder.catalog/document/register}.
 *
 * <p>Chain field-level setters; call {@link #field(String)} to switch to another
 * field on the same entity. Anything not set falls through to the scanner default.</p>
 */
public class FieldHintBuilder<O, T> {

    private final EntityConfigBuilder<O> parent;
    private final String fieldName;

    private Boolean visibleInList;
    private Boolean visibleInForm;
    private Boolean visibleInDetail;
    private Integer order;
    private String group;
    private String width;
    private String widget;
    private String placeholder;
    private String format;
    private String hint;
    private String label;
    private String refSecondary;
    private String refFilter;
    private String refOptionDecorator;
    private Boolean uniqueWithinSection;

    FieldHintBuilder(EntityConfigBuilder<O> parent, String fieldName) {
        this.parent = parent;
        this.fieldName = fieldName;
    }

    public FieldHintBuilder<O, T> order(int order) {
        this.order = order;
        return this;
    }

    public FieldHintBuilder<O, T> group(String group) {
        this.group = group;
        return this;
    }

    public FieldHintBuilder<O, T> width(String width) {
        this.width = width;
        return this;
    }

    /**
     * Override the control used to edit this field. Built-in hints include {@code "switch"}/
     * {@code "toggle"} (boolean), {@code "textarea"}, {@code "geojson"} (the geometry editor —
     * draw points, paths, and areas, stored as GeoJSON), and the media widgets {@code "image"},
     * {@code "avatar"} (small round), {@code "images"}/{@code "gallery"} (several), and
     * {@code "file"} (any type). The map widgets render on a
     * theme-aware MapLibre basemap; the media widgets stream the chosen file to {@code POST /api/media}
     * and store only the returned reference URL, so a plain String attribute holds it — see
     * {@code su.onno.ui.media}.
     */
    public FieldHintBuilder<O, T> widget(String widget) {
        if (widget != null && (widget.equalsIgnoreCase("map")
                || widget.equalsIgnoreCase("geo")
                || widget.equalsIgnoreCase("geolocation"))) {
            throw new IllegalArgumentException(
                    "Legacy point widget '" + widget + "' is not supported; migrate values to GeoJSON "
                            + "and use widget(\"geojson\")");
        }
        if (widget != null && (widget.equalsIgnoreCase("photo")
                || widget.equalsIgnoreCase("photos"))) {
            throw new IllegalArgumentException(
                    "Legacy media widget '" + widget + "' is not supported; use "
                            + (widget.equalsIgnoreCase("photo")
                            ? "widget(\"image\")" : "widget(\"images\")"));
        }
        this.widget = widget;
        return this;
    }

    /** Placeholder text shown in this field's empty input on the edit form. */
    public FieldHintBuilder<O, T> placeholder(String placeholder) {
        this.placeholder = placeholder;
        return this;
    }

    /**
     * How this field's value is displayed in tables (list) and the detail surface. The hint is
     * interpreted by value type:
     * <ul>
     *   <li><b>Dates / date-times</b> — a date pattern, e.g. {@code "dd-MM-yy"},
     *       {@code "dd/MM/yyyy HH:mm"} (uppercase {@code D}/{@code Y} are accepted as day/year).</li>
     *   <li><b>Numbers</b> — {@code "integer"}, {@code "decimal"}, {@code "percent"},
     *       an explicit ISO currency such as {@code "currency:EUR"}, or a decimal pattern like
     *       {@code "#,##0.00"}.</li>
     * </ul>
     * It does not affect the edit form's input control (use {@link #widget(String)} for that).
     */
    public FieldHintBuilder<O, T> format(String format) {
        if (format != null && format.trim().equalsIgnoreCase("currency")) {
            throw new IllegalArgumentException(
                    "Currency format requires an ISO 4217 code, for example currency:USD");
        }
        this.format = format;
        return this;
    }

    /**
     * Optional help text for this field, surfaced in the UI as a hoverable {@code ?} icon next to
     * the field's label (on the edit form, list column header, and read-only detail view). Keep it
     * short — a sentence explaining what the field means or how to fill it in. Blank (default) shows
     * no icon.
     */
    public FieldHintBuilder<O, T> hint(String hint) {
        this.hint = hint;
        return this;
    }

    /**
     * Override this field's display label — the text shown next to the input on the edit form, in
     * the list column header, and on the read-only detail view. Works for both custom attributes
     * (overriding {@code @Attribute(displayName=...)}) and the built-in <b>system columns</b>
     * ({@code code}/{@code description} on catalogs; {@code number}/{@code date}/{@code posted} on
     * documents), which otherwise have no DSL path to a label. The primary use is localization,
     * e.g. {@code f.field("code").label("Код")} or {@code f.field("posted").label("Статус")}.
     *
     * <p>This is the form/detail counterpart to {@link ListSpec#label(String, String)} (which only
     * relabels the list header); a {@code ListSpec.label(...)} on the same field still wins for the
     * list column specifically. Blank/unset falls through to the descriptor's display name.</p>
     */
    public FieldHintBuilder<O, T> label(String label) {
        this.label = label;
        return this;
    }

    public FieldHintBuilder<O, T> hideInList() {
        this.visibleInList = false;
        return this;
    }

    public FieldHintBuilder<O, T> hideInForm() {
        this.visibleInForm = false;
        return this;
    }

    public FieldHintBuilder<O, T> hideInDetail() {
        this.visibleInDetail = false;
        return this;
    }

    public FieldHintBuilder<O, T> visibleInList(boolean v) {
        this.visibleInList = v;
        return this;
    }

    public FieldHintBuilder<O, T> visibleInForm(boolean v) {
        this.visibleInForm = v;
        return this;
    }

    public FieldHintBuilder<O, T> visibleInDetail(boolean v) {
        this.visibleInDetail = v;
        return this;
    }

    /**
     * For a {@code Ref} field: show a <em>secondary</em> attribute of the picked record beneath its
     * name in the ref picker, to disambiguate same-named records (e.g. a customer's phone). Names a
     * field on the ref's <em>target</em> entity; the data already rides along in the picker payload,
     * so this only tells the client which extra value to render. No effect on a non-ref field.
     *
     * <p>Independent of search: the typeahead already matches every text column of the target, so a
     * record is findable by this attribute whether or not it's shown. See issue #184.</p>
     */
    public FieldHintBuilder<O, T> refSecondary(String targetFieldName) {
        this.refSecondary = targetFieldName;
        return this;
    }

    /** Compiler-checked counterpart of {@link #refSecondary(String)}. */
    public <V> FieldHintBuilder<O, T> refSecondary(Field<T, V> targetField) {
        return refSecondary(Fields.name(targetField));
    }

    /**
     * For a {@code Ref} field: narrow the picker's options with a predicate over the ref's
     * <em>target</em> entity, in the same small {@code field op value AND …} grammar a dashboard
     * widget's {@code config("filter", …)} uses. A {@code ${field}} placeholder substitutes the
     * form's <em>current</em> value of another field, making pickers cascade:
     *
     * <pre>
     * f.field("employee").refFilter("department = ${department}");   // header field → header field
     * f.field("lines.book").refFilter("supplier = ${supplier}");     // line cell ← header field
     * </pre>
     *
     * <p>While a referenced field is still empty the filter is skipped (the picker shows
     * everything); once it's set, options narrow and a later change clears the dependent field.
     * Static predicates work too (no placeholder), e.g. {@code "active = true"}. Left-hand names
     * are fields of the target entity; parsing is the injection-safe {@code WidgetFilter}
     * (known-column allowlist, bound values), so an unknown name degrades to "no filter", never an
     * error. No effect on a non-ref field.</p>
     */
    public FieldHintBuilder<O, T> refFilter(String filter) {
        this.refFilter = filter;
        return this;
    }

    /**
     * Decorate this reference picker's options using the given application-provided Spring bean.
     * The decorator receives live parent-form and tabular-row context and can add a status badge,
     * disable an option, and explain a conflict. Resolution is batched across the current search
     * page.
     *
     * <pre>
     * f.field("participants.employee")
     *     .refOptions(EmployeeAvailability.class)
     *     .uniqueWithinSection();
     * </pre>
     */
    public FieldHintBuilder<O, T> refOptions(Class<? extends RefOptionDecorator> decoratorType) {
        if (decoratorType == null) {
            throw new IllegalArgumentException("decoratorType must not be null");
        }
        this.refOptionDecorator = decoratorType.getName();
        return this;
    }

    /**
     * For a reference column in a tabular section, disable values already selected in sibling rows.
     * The current row's own value remains selectable. This is immediate UI guidance; domain
     * validation remains the authoritative safeguard on write.
     */
    public FieldHintBuilder<O, T> uniqueWithinSection() {
        this.uniqueWithinSection = true;
        return this;
    }

    /** Switch to configuring another field on the same entity. */
    public FieldHintBuilder<O, Object> field(String name) {
        return parent.field(name);
    }

    /** Continue with a compiler-checked field on the same entity. */
    public <V> FieldHintBuilder<O, Object> field(Field<O, V> field) {
        return parent.field(field);
    }

    String fieldName() {
        return fieldName;
    }

    FieldHint build() {
        return new FieldHint(
                visibleInList, visibleInForm, visibleInDetail,
                order, group, width, widget, placeholder, format, hint, label, refSecondary, refFilter,
                refOptionDecorator, uniqueWithinSection);
    }
}
