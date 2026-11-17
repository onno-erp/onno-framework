package su.onno.command;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandFeedbackTest {

    @Test
    void resultCarriesOnlyRefreshAndMeaning() {
        assertThat(CommandResult.ok()).isEqualTo(new CommandResult(false, null));
        assertThat(CommandResult.reload()).isEqualTo(new CommandResult(true, null));

        CommandResult result = CommandResult.success("Saved").andRefresh();
        assertThat(result.refresh()).isTrue();
        assertThat(result.feedback().severity()).isEqualTo(CommandSeverity.SUCCESS);
        assertThat(result.feedback().message()).isEqualTo("Saved");
        assertThat(result.feedback().title()).isNull();
    }

    @Test
    void titleAndDetailsAccumulate() {
        CommandResult result = CommandResult.warning("Two order lines need attention")
                .title("Stock is running low")
                .detail("Review quantities")
                .detail(" ")
                .detail("Create replenishment");

        assertThat(result.refresh()).isFalse();
        assertThat(result.feedback().severity()).isEqualTo(CommandSeverity.WARNING);
        assertThat(result.feedback().title()).isEqualTo("Stock is running low");
        assertThat(result.feedback().message()).isEqualTo("Two order lines need attention");
        assertThat(result.feedback().details()).containsExactly("Review quantities", "Create replenishment");
        assertThat(result.feedback().asText())
                .isEqualTo("Stock is running low: Two order lines need attention (Review quantities; Create replenishment)");
    }

    @Test
    void okHasNothingToTitle() {
        assertThatThrownBy(() -> CommandResult.ok().title("x")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectionCarriesFormAndFieldErrors() {
        CommandRejectedException rejection = CommandRejectedException.builder()
                .title("Approval blocked")
                .message("The room is occupied")
                .formError("A justification cannot override a hard conflict")
                .fieldError("reason", "Only soft conflicts may be justified")
                .build();

        assertThat(rejection.feedback().severity()).isEqualTo(CommandSeverity.ERROR);
        assertThat(rejection.feedback().formErrors()).hasSize(1);
        assertThat(rejection.feedback().fieldErrors().get("reason"))
                .containsExactly("Only soft conflicts may be justified");
        assertThat(rejection.getMessage()).isEqualTo("The room is occupied");
    }
}
