package su.onno.web;

import su.onno.command.CommandRejectedException;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class CommandRejectedExceptionHandlerTest {

    @Test
    void mapsExpectedBusinessRejectionToStructured422() {
        CommandRejectedException exception = CommandRejectedException.builder()
                .title("Approval blocked")
                .message("The room is occupied")
                .fieldError("reason", "Only soft conflicts may be justified")
                .build();

        var response = new CommandRejectedExceptionHandler().handle(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isSameAs(exception.feedback());
        assertThat(response.getBody().fieldErrors()).containsKey("reason");
    }
}
