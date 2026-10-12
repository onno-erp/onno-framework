package su.onno.crm.service;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import su.onno.ui.media.LoadedMedia;
import su.onno.ui.media.MediaProperties;
import su.onno.ui.media.MediaStorage;
import su.onno.ui.media.StoredMedia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Inbound files from external senders obey the operator's media policy, like uploads do. */
class CrmAttachmentsIngestTest {
    private static final MediaStorage STORAGE = new MediaStorage() {
        @Override public StoredMedia store(InputStream content, String filename, String contentType, long size) {
            return new StoredMedia("key/" + filename, "/api/media/key/" + filename, contentType, size, filename);
        }
        @Override public Optional<LoadedMedia> load(String key) { return Optional.empty(); }
    };

    private static CrmAttachments attachments(long maxBytes, List<String> allowed) {
        var properties = new MediaProperties();
        properties.setMaxFileSize(DataSize.ofBytes(maxBytes));
        properties.setAllowedContentTypes(allowed);
        return new CrmAttachments(STORAGE, properties);
    }

    @Test void refusesFilesOverTheConfiguredSize() {
        var attachments = attachments(4, List.of());
        assertThat(attachments.ingest("a.txt", "text/plain", new byte[4])).isEqualTo("/api/media/key/a.txt");
        assertThatThrownBy(() -> attachments.ingest("a.txt", "text/plain", new byte[5]))
                .isInstanceOfSatisfying(CrmAttachments.RejectedAttachment.class,
                        e -> assertThat(e.reason()).isEqualTo(CrmAttachments.RejectedAttachment.Reason.TOO_LARGE));
        assertThatThrownBy(() -> attachments.ingest("a.txt", "text/plain", new byte[0]))
                .isInstanceOfSatisfying(CrmAttachments.RejectedAttachment.class,
                        e -> assertThat(e.reason()).isEqualTo(CrmAttachments.RejectedAttachment.Reason.EMPTY));
    }

    @Test void refusesTypesOutsideTheAllowList() {
        var attachments = attachments(1024, List.of("image/*", "application/pdf"));
        assertThat(attachments.ingest("p.png", "IMAGE/PNG; charset=binary", new byte[1])).endsWith("p.png");
        assertThat(attachments.ingest("d.pdf", "application/pdf", new byte[1])).endsWith("d.pdf");
        assertThatThrownBy(() -> attachments.ingest("x.exe", "application/x-msdownload", new byte[1]))
                .isInstanceOfSatisfying(CrmAttachments.RejectedAttachment.class,
                        e -> assertThat(e.reason()).isEqualTo(CrmAttachments.RejectedAttachment.Reason.TYPE_NOT_ALLOWED))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
