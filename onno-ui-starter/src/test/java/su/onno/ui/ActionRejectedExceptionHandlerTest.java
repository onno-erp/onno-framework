package su.onno.ui;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class ActionRejectedExceptionHandlerTest {

    @Test
    void mapsExpectedBusinessRejectionToStructured422() {
        ActionRejectedException exception = ActionRejectedException.builder()
                .title("Approval blocked")
                .message("The room is occupied")
                .presentation(ActionPresentation.INLINE)
                .fieldError("reason", "Only soft conflicts may be justified")
                .build();

        var response = new ActionRejectedExceptionHandler().handle(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isSameAs(exception.feedback());
        assertThat(response.getBody().fieldErrors()).containsKey("reason");
    }
}
