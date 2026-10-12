package su.onno.crm.channels.gmail;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class GmailIncomingAttachmentTest {
    @Test void extractsBinaryAttachmentsAndDecodesUnicodeNames() throws Exception {
        byte[] bytes={0,1,2,3,(byte)255};
        String raw=GmailMail.reply("a@example.test","b@example.test","Files","", "Here is the file",
                List.of(new GmailMail.Outgoing("фото.png","image/png",bytes)),UUID.randomUUID());
        var mail=GmailMail.read(raw);
        assertThat(mail.files()).hasSize(1);
        assertThat(mail.files().getFirst().filename()).isEqualTo("фото.png");
        assertThat(mail.files().getFirst().content()).isEqualTo(bytes);
        assertThat(mail.text()).contains("Here is the file").doesNotContain("=?UTF-8");
    }
    @Test void refusedFileIsSkippedWithANoteWhileTheRestImports() throws Exception {
        String raw=GmailMail.reply("a@example.test","b@example.test","Files","","Two files",
                List.of(new GmailMail.Outgoing("tool.exe","application/x-msdownload",new byte[]{1}),
                        new GmailMail.Outgoing("photo.png","image/png",new byte[]{2})),UUID.randomUUID());
        var mail=GmailMail.read(raw);
        var attachments=org.mockito.Mockito.mock(su.onno.crm.service.CrmAttachments.class);
        org.mockito.Mockito.when(attachments.limit()).thenReturn(10);
        org.mockito.Mockito.when(attachments.ingest(org.mockito.ArgumentMatchers.eq("tool.exe"),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any()))
                .thenThrow(new su.onno.crm.service.CrmAttachments.RejectedAttachment(
                        su.onno.crm.service.CrmAttachments.RejectedAttachment.Reason.TYPE_NOT_ALLOWED,"refused"));
        org.mockito.Mockito.when(attachments.ingest(org.mockito.ArgumentMatchers.eq("photo.png"),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenReturn("/api/media/photo");
        org.mockito.Mockito.when(attachments.store(List.of("/api/media/photo"))).thenReturn("/api/media/photo");
        var bridge=new GmailBridge(org.mockito.Mockito.mock(GmailClient.class),null,
                org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class),null,null,null,null,null,null,null,
                attachments,null);
        var imported=bridge.importFiles(mail);
        assertThat(imported.urls()).isEqualTo("/api/media/photo");
        assertThat(imported.withNotes("Two files")).isEqualTo("Two files\n[File skipped: file type is not allowed]");
    }
}
