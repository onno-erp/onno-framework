package su.onno.web.pickerdialog;

import su.onno.command.CommandResult;
import su.onno.command.CommandSpec;
import su.onno.command.Commands;
import su.onno.web.pickerdialog.model.PdGroup;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;

/** Fixture for the "Show all…" picker descriptor (#411). */
@SpringBootConfiguration
@EnableAutoConfiguration
public class PickerDialogTestApp {

    /** A collection command the picker must never surface. */
    @Bean
    Commands<PdGroup> groupCommands() {
        return new Commands<>() {
            @Override
            public Class<PdGroup> entity() {
                return PdGroup.class;
            }

            @Override
            public void define(CommandSpec<PdGroup> c) {
                c.collectionCommand("archive").title("Archive")
                        .run(ctx -> CommandResult.ok());
            }
        };
    }
}
