package su.onno.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.TEXT_HTML;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UiResourceRoutingTest {

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
    void uiDeepLinkReturnsSpaShell() throws Exception {
        mvc.perform(get("/ui/documents/orders/123").accept(TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(TEXT_HTML));
    }

    @Test
    void exactUiMountReturnsSpaShell() throws Exception {
        mvc.perform(get("/ui").accept(TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(TEXT_HTML));
        mvc.perform(get("/ui/").accept(TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(TEXT_HTML));
    }

    @Test
    void uiPathWithoutHtmlAcceptDoesNotReturnSpaShell() throws Exception {
        mvc.perform(get("/ui/documents/orders/123"))
                .andExpect(status().isNotFound());
    }

    @Test
    void nonGetNavigationDoesNotReturnSpaShell() throws Exception {
        mvc.perform(post("/ui/documents/orders/123").accept(TEXT_HTML))
                .andExpect(status().isNotFound());
    }

    @Test
    void missingAssetReturnsNotFound() throws Exception {
        mvc.perform(get("/ui/missing-widget.js"))
                .andExpect(status().isNotFound());
    }

    @Test
    void unknownApiRouteReturnsNotFound() throws Exception {
        mvc.perform(get("/api/does-not-exist").accept(TEXT_HTML))
                .andExpect(status().isNotFound());
    }

    @Test
    void bundledRootAssetStillLoads() throws Exception {
        mvc.perform(get("/manifest.webmanifest"))
                .andExpect(status().isOk());
    }

    @Test
    void bundledViteAssetLoads() throws Exception {
        mvc.perform(get("/assets/routing-test.css"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/css"))
                .andExpect(content().string(".routing-test { color: green; }\n"));
    }

    @Test
    void packagedWidgetPluginLoadsFromTheUiMount() throws Exception {
        mvc.perform(get("/ui/plugins/TestWidget.js"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/javascript"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("registerWidget")));
    }

    @Test
    void consumerStaticResourceIsNotShadowed() throws Exception {
        mvc.perform(get("/consumer-static.txt"))
                .andExpect(status().isOk())
                .andExpect(content().string("consumer resource\n"));
    }

    @Test
    void resourceHandlerIsScopedToUiPath() {
        ResourceHandlerRegistry registry = new ResourceHandlerRegistry(context, context.getServletContext());

        new UiAutoConfiguration(new UiProperties()).addResourceHandlers(registry);

        assertThat(registry.hasMappingForPattern("/ui")).isTrue();
        assertThat(registry.hasMappingForPattern("/ui/**")).isTrue();
        assertThat(registry.hasMappingForPattern("/**")).isFalse();
    }

    @Configuration
    @EnableWebMvc
    static class TestApplication implements WebMvcConfigurer {

        private final UiRouting routing = new UiRouting(new UiProperties());

        @Override
        public void addResourceHandlers(ResourceHandlerRegistry registry) {
            routing.configuration.addResourceHandlers(registry);
            registry.addResourceHandler("/**")
                    .addResourceLocations("classpath:/META-INF/resources/", "classpath:/resources/",
                            "classpath:/static/", "classpath:/public/");
        }

        @Bean
        SpaIndexController spaIndexController() {
            return routing.configuration.spaIndexController();
        }
    }

    static final class UiRouting {
        private final UiAutoConfiguration configuration;

        private UiRouting(UiProperties properties) {
            this.configuration = new UiAutoConfiguration(properties);
        }
    }
}
