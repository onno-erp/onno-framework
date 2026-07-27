package su.onno.spring;

import org.junit.jupiter.api.Test;
import su.onno.spring.fixtures.rowreuse.SharedRowDocuments;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TabularSectionRowReuseTest {

    @Test
    void rejectsRowClassReusedAcrossTabularSections() {
        assertThatThrownBy(() -> OnnoAutoConfiguration.buildTabularSectionTables(
                List.of(SharedRowDocuments.class.getPackageName())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(SharedRowDocuments.SharedLine.class.getName())
                .hasMessageContaining(SharedRowDocuments.FirstDocument.class.getName())
                .hasMessageContaining("firstLines")
                .hasMessageContaining(SharedRowDocuments.SecondDocument.class.getName())
                .hasMessageContaining("secondLines")
                .hasMessageContaining("distinct concrete row class");
    }
}
