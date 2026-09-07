package su.onno.ui.tags;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import su.onno.ui.*;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TagControllerTest {
    @Test void deniesReadsAndWritesWithoutEntityPermissions() {
        var service=mock(TagService.class); var access=mock(UiAccessService.class);
        var controller=new TagController(service,access,mock(CatalogQueryService.class),mock(DocumentQueryService.class),List.of(),mock(org.springframework.context.ApplicationEventPublisher.class));
        assertThatThrownBy(() -> controller.library("catalogs","Customers",null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.assign("catalogs","Customers",UUID.randomUUID(),UUID.randomUUID(),null)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(service);
    }
}
