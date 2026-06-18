package su.onno.numbering;

public interface NumberGenerator {

    String nextNumber(String entityName, int length);

    String nextCode(String entityName, int length);

    default String nextNumber(String entityName, String prefix, int length) {
        return prefix + nextNumber(sequenceName(entityName, prefix), length);
    }

    default String nextCode(String entityName, String prefix, int length) {
        return prefix + nextCode(sequenceName(entityName, prefix), length);
    }

    private static String sequenceName(String entityName, String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return entityName;
        }
        return entityName + ":" + prefix;
    }
}
