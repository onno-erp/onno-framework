package su.onno.web.openinlist;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Fixture for the "Open in list" header action (#410). Every entity in the model package gets its
 * default surface; no authoring beans are needed.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class OpenInListTestApp {
}
