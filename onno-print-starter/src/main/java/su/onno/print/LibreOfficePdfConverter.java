package su.onno.print;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Converts with a local LibreOffice ({@code soffice --headless --convert-to pdf}). Each conversion
 * runs in its own temporary directory and user profile, so concurrent conversions do not contend
 * for LibreOffice's single-instance profile lock.
 */
public class LibreOfficePdfConverter implements PdfConverter {

    private final String command;
    private final Duration timeout;

    /**
     * @param command the {@code soffice} executable (a name on the PATH or an absolute path)
     * @param timeout how long one conversion may take
     */
    public LibreOfficePdfConverter(String command, Duration timeout) {
        this.command = command;
        this.timeout = timeout;
    }

    @Override
    public byte[] toPdf(byte[] document, String format) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("onno-print-");
            Path input = dir.resolve("document." + format);
            Files.write(input, document);
            Process process = new ProcessBuilder(List.of(command, "--headless", "--norestore",
                    "-env:UserInstallation=" + dir.resolve("profile").toUri(),
                    "--convert-to", "pdf", "--outdir", dir.toString(), input.toString()))
                    .redirectErrorStream(true)
                    .redirectOutput(dir.resolve("soffice.log").toFile())
                    .start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("LibreOffice conversion timed out after " + timeout);
            }
            Path pdf = dir.resolve("document.pdf");
            if (process.exitValue() != 0 || !Files.exists(pdf)) {
                throw new IllegalStateException("LibreOffice conversion failed (exit " + process.exitValue() + "): "
                        + Files.readString(dir.resolve("soffice.log")).strip());
            }
            return Files.readAllBytes(pdf);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not run LibreOffice (" + command + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while converting to PDF", e);
        } finally {
            if (dir != null) deleteQuietly(dir);
        }
    }

    private static void deleteQuietly(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // best effort: the OS cleans the temp directory eventually
        }
    }
}
