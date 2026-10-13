package su.onno.crm.channels.gmail;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import java.io.ByteArrayOutputStream;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class GmailHtmlBodyTest {
    @Test void sanitizeKeepsLayoutAndDropsActiveContent() {
        String html=EmailHtml.sanitize("<html><head><style>p{color:red}</style><script>alert(1)</script></head><body>"
                +"<table width=\"600\" cellpadding=\"4\"><tr><td bgcolor=\"#eee\" style=\"font-weight:bold\">Hi <b>there</b></td></tr></table>"
                +"<a href=\"javascript:alert(1)\">bad</a><a href=\"https://example.test/x\" onclick=\"steal()\">good</a>"
                +"<img src=\"cid:logo@mail\" onerror=\"x()\"><img src=\"data:text/html;base64,PHNjcmlwdD4=\"><iframe src=\"https://evil.test\"></iframe>"
                +"<form action=\"https://evil.test\"><input name=\"p\"></form></body></html>");
        assertThat(html).contains("<table","width=\"600\"","cellpadding=\"4\"","bgcolor=\"#eee\"","style=\"font-weight:bold\"","<b>there</b>",
                "href=\"https://example.test/x\"","target=\"_blank\"","rel=\"noopener noreferrer\"","src=\"cid:logo@mail\"");
        assertThat(html).doesNotContain("script","alert","onclick","onerror","javascript:","iframe","<form","<input","data:text/html","<style");
    }
    @Test void emptyOrOversizedHtmlIsNotKept() {
        assertThat(EmailHtml.sanitize("  ")).isNull();
        assertThat(EmailHtml.sanitize("<script>only()</script>")).isNull();
        assertThat(EmailHtml.sanitize("<p>"+"x".repeat(EmailHtml.MAX_LENGTH)+"</p>")).isNull();
    }
    @Test void htmlOnlyMailGetsReadableTextFallback() {
        assertThat(EmailHtml.toText("<html><body>\n  <p>Hello&nbsp;Anna,</p>\n<div>Line one<br>Line two</div><style>x{}</style></body></html>"))
                .isEqualTo("Hello Anna,\nLine one\nLine two");
    }
    @Test void readsAlternativeHtmlAndPointsInlineImagesAtStoredFiles() throws Exception {
        var message=new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(new InternetAddress("anna@example.test"));message.setRecipient(Message.RecipientType.TO,new InternetAddress("studio@example.test"));
        message.setSubject("Moodboard");
        var alternative=new MimeMultipart("alternative");
        var text=new MimeBodyPart();text.setText("Plain version","UTF-8");alternative.addBodyPart(text);
        var html=new MimeBodyPart();html.setContent("<p>Rich <i>version</i></p><img src=\"cid:logo1\">","text/html; charset=UTF-8");alternative.addBodyPart(html);
        var wrapper=new MimeBodyPart();wrapper.setContent(alternative);
        var related=new MimeMultipart("related");related.addBodyPart(wrapper);
        var image=new MimeBodyPart();
        image.setDataHandler(new jakarta.activation.DataHandler(new jakarta.mail.util.ByteArrayDataSource(new byte[]{1,2,3},"image/png")));
        image.setHeader("Content-ID","<logo1>");image.setDisposition(Part.INLINE);related.addBodyPart(image);
        message.setContent(related);message.saveChanges();
        var out=new ByteArrayOutputStream();message.writeTo(out);
        var mail=GmailMail.read(Base64.getUrlEncoder().encodeToString(out.toByteArray()));
        assertThat(mail.text()).isEqualTo("Plain version");
        assertThat(mail.html()).contains("<i>version</i>","src=\"cid:logo1\"");
        assertThat(mail.files()).singleElement().satisfies(f->assertThat(f.contentId()).isEqualTo("logo1"));
        var imported=new GmailBridge.ImportedFiles("/api/media/a",List.of(),Map.of("logo1","/api/media/a"));
        assertThat(imported.html(mail)).contains("src=\"/api/media/a\"").doesNotContain("cid:");
    }
}
