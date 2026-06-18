package su.onno.mail;

public record MailAttachment(String filename, String contentType, byte[] content) {
}
