package com.onec.metadata;

public record AttributeDescriptor(
        String fieldName,
        String displayName,
        String columnName,
        Class<?> javaType,
        int length,
        boolean required,
        boolean isRef,
        String refTarget,
        int precision,
        int scale,
        boolean visibleInList,
        boolean visibleInForm,
        boolean visibleInDetail,
        int order,
        String group,
        String widthHint,
        String widget,
        Constraints constraints,
        boolean secret
) {

    /**
     * Declarative validation bounds for an attribute, from {@code @Attribute}. {@code min}/{@code max}
     * are {@code NaN} when unset; {@code minLength} is {@code 0} when unset; {@code pattern} blank when
     * unset. The maximum string length is the descriptor's {@link #length()}.
     */
    public record Constraints(double min, double max, int minLength, String pattern, boolean email) {
        public static final Constraints NONE = new Constraints(Double.NaN, Double.NaN, 0, "", false);

        public boolean hasMin() { return !Double.isNaN(min); }
        public boolean hasMax() { return !Double.isNaN(max); }
    }
}
