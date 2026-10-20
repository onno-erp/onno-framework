package su.onno.print;

import org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class DocxTemplateEngineTest {

    private final DocxTemplateEngine engine = new DocxTemplateEngine();

    record Line(String book, int qty) {
    }

    @Test
    void replacesPlaceholderSplitAcrossRunsKeepingFirstRunFormatting() throws IOException {
        byte[] template = docx(doc -> {
            XWPFParagraph p = doc.createParagraph();
            XWPFRun head = p.createRun();
            head.setText("Dear {{cus");
            head.setBold(true);
            p.createRun().setText("tomer");
            p.createRun().setText("}}, see {{missing}}.");
        });

        XWPFDocument out = render(template, model().text("customer", "Acme"));

        XWPFParagraph p = out.getParagraphs().get(0);
        assertThat(p.getText()).isEqualTo("Dear Acme, see {{missing}}.");
        assertThat(p.getRuns().get(0).getText(0)).isEqualTo("Dear Acme");
        assertThat(p.getRuns().get(0).isBold()).isTrue();
    }

    @Test
    void repeatsTableRowPerItemAndFillsGlobals() throws IOException {
        byte[] template = docx(doc -> {
            XWPFTable table = doc.createTable(3, 2);
            table.getRow(0).getCell(0).setText("Book");
            table.getRow(0).getCell(1).setText("Qty");
            table.getRow(1).getCell(0).setText("{{items.book}}");
            table.getRow(1).getCell(1).setText("{{items.qty}}");
            table.getRow(2).getCell(0).setText("Total");
            table.getRow(2).getCell(1).setText("{{total}}");
        });
        List<Line> lines = List.of(new Line("Dune", 2), new Line("Emma", 1), new Line("Ulysses", 4));

        XWPFDocument out = render(template, model()
                .number("total", 7)
                .table("items", lines, (row, line) -> row.text("book", line.book()).number("qty", line.qty())));

        XWPFTable table = out.getTables().get(0);
        assertThat(table.getRows()).hasSize(5);
        assertThat(cells(table, 1)).containsExactly("Dune", "2");
        assertThat(cells(table, 2)).containsExactly("Emma", "1");
        assertThat(cells(table, 3)).containsExactly("Ulysses", "4");
        assertThat(cells(table, 4)).containsExactly("Total", "7");
    }

    @Test
    void removesTableRowForEmptyCollection() throws IOException {
        byte[] template = docx(doc -> {
            XWPFTable table = doc.createTable(2, 1);
            table.getRow(0).getCell(0).setText("Book");
            table.getRow(1).getCell(0).setText("{{items.book}}");
        });

        XWPFDocument out = render(template, model().table("items", List.<Line>of(), (row, line) -> { }));

        assertThat(out.getTables().get(0).getRows()).hasSize(1);
    }

    @Test
    void fillsHeadersFootersAndFormatsWithPatterns() throws IOException {
        byte[] template = docx(doc -> {
            XWPFHeaderFooterPolicy policy = doc.createHeaderFooterPolicy();
            policy.createHeader(XWPFHeaderFooterPolicy.DEFAULT).createParagraph().createRun().setText("Order {{number}}");
            policy.createFooter(XWPFHeaderFooterPolicy.DEFAULT).createParagraph().createRun().setText("{{date}}");
            doc.createParagraph().createRun().setText("Amount {{amount}}\n{{note}}");
        });

        TemplateModel model = new TemplateModel(String::valueOf, Locale.GERMANY)
                .text("number", "SO-1")
                .date("date", LocalDate.of(2026, 3, 9), "dd.MM.yyyy")
                .number("amount", new BigDecimal("1234.5"), "#,##0.00")
                .text("note", "first\nsecond");
        XWPFDocument out = render(template, model);

        assertThat(out.getHeaderList().get(0).getText()).contains("Order SO-1");
        assertThat(out.getFooterList().get(0).getText()).contains("09.03.2026");
        XWPFRun run = out.getParagraphs().get(0).getRuns().get(0);
        assertThat(run.getText(0)).isEqualTo("Amount 1.234,50");
        assertThat(run.getCTR().sizeOfBrArray()).isEqualTo(2);
        assertThat(out.getParagraphs().get(0).getText()).contains("first").contains("second");
    }

    private static TemplateModel model() {
        return new TemplateModel(String::valueOf, Locale.ROOT);
    }

    private XWPFDocument render(byte[] template, TemplateModel model) throws IOException {
        return new XWPFDocument(new ByteArrayInputStream(engine.render(template, model)));
    }

    private static List<String> cells(XWPFTable table, int row) {
        return table.getRow(row).getTableCells().stream().map(c -> c.getText()).toList();
    }

    private static byte[] docx(Consumer<XWPFDocument> build) throws IOException {
        try (XWPFDocument doc = new XWPFDocument()) {
            build.accept(doc);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        }
    }
}
