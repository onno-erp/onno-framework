package su.onno.crm.channels.gmail;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class GmailReturnPathTest {
    @Test void acceptsTheHostsPageInsteadOfRequiringACrmRoute() {
        assertThat(GmailOAuthController.returnPath("/portal/connections?tab=email"))
                .isEqualTo("/portal/connections?tab=email");
    }
    @Test void refusesExternalOrHeaderInjectionDestinations() {
        for(String path:new String[]{"https://example.com","//example.com","/\\example.com","/settings\r\nLocation: https://example.com"})
            assertThatThrownBy(()->GmailOAuthController.returnPath(path)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
