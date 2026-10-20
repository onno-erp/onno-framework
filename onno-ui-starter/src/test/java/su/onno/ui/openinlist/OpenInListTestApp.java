package su.onno.ui.openinlist;

import su.onno.ui.EntityConfigBuilder;
import su.onno.ui.EntityView;
import su.onno.ui.Page;
import su.onno.ui.PageBuilder;
import su.onno.ui.openinlist.model.OlEvent;
import su.onno.ui.openinlist.model.OlNote;
import su.onno.ui.openinlist.model.OlPaged;
import su.onno.ui.openinlist.model.OlPinned;
import su.onno.ui.openinlist.model.OlTask;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;

/** Fixture for the "Open in list" header action (#410): one entity per placement case. */
@SpringBootConfiguration
@EnableAutoConfiguration
public class OpenInListTestApp {

    @Bean
    EntityView<OlNote> noteView() {
        return simpleView(OlNote.class);
    }

    @Bean
    EntityView<OlPinned> pinnedView() {
        return new EntityView<>() {
            @Override
            public Class<OlPinned> entity() {
                return OlPinned.class;
            }

            @Override
            public void fields(EntityConfigBuilder<OlPinned> f) {
                f.action("openInList").primary();
                f.action("duplicate").primary();
                f.action("delete").hidden();
            }
        };
    }

    @Bean
    EntityView<OlPaged> pagedView() {
        return simpleView(OlPaged.class);
    }

    @Bean
    Page pagedListOverride() {
        return new Page() {
            @Override
            public String route() {
                return "/catalogs/OlPaged";
            }

            @Override
            public void compose(PageBuilder b) {
                b.title("Paged");
                b.text("An authored page instead of the default list.");
            }
        };
    }

    @Bean
    EntityView<OlTask> taskView() {
        return new EntityView<>() {
            @Override
            public Class<OlTask> entity() {
                return OlTask.class;
            }

            @Override
            public void fields(EntityConfigBuilder<OlTask> f) {
                f.action("openInList").hidden();
                f.action("duplicate").primary();
            }
        };
    }

    @Bean
    EntityView<OlEvent> eventView() {
        return simpleView(OlEvent.class);
    }

    private static <E> EntityView<E> simpleView(Class<E> type) {
        return new EntityView<>() {
            @Override
            public Class<E> entity() {
                return type;
            }
        };
    }
}
