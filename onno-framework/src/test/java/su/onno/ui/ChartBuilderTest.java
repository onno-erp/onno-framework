package su.onno.ui;

import org.junit.jupiter.api.Test;
import su.onno.annotations.Attribute;
import su.onno.annotations.Document;
import su.onno.model.DocumentObject;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChartBuilderTest {

    @Test
    void typedChartBuildsFineGrainedLegacyCompatibleConfig() {
        PageBuilder page = new PageBuilder();

        page.chart("Revenue", TestOrder.class)
                .width("full")
                .time(TestOrder::getDate, ChartBuilder.TimeBucket.WEEK)
                .sum(TestOrder::getTotal)
                .area().label("Revenue").color("primary").currency("USD")
                .secondary("Orders", m -> m.count().bar().color("warning"))
                .axis(ChartBuilder.Axis.LEFT, a -> a.minimum(0).maximum(50_000).label("Revenue"))
                .axis(ChartBuilder.Axis.RIGHT, a -> a.minimum(0).label("Orders"))
                .threshold("Target", 20_000, t -> t.color("success").style(ChartBuilder.LineStyle.DASHED))
                .legend(ChartBuilder.Legend.TOP)
                .dataLabels(ChartBuilder.DataLabels.AUTO)
                .curve(ChartBuilder.Curve.LINEAR)
                .points(true)
                .height(320);

        UiLayoutBuilder.WidgetConfig widget = page.widgets().getFirst();
        assertThat(widget.type()).isEqualTo("chart");
        assertThat(widget.entityType()).isEqualTo("document");
        assertThat(widget.entityClass()).isEqualTo(TestOrder.class);
        assertThat(widget.dateField()).isEqualTo("date");
        assertThat(widget.extraConfig())
                .containsEntry("groupBy", "date")
                .containsEntry("groupByDate", "week")
                .containsEntry("bucketMode", "fixed")
                .containsEntry("metric", "sum")
                .containsEntry("metricField", "total")
                .containsEntry("measure2", "count")
                .containsEntry("kind2", "bar")
                .containsEntry("color", "primary")
                .containsEntry("color2", "warning")
                .containsEntry("yMin", "0.0")
                .containsEntry("yMax", "50000.0")
                .containsEntry("threshold.0.value", "20000.0")
                .containsEntry("threshold.0.label", "Target")
                .containsEntry("legend", "top")
                .containsEntry("dataLabels", "auto")
                .containsEntry("curve", "linear")
                .containsEntry("points", "true")
                .containsEntry("height", "320");
    }

    @Test
    void rejectsAnUnmodelledSourceAndUnsafeDimensions() {
        assertThatThrownBy(() -> new PageBuilder().chart("Bad", String.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@Document");

        assertThatThrownBy(() -> new PageBuilder().chart("Bad", TestOrder.class).height(80))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 120 and 800");
    }

    @Document(name = "chart_test_orders")
    static class TestOrder extends DocumentObject {
        @Attribute
        private BigDecimal total;
        public BigDecimal getTotal() { return total; }
    }
}
