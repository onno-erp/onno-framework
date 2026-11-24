package su.onno.service;

import su.onno.metadata.AttributeDescriptor;
import su.onno.types.PolyRef;
import su.onno.types.Ref;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Compiles a structured, Prisma-like {@code where} predicate (JSON) into a safe SQL fragment with
 * bound parameters. The
 * typed UI client sends {@code where={"posted":true,"total":{"gte":100}}} and the server never sees
 * a predicate as text.
 *
 * <pre>{@code
 * {
 *   "posted": true,                               // equality
 *   "total": { "gte": 100, "lt": 1000 },          // several operators on one field are ANDed
 *   "status": { "in": ["<uuid>", "<uuid>"] },     // refs/enums as UUID strings
 *   "assignedTo": null,                           // IS NULL
 *   "customer": { "not": null },                  // IS NOT NULL
 *   "note": { "contains": "rush" },               // case-insensitive substring
 *   "or": [ { "status": "<uuid>" }, { "open": true } ],
 *   "not": { "status": "<uuid>" }
 * }
 * }</pre>
 *
 * <p>Keys are the entity's logical field names (or the system fields {@code id}, {@code code},
 * {@code description}, {@code number}, {@code date}, {@code posted}, {@code period},
 * {@code deletionMark}, …), resolved through the same field→column resolver the list feed uses and
 * checked against the entity's column allowlist; an unknown field, operator or malformed value is a
 * {@code 400}, never silently dropped. Values are always bound, coerced to the column's SQL type
 * (UUID for refs/enums — an enum also accepts its constant name — {@code LocalDate} /
 * {@code LocalDateTime} for temporals, {@code BigDecimal} for numbers), so PostgreSQL's strict
 * typing holds. Comparisons follow SQL: {@code not}/{@code notIn} on a NULL column is not a
 * match. Nesting is bounded by {@link #MAX_DEPTH}, {@code in} lists by {@link #MAX_IN_VALUES}.</p>
 */
public final class Where {

    /** Deepest allowed nesting of {@code and}/{@code or}/{@code not} (and field-level {@code not}). */
    public static final int MAX_DEPTH = 8;
    /** Most values one {@code in}/{@code notIn} list may carry. */
    public static final int MAX_IN_VALUES = 500;
    /** Most field comparisons one predicate may hold. */
    public static final int MAX_PREDICATES = 200;
    /** Longest accepted JSON text. */
    public static final int MAX_LENGTH = 64 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final Pattern IDENTIFIER = Pattern.compile("^_?[a-z][a-z0-9_]*$");
    private static final String PARAM_PREFIX = "wh";

    private Where() {
    }

    // ---------------------------------------------------------------- schema

    /** The SQL type family of a filterable column, which decides how a value is bound. */
    public enum Type { TEXT, NUMBER, BOOLEAN, DATE, DATETIME, UUID, OTHER }

    /** One filterable column: its storage name, type family and (for an enum column) the enum class. */
    public record Column(String name, Type type, Class<? extends Enum<?>> enumType) {
        public Column(String name, Type type) {
            this(name, type, null);
        }
    }

    /**
     * The columns a {@code where} may reference, keyed by lower-case storage name, plus the resolver
     * from a logical field name to that storage name.
     */
    public record Schema(Map<String, Column> columns, Function<String, String> resolver) {

        /** A schema over {@code attributes} (secret ones excluded) plus the given system columns. */
        public static Schema of(Collection<AttributeDescriptor> attributes, Map<String, Type> systemColumns,
                                Function<String, String> resolver) {
            Map<String, Column> columns = new LinkedHashMap<>();
            systemColumns.forEach((name, type) -> columns.put(name, new Column(name, type)));
            for (AttributeDescriptor a : attributes) {
                if (a.secret()) continue;
                String name = a.columnName().toLowerCase(Locale.ROOT);
                columns.put(name, column(name, a.javaType()));
            }
            return new Schema(Map.copyOf(columns), resolver);
        }

        /** This schema with one more column (e.g. a hierarchical catalog's {@code _parent}). */
        public Schema with(String name, Type type) {
            Map<String, Column> columns = new LinkedHashMap<>(this.columns);
            columns.put(name, new Column(name, type));
            return new Schema(Map.copyOf(columns), resolver);
        }

        @SuppressWarnings("unchecked")
        private static Column column(String name, Class<?> type) {
            if (type == null) return new Column(name, Type.OTHER);
            if (type.isEnum()) return new Column(name, Type.UUID, (Class<? extends Enum<?>>) type);
            if (PolyRef.class.isAssignableFrom(type)) return new Column(name, Type.TEXT);
            if (Ref.class.isAssignableFrom(type) || type == UUID.class) return new Column(name, Type.UUID);
            if (type == String.class) return new Column(name, Type.TEXT);
            if (type == Boolean.class || type == boolean.class) return new Column(name, Type.BOOLEAN);
            if (ListFilter.isNumeric(type)) return new Column(name, Type.NUMBER);
            if (type == LocalDate.class) return new Column(name, Type.DATE);
            if (type == LocalDateTime.class || type == OffsetDateTime.class
                    || type == java.time.Instant.class || type == java.time.ZonedDateTime.class) {
                return new Column(name, Type.DATETIME);
            }
            return new Column(name, Type.OTHER);
        }

        Column resolve(String field) {
            String mapped = resolver == null ? field : resolver.apply(field);
            String column = mapped == null ? null : mapped.toLowerCase(Locale.ROOT);
            Column c = column == null || !IDENTIFIER.matcher(column).matches() ? null : columns.get(column);
            if (c == null) {
                throw invalid("Unknown where field: " + field);
            }
            return c;
        }
    }

    // ---------------------------------------------------------------- predicate tree

    /** A parsed, validated predicate. Values are already coerced to their bind types. */
    public sealed interface Node permits All, AndNode, OrNode, NotNode, Compare, IsNull, InList, Match {
    }

    /** No restriction. */
    public record All() implements Node {
    }

    public record AndNode(List<Node> parts) implements Node {
    }

    public record OrNode(List<Node> parts) implements Node {
    }

    public record NotNode(Node part) implements Node {
    }

    /** Comparison operators; {@code NE} renders as {@code <>}. */
    public enum Op {
        EQ("="), NE("<>"), GT(">"), GTE(">="), LT("<"), LTE("<=");

        private final String sql;

        Op(String sql) {
            this.sql = sql;
        }
    }

    public record Compare(String column, Op op, Object value) implements Node {
    }

    public record IsNull(String column, boolean negated) implements Node {
    }

    /** {@code column IN (values)}; a {@code null} element also admits NULL. Negated for {@code notIn}. */
    public record InList(String column, List<Object> values, boolean negated) implements Node {
    }

    /** Case-insensitive {@code contains} / {@code startsWith} over the column's text form. */
    public record Match(String column, String text, boolean prefix) implements Node {
    }

    // ---------------------------------------------------------------- entry points

    /** Parse and compile {@code json} (blank → no predicate); the fragment has no leading {@code AND}. */
    public static SqlPredicate compile(String json, Schema schema) {
        if (json == null || json.isBlank()) {
            return new SqlPredicate("", Map.of());
        }
        return render(parse(json, schema));
    }

    /** As {@link #compile(String, Schema)} for an already-parsed JSON value (a POST body field). */
    public static SqlPredicate compile(JsonNode json, Schema schema) {
        if (json == null || json.isNull() || json.isMissingNode()) {
            return new SqlPredicate("", Map.of());
        }
        if (json.isTextual()) {
            return compile(json.asText(), schema);
        }
        return render(parse(json, schema));
    }

    /**
     * A JSON-body {@code where} as the JSON text the query services take: an object is serialized,
     * a string (already-encoded JSON) passes through, absent/null is {@code null}.
     */
    public static String text(JsonNode json) {
        if (json == null || json.isNull() || json.isMissingNode()) return null;
        return json.isTextual() ? json.asText() : json.toString();
    }

    /** Parse {@code json} into a validated predicate tree. */
    public static Node parse(String json, Schema schema) {
        if (json.length() > MAX_LENGTH) {
            throw invalid("where is too long (max " + MAX_LENGTH + " characters)");
        }
        JsonNode node;
        try {
            node = JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw invalid("where is not valid JSON: " + e.getOriginalMessage());
        }
        return parse(node, schema);
    }

    /** Parse a JSON value into a validated predicate tree. */
    public static Node parse(JsonNode json, Schema schema) {
        if (json == null || json.isNull() || json.isMissingNode()) {
            return new All();
        }
        return new Parser(schema).clause(json, 1);
    }

    /** Render a predicate tree to SQL with named bindings. */
    public static SqlPredicate render(Node node) {
        if (node instanceof All) {
            return new SqlPredicate("", Map.of());
        }
        Map<String, Object> bindings = new LinkedHashMap<>();
        String sql = new Renderer(bindings).sql(node);
        return new SqlPredicate(sql, bindings);
    }

    /** AND two compiled predicates (either may be empty). */
    public static SqlPredicate and(SqlPredicate a, SqlPredicate b) {
        if (a == null || a.isEmpty()) return b == null ? new SqlPredicate("", Map.of()) : b;
        if (b == null || b.isEmpty()) return a;
        Map<String, Object> merged = new LinkedHashMap<>(a.bindings());
        merged.putAll(b.bindings());
        return new SqlPredicate("(" + a.sql() + ") AND (" + b.sql() + ")", merged);
    }

    private static ServiceException invalid(String message) {
        return new ServiceException(ServiceException.Status.BAD_REQUEST, message);
    }

    // ---------------------------------------------------------------- parsing

    private static final Set<String> OPERATORS = Set.of(
            "eq", "not", "in", "notIn", "gt", "gte", "lt", "lte", "contains", "startsWith");

    private static final class Parser {
        private final Schema schema;
        private int predicates;

        Parser(Schema schema) {
            this.schema = schema;
        }

        /** A where object: field keys and {@code and}/{@code or}/{@code not}, all ANDed. */
        Node clause(JsonNode node, int depth) {
            depth(depth);
            if (!node.isObject()) {
                throw invalid("where must be a JSON object");
            }
            List<Node> parts = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> e = fields.next();
                String key = e.getKey();
                JsonNode value = e.getValue();
                Node part = switch (key) {
                    case "and" -> all(clauses(value, depth, "and", true));
                    case "or" -> new OrNode(clauses(value, depth, "or", false));
                    case "not" -> not(all(clauses(value, depth, "not", true)));
                    default -> field(schema.resolve(key), value, depth);
                };
                if (!(part instanceof All)) {
                    parts.add(part);
                }
            }
            if (parts.isEmpty()) {
                return new All();
            }
            return parts.size() == 1 ? parts.getFirst() : new AndNode(parts);
        }

        /** The conjunction of {@code parts}, dropping no-ops ({@code and: []} restricts nothing). */
        private static Node all(List<Node> parts) {
            List<Node> real = parts.stream().filter(p -> !(p instanceof All)).toList();
            if (real.isEmpty()) return new All();
            return real.size() == 1 ? real.getFirst() : new AndNode(real);
        }

        /** {@code not} of nothing restricts nothing (an empty object is no filter, negated or not). */
        private static Node not(Node part) {
            return part instanceof All ? part : new NotNode(part);
        }

        private List<Node> clauses(JsonNode value, int depth, String combinator, boolean allowObject) {
            if (value.isObject() && allowObject) {
                return List.of(clause(value, depth + 1));
            }
            if (!value.isArray()) {
                throw invalid("where." + combinator + " must be an array of where objects");
            }
            List<Node> out = new ArrayList<>();
            for (JsonNode item : value) {
                out.add(clause(item, depth + 1));
            }
            return out;
        }

        /** One field's predicate: a literal (equality), {@code null}, or an operator object. */
        private Node field(Column column, JsonNode value, int depth) {
            if (value.isNull()) {
                return isNull(column, false);
            }
            if (value.isArray()) {
                throw invalid("where field " + column.name() + ": use { \"in\": [...] } for a list of values");
            }
            if (!value.isObject()) {
                return compare(column, Op.EQ, value);
            }
            depth(depth + 1);
            List<Node> parts = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> ops = value.fields();
            while (ops.hasNext()) {
                Map.Entry<String, JsonNode> e = ops.next();
                String op = e.getKey();
                JsonNode operand = e.getValue();
                if (!OPERATORS.contains(op)) {
                    throw invalid("Unknown where operator '" + op + "' on field " + column.name());
                }
                parts.add(switch (op) {
                    case "eq" -> operand.isNull() ? isNull(column, false) : compare(column, Op.EQ, operand);
                    case "not" -> operand.isNull() ? isNull(column, true)
                            : operand.isObject() ? not(field(column, operand, depth + 1))
                            : compare(column, Op.NE, operand);
                    case "in" -> in(column, operand, false);
                    case "notIn" -> in(column, operand, true);
                    case "gt" -> compare(column, Op.GT, operand);
                    case "gte" -> compare(column, Op.GTE, operand);
                    case "lt" -> compare(column, Op.LT, operand);
                    case "lte" -> compare(column, Op.LTE, operand);
                    case "contains" -> match(column, operand, false);
                    case "startsWith" -> match(column, operand, true);
                    default -> throw new IllegalStateException(op);
                });
            }
            parts.removeIf(p -> p instanceof All);
            if (parts.isEmpty()) {
                return new All();
            }
            return parts.size() == 1 ? parts.getFirst() : new AndNode(parts);
        }

        private Node isNull(Column column, boolean negated) {
            count();
            return new IsNull(column.name(), negated);
        }

        private Node compare(Column column, Op op, JsonNode operand) {
            count();
            if (operand.isNull()) {
                throw invalid("where field " + column.name() + ": " + op.name().toLowerCase(Locale.ROOT)
                        + " needs a value, not null");
            }
            return new Compare(column.name(), op, value(column, operand));
        }

        private Node in(Column column, JsonNode operand, boolean negated) {
            count();
            if (!operand.isArray()) {
                throw invalid("where field " + column.name() + ": " + (negated ? "notIn" : "in")
                        + " needs an array");
            }
            if (operand.size() > MAX_IN_VALUES) {
                throw invalid("where field " + column.name() + ": at most " + MAX_IN_VALUES + " values per list");
            }
            List<Object> values = new ArrayList<>();
            for (JsonNode item : operand) {
                values.add(item.isNull() ? null : value(column, item));
            }
            return new InList(column.name(), values, negated);
        }

        private Node match(Column column, JsonNode operand, boolean prefix) {
            count();
            if (!operand.isValueNode() || operand.isNull()) {
                throw invalid("where field " + column.name() + ": " + (prefix ? "startsWith" : "contains")
                        + " needs a string");
            }
            return new Match(column.name(), operand.asText(), prefix);
        }

        private void count() {
            if (++predicates > MAX_PREDICATES) {
                throw invalid("where has too many conditions (max " + MAX_PREDICATES + ")");
            }
        }

        private static void depth(int depth) {
            if (depth > MAX_DEPTH) {
                throw invalid("where is nested too deeply (max depth " + MAX_DEPTH + ")");
            }
        }
    }

    /** Coerce a JSON scalar to the bind type of {@code column}. */
    private static Object value(Column column, JsonNode node) {
        if (!node.isValueNode()) {
            throw invalid("where field " + column.name() + ": expected a value, got " + node.getNodeType());
        }
        try {
            return switch (column.type()) {
                case UUID -> uuid(column, node);
                case NUMBER -> node.isNumber() ? node.decimalValue() : new BigDecimal(text(column, node).trim());
                case BOOLEAN -> bool(column, node);
                case DATE -> date(text(column, node));
                case DATETIME -> dateTime(text(column, node));
                case TEXT -> node.asText();
                case OTHER -> node.isNumber() ? node.decimalValue() : node.isBoolean() ? node.booleanValue() : node.asText();
            };
        } catch (NumberFormatException | DateTimeParseException e) {
            throw invalid("where field " + column.name() + ": invalid " + column.type().name().toLowerCase(Locale.ROOT)
                    + " value '" + node.asText() + "'");
        }
    }

    private static String text(Column column, JsonNode node) {
        if (!node.isTextual() && !node.isNumber()) {
            throw invalid("where field " + column.name() + ": expected a string, got " + node.getNodeType());
        }
        return node.asText();
    }

    /** A UUID; on an enum column also a constant name, bound as its stored id ({@link EnumValues#filterId}). */
    private static UUID uuid(Column column, JsonNode node) {
        String raw = text(column, node).trim();
        if (column.enumType() != null) {
            return EnumValues.filterId(column.enumType(), raw, "where field " + column.name());
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException notAUuid) {
            throw invalid("where field " + column.name() + ": expected a UUID, got '" + raw + "'");
        }
    }

    private static Boolean bool(Column column, JsonNode node) {
        if (node.isBoolean()) return node.booleanValue();
        String raw = node.asText().trim();
        if (raw.equalsIgnoreCase("true")) return Boolean.TRUE;
        if (raw.equalsIgnoreCase("false")) return Boolean.FALSE;
        throw invalid("where field " + column.name() + ": expected true or false, got '" + raw + "'");
    }

    private static LocalDate date(String raw) {
        String v = raw.trim();
        return v.length() > 10 ? dateTime(v).toLocalDate() : LocalDate.parse(v);
    }

    /** ISO date-time (offset/zone dropped, wall clock kept) or a plain date at midnight. */
    private static LocalDateTime dateTime(String raw) {
        String v = raw.trim();
        if (v.length() <= 10) {
            return LocalDate.parse(v).atStartOfDay();
        }
        try {
            return LocalDateTime.parse(v);
        } catch (DateTimeParseException withOffset) {
            return OffsetDateTime.parse(v).toLocalDateTime();
        }
    }

    // ---------------------------------------------------------------- rendering

    private static final class Renderer {
        private final Map<String, Object> bindings;

        Renderer(Map<String, Object> bindings) {
            this.bindings = bindings;
        }

        String sql(Node node) {
            return switch (node) {
                case All a -> "1=1";
                case AndNode and -> join(and.parts(), " AND ", "1=1");
                case OrNode or -> join(or.parts(), " OR ", "1=0");
                case NotNode not -> "NOT (" + sql(not.part()) + ")";
                case Compare c -> c.column() + " " + c.op().sql + " :" + bind(c.value());
                case IsNull n -> n.column() + (n.negated() ? " IS NOT NULL" : " IS NULL");
                case InList in -> in.negated() ? "NOT (" + in(in) + ")" : in(in);
                case Match m -> "LOWER(CAST(" + m.column() + " AS VARCHAR)) LIKE :"
                        + bind(like(m.text(), m.prefix())) + " ESCAPE '\\'";
            };
        }

        private String join(List<Node> parts, String separator, String empty) {
            if (parts.isEmpty()) return empty;
            if (parts.size() == 1) return sql(parts.getFirst());
            List<String> rendered = new ArrayList<>();
            for (Node part : parts) {
                rendered.add("(" + sql(part) + ")");
            }
            return String.join(separator, rendered);
        }

        private String in(InList in) {
            List<String> params = new ArrayList<>();
            boolean nulls = false;
            for (Object value : in.values()) {
                if (value == null) {
                    nulls = true;
                } else {
                    params.add(":" + bind(value));
                }
            }
            if (params.isEmpty()) {
                return nulls ? in.column() + " IS NULL" : "1=0";
            }
            String list = in.column() + " IN (" + String.join(", ", params) + ")";
            return nulls ? "(" + list + " OR " + in.column() + " IS NULL)" : list;
        }

        private String bind(Object value) {
            String name = PARAM_PREFIX + bindings.size();
            bindings.put(name, value);
            return name;
        }

        private static String like(String text, boolean prefix) {
            String escaped = text.toLowerCase(Locale.ROOT)
                    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            return (prefix ? "" : "%") + escaped + "%";
        }
    }
}
