package su.onno.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import static org.springframework.http.MediaType.TEXT_HTML;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RootUiResourceRoutingTest {

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestApplication.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void rootMountedDeepLinkReturnsSpaShell() throws Exception {
        mvc.perform(get("/documents/orders/123").accept(TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(TEXT_HTML));
    }

    @Test
    void rootMountedUiDoesNotFallbackForApiOrAssets() throws Exception {
        mvc.perform(get("/api/does-not-exist").accept(TEXT_HTML))
                .andExpect(status().isNotFound());
        mvc.perform(get("/missing-widget.js").accept(TEXT_HTML))
                .andExpect(status().isNotFound());
    }

    @Test
    void rootMountedUiServesConsumerStaticResources() throws Exception {
        mvc.perform(get("/consumer-static.txt"))
                .andExpect(status().isOk())
                .andExpect(content().string("consumer resource\n"));
    }

    @Configuration
    @EnableWebMvc
    static class TestApplication implements WebMvcConfigurer {

        private final UiAutoConfiguration configuration;

        TestApplication() {
            UiProperties properties = new UiProperties();
            properties.setPath("/");
            this.configuration = new UiAutoConfiguration(properties);
        }

        @Override
        public void addResourceHandlers(ResourceHandlerRegistry registry) {
            configuration.addResourceHandlers(registry);
        }

        @Bean
        SpaIndexController spaIndexController() {
            return configuration.spaIndexController();
        }
    }
}
