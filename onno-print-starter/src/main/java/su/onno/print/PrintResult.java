package su.onno.print;

/**
 * A filled print form.
 *
 * @param content     the file bytes
 * @param contentType the file's media type
 * @param fileName    download file name, extension included
 */
public record PrintResult(byte[] content, String contentType, String fileName) {
}
