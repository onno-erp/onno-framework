package su.onno.print;

public enum PrintFormat {
    HTML("text/html"),
    PDF("application/pdf");

    private final String contentType;

    PrintFormat(String contentType) {
        this.contentType = contentType;
    }

    public String contentType() {
        return contentType;
    }
}
