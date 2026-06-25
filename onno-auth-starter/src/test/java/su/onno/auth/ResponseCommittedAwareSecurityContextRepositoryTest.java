package su.onno.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.SecurityContextRepository;

import su.onno.auth.OnnoAuthAutoConfiguration.InMemoryAuthConfiguration.ResponseCommittedAwareSecurityContextRepository;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link ResponseCommittedAwareSecurityContextRepository}: the wrapper must skip the
 * delegate's {@code saveContext} when the response is already committed (so the remember-me filter
 * does not try to create a session — and a {@code Set-Cookie} — on the committed {@code /api/events}
 * SSE response), and otherwise delegate unchanged.
 */
class ResponseCommittedAwareSecurityContextRepositoryTest {

    @Test
    void skipsSaveWhenResponseIsCommitted() {
        SecurityContextRepository delegate = mock(SecurityContextRepository.class);
        ResponseCommittedAwareSecurityContextRepository repository =
                new ResponseCommittedAwareSecurityContextRepository(delegate);

        SecurityContext context = mock(SecurityContext.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.isCommitted()).thenReturn(true);

        repository.saveContext(context, request, response);

        verify(delegate, never()).saveContext(context, request, response);
    }

    @Test
    void delegatesSaveWhenResponseIsNotCommitted() {
        SecurityContextRepository delegate = mock(SecurityContextRepository.class);
        ResponseCommittedAwareSecurityContextRepository repository =
                new ResponseCommittedAwareSecurityContextRepository(delegate);

        SecurityContext context = mock(SecurityContext.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.isCommitted()).thenReturn(false);

        repository.saveContext(context, request, response);

        verify(delegate).saveContext(context, request, response);
    }
}
