package su.onno.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ActionFeedbackTest {

    @Test
    void legacyActionResultConstructorAndFactoriesRemainCompatible() {
        ActionResult direct = new ActionResult("Done", "onno://catalogs/items", true);

        assertThat(direct.message()).isEqualTo("Done");
        assertThat(direct.navigate()).isEqualTo("onno://catalogs/items");
        assertThat(direct.refresh()).isTrue();
        assertThat(direct.feedback()).isNull();
        assertThat(ActionResult.message("Saved").message()).isEqualTo("Saved");
        assertThat(ActionResult.refresh("Saved").refresh()).isTrue();
    }

    @Test
    void successfulDialogCarriesTypedFeedback() {
        ActionResult result = ActionResult.dialog(ActionDialog.info("Import completed")
                .message("124 records were imported")
                .detail("3 rows were skipped"));

        assertThat(result.feedback().severity()).isEqualTo(ActionSeverity.INFO);
        assertThat(result.feedback().presentation()).isEqualTo(ActionPresentation.DIALOG);
        assertThat(result.feedback().title()).isEqualTo("Import completed");
        assertThat(result.feedback().details()).containsExactly("3 rows were skipped");
    }

    @Test
    void rejectionCarriesFormAndFieldErrorsAndKeepsFormOpenByDefault() {
        ActionRejectedException rejection = ActionRejectedException.builder()
                .title("Approval blocked")
                .message("The room is occupied")
                .formError("A justification cannot override a hard conflict")
                .fieldError("reason", "Only soft conflicts may be justified")
                .build();

        assertThat(rejection.feedback().severity()).isEqualTo(ActionSeverity.ERROR);
        assertThat(rejection.feedback().presentation()).isEqualTo(ActionPresentation.DIALOG);
        assertThat(rejection.feedback().keepFormOpen()).isTrue();
        assertThat(rejection.feedback().formErrors()).hasSize(1);
        assertThat(rejection.feedback().fieldErrors().get("reason"))
                .containsExactly("Only soft conflicts may be justified");
    }
}
