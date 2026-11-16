package su.onno.web;

import su.onno.command.CommandFeedback;
import su.onno.command.CommandRejectedException;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BatchRunnerCommandFeedbackTest {

    @Test
    void preservesFirstPurposefulRejectionAlongsideBatchTally() {
        UUID rejected = UUID.randomUUID();
        UUID accepted = UUID.randomUUID();
        BatchRunner runner = new BatchRunner(1);

        Map<String, Object> result = runner.run(List.of(rejected, accepted), id -> {
            if (id.equals(rejected)) {
                throw CommandRejectedException.builder()
                        .title("Approval blocked")
                        .message("Room conflict")
                        .build();
            }
        });

        assertThat(result).containsEntry("ok", 1).containsEntry("total", 2)
                .containsEntry("feedbackRejected", true);
        assertThat(result.get("failed")).isEqualTo(List.of(rejected.toString()));
        assertThat((CommandFeedback) result.get("feedback"))
                .extracting(CommandFeedback::title, CommandFeedback::message)
                .containsExactly("Approval blocked", "Room conflict");
    }
}
