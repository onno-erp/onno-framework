package su.onno.ui.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import su.onno.migration.MigrationContext;
import su.onno.repository.EnumerationPersistence;
import su.onno.ui.media.MediaStorage;
import su.onno.ui.media.StoredMedia;

import org.jdbi.v3.core.statement.PreparedBatch;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Currency;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One-time conversion support for applications moving legacy UI values and authored metadata to
 * the 2.0 contracts.
 *
 * <p>Create this inside an {@code AppMigration} from its {@link MigrationContext}. Column helpers
 * scan non-null values and apply changed rows in prepared batches inside the migration's existing
 * transaction. Identifiers are strictly validated before being interpolated into SQL. A malformed
 * or ambiguous legacy value aborts the migration with its table/column/row id in the error rather
 * than leaving a partially guessed conversion.</p>
 *
 * <pre>{@code
 * public void migrate(MigrationContext context) throws Exception {
 *     var tool = new Ui2MigrationTool(context, mediaStorage);
 *     tool.migrateEnumNames("catalog_orders", "_id", "status", OrderStatus.class);
 *     tool.migrateDataUrlImages("catalog_people", "_id", "avatar_url");
 *     tool.migrateGeoPoints("catalog_sites", "_id", "location");
 * }
 * }</pre>
 *
 * <p>The source-only helpers are deliberately explicit. In particular, a bare
 * {@code "currency"} has no safe universal replacement, so callers must supply their ISO 4217
 * currency. They can use these helpers from a small source/config codemod or to verify replacements
 * before upgrading.</p>
 */
public final class Ui2MigrationTool {

    private static final int DEFAULT_BATCH_SIZE = 250;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern DATA_URL = Pattern.compile(
            "^data:([^;,]+)(?:;charset=[^;,]+)?;base64,(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern LEGACY_POINT = Pattern.compile(
            "^\\s*([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))\\s*,\\s*"
                    + "([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))\\s*$");
    private static final Set<String> GEOJSON_TYPES = Set.of(
            "FeatureCollection", "Feature", "Point", "MultiPoint", "LineString",
            "MultiLineString", "Polygon", "MultiPolygon", "GeometryCollection");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Map<String, String> ICON_NAMES;

    static {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("home", "house");
        names.put("bar-chart", "chart-column");
        names.put("bar-chart-2", "chart-column");
        names.put("bar-chart-3", "chart-column");
        names.put("bar-chart-4", "chart-column");
        names.put("bar-chart-horizontal", "chart-bar");
        names.put("pie-chart", "chart-pie");
        names.put("line-chart", "chart-line");
        names.put("area-chart", "chart-area");
        names.put("scatter-chart", "chart-scatter");
        names.put("candlestick-chart", "chart-candlestick");
        names.put("gantt-chart", "chart-gantt");
        ICON_NAMES = Map.copyOf(names);
    }

    private final MigrationContext context;
    private final MediaStorage mediaStorage;
    private final int batchSize;

    /**
     * A tool with media migration enabled. The supplied storage is the application's configured
     * {@link MediaStorage}, so converted data URLs land in the same backend as new uploads.
     */
    public Ui2MigrationTool(MigrationContext context, MediaStorage mediaStorage) {
        this(context, mediaStorage, DEFAULT_BATCH_SIZE);
    }

    /**
     * A tool for enum/geo/source-metadata migrations only. Calling an image conversion fails fast
     * with an explanation that a {@link MediaStorage} must be supplied.
     */
    public Ui2MigrationTool(MigrationContext context) {
        this(context, null, DEFAULT_BATCH_SIZE);
    }

    /** Constructor with an explicit update batch size, primarily for large application tables. */
    public Ui2MigrationTool(MigrationContext context, MediaStorage mediaStorage, int batchSize) {
        this.context = Objects.requireNonNull(context, "context");
        this.mediaStorage = mediaStorage;
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.batchSize = batchSize;
    }

    /**
     * Replace legacy Java enum constant names with their deterministic stored UUIDs.
     * Already-canonical UUID values are left unchanged; unknown names abort the migration.
     */
    public <E extends Enum<E>> MigrationResult migrateEnumNames(
            String table, String idColumn, String valueColumn, Class<E> enumType) throws Exception {
        Objects.requireNonNull(enumType, "enumType");
        return migrateColumn(table, idColumn, valueColumn, value -> {
            if (value instanceof UUID) {
                return value;
            }
            String raw = value.toString().trim();
            try {
                UUID.fromString(raw);
                return value;
            } catch (IllegalArgumentException notUuid) {
                // A column containing names is necessarily textual. Bind the replacement as text
                // too so PostgreSQL does not reject assigning a UUID-typed parameter to varchar.
                return canonicalEnumId(enumType, raw).toString();
            }
        });
    }

    /**
     * Decode every legacy base64 {@code data:image/...} URL in an image/gallery column, store it
     * through {@link MediaStorage}, and replace it with the returned URL. Newline-joined galleries
     * are converted item by item; already-stored URLs are unchanged.
     */
    public MigrationResult migrateDataUrlImages(
            String table, String idColumn, String valueColumn) throws Exception {
        requireMediaStorage();
        return migrateColumn(table, idColumn, valueColumn,
                value -> canonicalMediaValue(value.toString()));
    }

    /**
     * Replace a legacy {@code "lat,lng"} point string with a GeoJSON FeatureCollection in the same
     * column. Existing JSON is unchanged. Afterward, change authored metadata from
     * {@code widget("map")} / {@code map().field(...)} to {@code widget("geojson")} /
     * {@code map().geoJson(...)}.
     */
    public MigrationResult migrateGeoPoints(
            String table, String idColumn, String valueColumn) throws Exception {
        return migrateColumn(table, idColumn, valueColumn,
                value -> canonicalGeoJson(value.toString()));
    }

    /**
     * Convert one legacy enum constant name to its deterministic UUID.
     */
    public static <E extends Enum<E>> UUID canonicalEnumId(Class<E> enumType, String constantName) {
        Objects.requireNonNull(enumType, "enumType");
        if (constantName == null || constantName.isBlank()) {
            throw new IllegalArgumentException("enum constant name must not be blank");
        }
        final E value;
        try {
            value = Enum.valueOf(enumType, constantName);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(
                    "Unknown " + enumType.getName() + " constant '" + constantName + "'", unknown);
        }
        return EnumerationPersistence.resolveId(enumType, value);
    }

    /**
     * Store one legacy data-URL value (or every item of a newline-joined gallery) and return
     * canonical stored-media URL(s). Values that contain no data URL pass through unchanged.
     */
    public String canonicalMediaValue(String value) throws IOException {
        requireMediaStorage();
        if (value == null || value.isBlank()) {
            return value;
        }
        String[] items = value.split("\\R", -1);
        List<String> migrated = new ArrayList<>(items.length);
        boolean changed = false;
        for (String item : items) {
            if (!item.regionMatches(true, 0, "data:", 0, 5)) {
                migrated.add(item);
                continue;
            }
            migrated.add(storeDataUrl(item));
            changed = true;
        }
        return changed ? String.join("\n", migrated) : value;
    }

    /**
     * Convert a legacy point string to canonical GeoJSON. An existing object with a recognized
     * GeoJSON {@code type} passes through unchanged; malformed JSON/coordinates and out-of-range
     * points fail fast.
     */
    public static String canonicalGeoJson(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("{")) {
            try {
                JsonNode json = JSON.readTree(trimmed);
                if (json.isObject() && GEOJSON_TYPES.contains(json.path("type").asText())) {
                    return value;
                }
            } catch (IOException invalidJson) {
                throw new IllegalArgumentException("Existing geometry is malformed JSON", invalidJson);
            }
            throw new IllegalArgumentException("Existing geometry is not recognizable GeoJSON");
        }
        Matcher point = LEGACY_POINT.matcher(trimmed);
        if (!point.matches()) {
            throw new IllegalArgumentException(
                    "Expected legacy point as 'lat,lng' or existing GeoJSON, got '" + value + "'");
        }
        double lat = Double.parseDouble(point.group(1));
        double lng = Double.parseDouble(point.group(2));
        if (!Double.isFinite(lat) || lat < -90 || lat > 90
                || !Double.isFinite(lng) || lng < -180 || lng > 180) {
            throw new IllegalArgumentException("Point is outside latitude/longitude bounds: " + value);
        }
        String latitude = decimal(lat);
        String longitude = decimal(lng);
        return "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\","
                + "\"properties\":{},\"geometry\":{\"type\":\"Point\",\"coordinates\":["
                + longitude + "," + latitude + "]}}]}";
    }

    /**
     * Make a field-format hint explicit. A bare {@code currency} is rewritten with the caller's
     * ISO 4217 code; an existing {@code currency:xxx} is validated and normalized to uppercase.
     */
    public static String canonicalCurrencyFormat(String format, String defaultCurrency) {
        if (format == null) {
            return null;
        }
        String trimmed = format.trim();
        if (!trimmed.equalsIgnoreCase("currency")
                && !trimmed.toLowerCase(Locale.ROOT).startsWith("currency:")) {
            return format;
        }
        String code = trimmed.equalsIgnoreCase("currency")
                ? defaultCurrency
                : trimmed.substring(trimmed.indexOf(':') + 1);
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException(
                    "A bare currency format requires an explicit ISO 4217 currency");
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        Currency.getInstance(normalized);
        return "currency:" + normalized;
    }

    /** Return the current Lucide name for a formerly accepted alias; current names pass through. */
    public static String canonicalIcon(String icon) {
        if (icon == null) {
            return null;
        }
        return ICON_NAMES.getOrDefault(icon, icon);
    }

    /** Rewrite a legacy point-widget name to the canonical GeoJSON editor name. */
    public static String canonicalWidget(String widget) {
        if (widget == null) {
            return null;
        }
        return switch (widget.toLowerCase(Locale.ROOT)) {
            case "map", "geo", "geolocation" -> "geojson";
            case "photo" -> "image";
            case "photos" -> "images";
            default -> widget;
        };
    }

    /** Rewrite the old dashboard/list map config key to the canonical GeoJSON key. */
    public static String canonicalMapConfigKey(String key) {
        return key != null && key.equalsIgnoreCase("geoField") ? "geoJsonField" : key;
    }

    private MigrationResult migrateColumn(
            String table, String idColumn, String valueColumn, CheckedMigration migration)
            throws Exception {
        String safeTable = tableIdentifier(table);
        String safeId = identifier(idColumn, "idColumn");
        String safeValue = identifier(valueColumn, "valueColumn");

        List<Row> rows = context.handle().createQuery(
                        "SELECT " + safeId + ", " + safeValue + " FROM " + safeTable
                                + " WHERE " + safeValue + " IS NOT NULL")
                .map((rs, ignored) -> new Row(rs.getObject(1), rs.getObject(2)))
                .list();

        List<Change> changes = new ArrayList<>();
        for (Row row : rows) {
            Object migrated;
            try {
                migrated = migration.apply(row.value());
            } catch (Exception failure) {
                throw new IllegalArgumentException(
                        "Failed to migrate " + safeTable + "." + safeValue
                                + " for " + safeId + "=" + row.id() + ": " + failure.getMessage(),
                        failure);
            }
            if (!Objects.equals(row.value(), migrated)) {
                changes.add(new Change(row.id(), migrated));
            }
        }

        String updateSql = "UPDATE " + safeTable + " SET " + safeValue + " = :value WHERE "
                + safeId + " = :id";
        for (int start = 0; start < changes.size(); start += batchSize) {
            PreparedBatch batch = context.handle().prepareBatch(updateSql);
            int end = Math.min(start + batchSize, changes.size());
            for (int i = start; i < end; i++) {
                Change change = changes.get(i);
                batch.bind("value", change.value()).bind("id", change.id()).add();
            }
            batch.execute();
        }
        return new MigrationResult(rows.size(), changes.size());
    }

    private String storeDataUrl(String dataUrl) throws IOException {
        Matcher match = DATA_URL.matcher(dataUrl);
        if (!match.matches()) {
            throw new IllegalArgumentException(
                    "Only base64 data URLs are migratable; expected data:<type>;base64,<bytes>");
        }
        String contentType = match.group(1).trim().toLowerCase(Locale.ROOT);
        if (!contentType.startsWith("image/")) {
            throw new IllegalArgumentException("Expected an image data URL, got " + contentType);
        }
        final byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(match.group(2));
        } catch (IllegalArgumentException invalidBase64) {
            throw new IllegalArgumentException("Invalid base64 image data", invalidBase64);
        }
        StoredMedia stored;
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
            stored = mediaStorage.store(input, "legacy-image", contentType, bytes.length);
        }
        if (stored == null || stored.url() == null || stored.url().isBlank()) {
            throw new IllegalStateException("MediaStorage returned no canonical URL");
        }
        return stored.url();
    }

    private void requireMediaStorage() {
        if (mediaStorage == null) {
            throw new IllegalStateException(
                    "Data-URL image migration requires the application's MediaStorage");
        }
    }

    private static String tableIdentifier(String table) {
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("table must not be blank");
        }
        String[] parts = table.split("\\.", -1);
        for (String part : parts) {
            identifier(part, "table");
        }
        return String.join(".", parts);
    }

    private static String identifier(String value, String label) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " is not a safe SQL identifier: " + value);
        }
        return value;
    }

    private static String decimal(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /** Counts from a completed table-column conversion. */
    public record MigrationResult(int scanned, int migrated) {
    }

    private record Row(Object id, Object value) {
    }

    private record Change(Object id, Object value) {
    }

    @FunctionalInterface
    private interface CheckedMigration {
        Object apply(Object value) throws Exception;
    }
}
