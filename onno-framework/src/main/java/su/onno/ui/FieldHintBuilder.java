package su.onno.ui;

/**
 * Per-field hint builder. Obtained from {@link EntityConfigBuilder#field(String)}
 * inside a lambda passed to {@code SectionBuilder.catalog/document/register}.
 *
 * <p>Chain field-level setters; call {@link #field(String)} to switch to another
 * field on the same entity. Anything not set falls through to the scanner default.</p>
 */
public class FieldHintBuilder {

    private final EntityConfigBuilder parent;
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

    FieldHintBuilder(EntityConfigBuilder parent, String fieldName) {
        this.parent = parent;
        this.fieldName = fieldName;
    }

    public FieldHintBuilder order(int order) {
        this.order = order;
        return this;
    }

    public FieldHintBuilder group(String group) {
        this.group = group;
        return this;
    }

    public FieldHintBuilder width(String width) {
        this.width = width;
        return this;
    }

    /**
     * Override the control used to edit this field. Built-in hints include {@code "switch"}/
     * {@code "toggle"} (boolean), {@code "textarea"}, {@code "map"}/{@code "geo"} (a single point
     * stored as a "lat,lng" string), {@code "geojson"} (the full geometry editor — draw points,
     * paths, and areas, stored as a GeoJSON string), and the media widgets {@code "image"}/
     * {@code "photo"}, {@code "avatar"} (small round), {@code "images"}/{@code "gallery"}/
     * {@code "photos"} (several), and {@code "file"} (any type). The map widgets render on a
     * theme-aware MapLibre basemap; the media widgets stream the chosen file to {@code POST /api/media}
     * and store only the returned reference URL, so a plain String attribute holds it — see
     * {@code su.onno.ui.media}.
     */
    public FieldHintBuilder widget(String widget) {
        this.widget = widget;
        return this;
    }

    /** Placeholder text shown in this field's empty input on the edit form. */
    public FieldHintBuilder placeholder(String placeholder) {
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
     *       {@code "currency"} (or {@code "currency:EUR"}), or a decimal pattern like
     *       {@code "#,##0.00"}.</li>
     * </ul>
     * It does not affect the edit form's input control (use {@link #widget(String)} for that).
     */
    public FieldHintBuilder format(String format) {
        this.format = format;
        return this;
    }

    /**
     * Optional help text for this field, surfaced in the UI as a hoverable {@code ?} icon next to
     * the field's label (on the edit form, list column header, and read-only detail view). Keep it
     * short — a sentence explaining what the field means or how to fill it in. Blank (default) shows
     * no icon.
     */
    public FieldHintBuilder hint(String hint) {
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
    public FieldHintBuilder label(String label) {
        this.label = label;
        return this;
    }

    public FieldHintBuilder hideInList() {
        this.visibleInList = false;
        return this;
    }

    public FieldHintBuilder hideInForm() {
        this.visibleInForm = false;
        return this;
    }

    public FieldHintBuilder hideInDetail() {
        this.visibleInDetail = false;
        return this;
    }

    public FieldHintBuilder visibleInList(boolean v) {
        this.visibleInList = v;
        return this;
    }

    public FieldHintBuilder visibleInForm(boolean v) {
        this.visibleInForm = v;
        return this;
    }

    public FieldHintBuilder visibleInDetail(boolean v) {
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
    public FieldHintBuilder refSecondary(String targetFieldName) {
        this.refSecondary = targetFieldName;
        return this;
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
    public FieldHintBuilder refFilter(String filter) {
        this.refFilter = filter;
        return this;
    }

    /** Switch to configuring another field on the same entity. */
    public FieldHintBuilder field(String name) {
        return parent.field(name);
    }

    String fieldName() {
        return fieldName;
    }

    FieldHint build() {
        return new FieldHint(
                visibleInList, visibleInForm, visibleInDetail,
                order, group, width, widget, placeholder, format, hint, label, refSecondary, refFilter);
    }
}
