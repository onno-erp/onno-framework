package su.onno.crm.channels.telegram;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import su.onno.crm.service.CrmAttachments;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
class TelegramAttachmentsTest {
    final ObjectMapper json=new ObjectMapper();
    @Test void downloadsLargestPhotoAndStoresOnlyLocalReference() throws Exception {
        var client=mock(TelegramClient.class);var storage=mock(CrmAttachments.class);
        when(storage.limit()).thenReturn(10);byte[] bytes={1,2,3};when(client.download("large")).thenReturn(bytes);
        when(storage.ingest("photo.jpg","image/jpeg",bytes)).thenReturn("/api/media/photo");
        when(storage.store(List.of("/api/media/photo"))).thenReturn("/api/media/photo");
        var result=TelegramAttachments.read(json.readTree("{\"photo\":[{\"file_id\":\"small\"},{\"file_id\":\"large\"}]}"),client,storage);
        assertThat(result.urls()).isEqualTo("/api/media/photo");assertThat(result.failure()).isEmpty();verify(client,never()).download("small");
    }
    @Test void oversizedFilesDoNotBlockTheConversationOrAttemptDownload() throws Exception {
        var client=mock(TelegramClient.class);var storage=mock(CrmAttachments.class);when(storage.limit()).thenReturn(10);
        var result=TelegramAttachments.read(json.readTree("{\"document\":{\"file_id\":\"big\",\"file_size\":30000000}}"),client,storage);
        assertThat(result.urls()).isNull();assertThat(result.failure()).contains("20 MB");verifyNoInteractions(client);
    }
    @Test void mediaPolicyRefusalsAreReportedByReason() throws Exception {
        var client=mock(TelegramClient.class);var storage=mock(CrmAttachments.class);when(storage.limit()).thenReturn(10);
        when(client.download("doc")).thenReturn(new byte[]{1});
        when(storage.ingest(any(),any(),any())).thenThrow(new CrmAttachments.RejectedAttachment(CrmAttachments.RejectedAttachment.Reason.TYPE_NOT_ALLOWED,"refused"));
        var result=TelegramAttachments.read(json.readTree("{\"document\":{\"file_id\":\"doc\"}}"),client,storage);
        assertThat(result.urls()).isNull();assertThat(result.failure()).isEqualTo(TelegramAttachments.TYPE_NOT_ALLOWED);
    }
}
