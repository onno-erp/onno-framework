package su.onno.ui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BatchRunnerActionFeedbackTest {

    @Test
    void preservesFirstPurposefulRejectionAlongsideBatchTally() {
        UUID rejected = UUID.randomUUID();
        UUID accepted = UUID.randomUUID();
        BatchRunner runner = new BatchRunner(1);

        Map<String, Object> result = runner.run(List.of(rejected, accepted), id -> {
            if (id.equals(rejected)) {
                throw ActionRejectedException.builder()
                        .title("Approval blocked")
                        .message("Room conflict")
                        .build();
            }
        });

        assertThat(result).containsEntry("ok", 1).containsEntry("total", 2)
                .containsEntry("feedbackRejected", true);
        assertThat(result.get("failed")).isEqualTo(List.of(rejected.toString()));
        assertThat((ActionFeedback) result.get("feedback"))
                .extracting(ActionFeedback::title, ActionFeedback::message)
                .containsExactly("Approval blocked", "Room conflict");
    }
}
