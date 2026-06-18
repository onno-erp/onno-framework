package su.onno.print;

public record PrintResult(byte[] content, String contentType, String filename) {
}
