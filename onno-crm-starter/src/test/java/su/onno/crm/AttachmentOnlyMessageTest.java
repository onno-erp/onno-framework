package su.onno.crm;
import org.junit.jupiter.api.Test;
import su.onno.annotations.Attribute;
import su.onno.crm.domain.ConversationMessage;
import static org.assertj.core.api.Assertions.*;
class AttachmentOnlyMessageTest {
    @Test void metadataMustAllowFileOnlyMessagesToReachConditionalValidation() throws Exception {
        assertThat(ConversationMessage.class.getDeclaredField("body").getAnnotation(Attribute.class).required()).isFalse();
    }
}
