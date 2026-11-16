package su.onno.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import su.onno.process.ProcessDefinitions;
import su.onno.process.ProcessEngine;
import su.onno.ui.notifications.NotificationController;
import su.onno.ui.notifications.NotificationService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ControllerConstructorWiringTest {
    @Test
    void springSelectsProductionConstructorsInsteadOfCompatibilityOverloads() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(NotificationService.class, () -> mock(NotificationService.class));
            context.registerBean(CurrentUserResolver.class, () -> mock(CurrentUserResolver.class));
            context.registerBean(RecordAccess.class, () -> mock(RecordAccess.class));
            context.registerBean(ProcessEngine.class, () -> mock(ProcessEngine.class));
            context.registerBean(ProcessDefinitions.class, () -> mock(ProcessDefinitions.class));
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(UiAccessService.class, () -> mock(UiAccessService.class));
            context.registerBean(TaskAssigneeDirectory.class, () -> mock(TaskAssigneeDirectory.class));
            context.register(NotificationController.class, ProcessController.class);
            context.refresh();

            assertThat(ReflectionTestUtils.getField(context.getBean(NotificationController.class), "recordAccess"))
                    .isSameAs(context.getBean(RecordAccess.class));
            assertThat(ReflectionTestUtils.getField(context.getBean(ProcessController.class), "assignees"))
                    .isSameAs(context.getBean(TaskAssigneeDirectory.class));
            assertThat(ReflectionTestUtils.getField(context.getBean(ProcessController.class), "currentUser"))
                    .isSameAs(context.getBean(CurrentUserResolver.class));
        }
    }
}
