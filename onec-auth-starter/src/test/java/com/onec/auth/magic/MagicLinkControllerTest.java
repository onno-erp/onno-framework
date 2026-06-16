package com.onec.auth.magic;

import com.onec.auth.OnecAuthProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MagicLinkControllerTest {

    private MagicLinkService service;
    private OnecAuthProperties properties;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(MagicLinkService.class);
        properties = new OnecAuthProperties();
        mvc = MockMvcBuilders.standaloneSetup(new MagicLinkController(service, properties)).build();
    }

    @Test
    void requestAlwaysAcceptedAndDelegatesEmail() throws Exception {
        mvc.perform(post("/api/auth/magic/request")
                        .contentType(APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.com\"}"))
                .andExpect(status().isAccepted());

        verify(service).requestLink(eq("alice@example.com"), anyString());
    }

    @Test
    void requestWithBlankEmailIsStillAcceptedAndDoesNotDelegate() throws Exception {
        mvc.perform(post("/api/auth/magic/request")
                        .contentType(APPLICATION_JSON)
                        .content("{\"email\":\"\"}"))
                .andExpect(status().isAccepted());

        verifyNoInteractions(service);
    }

    @Test
    void verifyWithInvalidTokenRedirectsToLoginError() throws Exception {
        when(service.verify("bad")).thenReturn(Optional.empty());

        mvc.perform(get("/api/auth/magic/verify").param("token", "bad"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login?error=link"));
    }

    @Test
    void verifyWithValidTokenEstablishesSessionAndRedirectsHome() throws Exception {
        Authentication authentication =
                UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of());
        when(service.verify("good")).thenReturn(Optional.of(authentication));

        mvc.perform(get("/api/auth/magic/verify").param("token", "good"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void verifyHonoursConfiguredRedirectPath() throws Exception {
        properties.getMagicLink().setRedirectPath("/dashboard");
        Authentication authentication =
                UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of());
        when(service.verify("good")).thenReturn(Optional.of(authentication));

        mvc.perform(get("/api/auth/magic/verify").param("token", "good"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/dashboard"));
    }
}
