package su.onno.print;

public record PrintTemplateDescriptor(
        Class<?> target,
        String name,
        String label,
        String template,
        PrintFormat format,
        int order
) {

    public String resolvedLabel() {
        return label == null || label.isBlank() ? name : label;
    }
}
