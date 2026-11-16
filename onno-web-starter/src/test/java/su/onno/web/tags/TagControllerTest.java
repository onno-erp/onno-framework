package su.onno.web.tags;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import su.onno.web.*;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TagControllerTest {
    @Test void deniesReadsAndWritesWithoutEntityPermissions() {
        var service=mock(TagService.class); var access=mock(AccessService.class);
        var controller=new TagController(service,access,mock(CatalogQueryService.class),mock(DocumentQueryService.class),List.of(),null);
        assertThatThrownBy(() -> controller.library("catalogs","Customers",null,su.onno.access.AccessSubject.user(null,java.util.Set.of()))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.assign("catalogs","Customers",UUID.randomUUID(),UUID.randomUUID(),null,su.onno.access.AccessSubject.user(null,java.util.Set.of()))).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(service);
    }
}
