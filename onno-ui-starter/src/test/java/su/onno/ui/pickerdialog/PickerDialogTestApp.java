package su.onno.ui.pickerdialog;

import su.onno.ui.ActionResult;
import su.onno.ui.ActionScope;
import su.onno.ui.ActionSpec;
import su.onno.ui.EntityView;
import su.onno.ui.ListSpec;
import su.onno.ui.Page;
import su.onno.ui.PageBuilder;
import su.onno.ui.pickerdialog.model.PdGroup;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;

/** Fixture for the "Show all…" picker descriptor (#411). */
@SpringBootConfiguration
@EnableAutoConfiguration
public class PickerDialogTestApp {

    @Bean
    EntityView<PdGroup> groupView() {
        return new EntityView<>() {
            @Override
            public Class<PdGroup> entity() {
                return PdGroup.class;
            }

            @Override
            public void list(ListSpec<PdGroup> list) {
                list.columns("description", "region");
                list.filter("region").contains();
            }

            @Override
            public void actions(ActionSpec a) {
                a.action("archive").label("Archive").scope(ActionScope.TOOLBAR)
                        .handler(ctx -> ActionResult.ok());
            }
        };
    }

    @Bean
    Page groupListOverride() {
        return new Page() {
            @Override
            public String route() {
                return "/catalogs/PdGroups";
            }

            @Override
            public void compose(PageBuilder b) {
                b.title("Groups board");
                b.text("An authored page instead of the default list.");
            }
        };
    }
}
