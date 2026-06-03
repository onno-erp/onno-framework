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
        boolean secret
) {
}
