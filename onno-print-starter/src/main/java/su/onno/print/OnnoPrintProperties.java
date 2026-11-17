package su.onno.print;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Settings of {@code onno-print-starter}. */
@ConfigurationProperties(prefix = "onno.print")
public class OnnoPrintProperties {

    /**
     * Master switch for print forms. When false, no PrintService, print endpoints or record "Print"
     * actions are registered, even if PrintForm beans exist.
     */
    private boolean enabled = true;

    private final Templates templates = new Templates();
    private final Batch batch = new Batch();
    private final Pdf pdf = new Pdf();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Templates getTemplates() {
        return templates;
    }

    public Batch getBatch() {
        return batch;
    }

    public Pdf getPdf() {
        return pdf;
    }

    /** Admin-uploaded template overrides. */
    public static class Templates {

        /**
         * Whether admins may upload replacement templates (stored in the onno_print_templates
         * table) that take precedence over the built-in ones.
         */
        private boolean enabled = true;

        /** Roles allowed to list, download, upload and reset print templates. ADMIN always passes. */
        private List<String> manageRoles = new ArrayList<>(List.of("ADMIN"));

        /** Largest accepted uploaded template. */
        private DataSize maxSize = DataSize.ofMegabytes(10);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getManageRoles() {
            return manageRoles;
        }

        public void setManageRoles(List<String> manageRoles) {
            this.manageRoles = manageRoles;
        }

        public DataSize getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(DataSize maxSize) {
            this.maxSize = maxSize;
        }
    }

    /** Printing several selected records at once into a zip archive. */
    public static class Batch {

        /** Most records one batch print request may include; larger selections are rejected. */
        private int maxRecords = 200;

        public int getMaxRecords() {
            return maxRecords;
        }

        public void setMaxRecords(int maxRecords) {
            this.maxRecords = maxRecords;
        }
    }

    /** PDF output. Configure one converter; an application PdfConverter bean replaces both. */
    public static class Pdf {

        private final Gotenberg gotenberg = new Gotenberg();
        private final LibreOffice libreoffice = new LibreOffice();

        public Gotenberg getGotenberg() {
            return gotenberg;
        }

        public LibreOffice getLibreoffice() {
            return libreoffice;
        }
    }

    /** Converts through a Gotenberg service. */
    public static class Gotenberg {

        /** Base URL of a Gotenberg service (for a local one, port 3000 on localhost). Setting it enables PDF output. */
        private URI url;

        /** Timeout of one Gotenberg conversion request. */
        private Duration timeout = Duration.ofSeconds(60);

        public URI getUrl() {
            return url;
        }

        public void setUrl(URI url) {
            this.url = url;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }

    /** Converts with a local LibreOffice installation. */
    public static class LibreOffice {

        /**
         * The soffice executable (a name on the PATH or an absolute path, e.g.
         * /Applications/LibreOffice.app/Contents/MacOS/soffice). Setting it enables PDF output.
         */
        private String command;

        /** Timeout of one LibreOffice conversion. */
        private Duration timeout = Duration.ofSeconds(120);

        public String getCommand() {
            return command;
        }

        public void setCommand(String command) {
            this.command = command;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }
}
