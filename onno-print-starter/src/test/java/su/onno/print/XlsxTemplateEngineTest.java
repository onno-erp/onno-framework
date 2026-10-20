package su.onno.print;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.STCellType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class XlsxTemplateEngineTest {

    private final XlsxTemplateEngine engine = new XlsxTemplateEngine();

    record Line(String book, int qty) {
    }

    @Test
    void writesTypedValuesWithoutMutatingSharedStyles() throws IOException {
        byte[] template = xlsx(sheet -> {
            CellStyle shared = sheet.getWorkbook().createCellStyle();
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue("{{amount}}");
            row.createCell(1).setCellValue("{{date}}");
            row.getCell(1).setCellStyle(shared);
            row.createCell(2).setCellValue("Order {{number}} for {{customer}}");
            row.createCell(3).setCellValue("untouched");
            row.getCell(3).setCellStyle(shared);
        });

        Sheet out = render(template, model()
                .number("amount", 12.5)
                .date("date", LocalDate.of(2026, 1, 31))
                .text("number", "SO-7")
                .text("customer", "Acme")).getSheetAt(0);

        Row row = out.getRow(0);
        assertThat(row.getCell(0).getCellType()).isEqualTo(CellType.NUMERIC);
        assertThat(row.getCell(0).getNumericCellValue()).isEqualTo(12.5);
        assertThat(DateUtil.isCellDateFormatted(row.getCell(1))).isTrue();
        assertThat(row.getCell(1).getLocalDateTimeCellValue().toLocalDate()).isEqualTo(LocalDate.of(2026, 1, 31));
        assertThat(row.getCell(2).getStringCellValue()).isEqualTo("Order SO-7 for Acme");
        // The date format went onto a cloned style, not the style the other cell still shares.
        CellStyle shared = row.getCell(3).getCellStyle();
        assertThat(DateUtil.isADateFormat(shared.getDataFormat(), shared.getDataFormatString())).isFalse();
        assertThat(row.getCell(1).getCellStyle().getIndex()).isNotEqualTo(shared.getIndex());
    }

    @Test
    void repeatsTableRowsAndStretchesTotals() throws IOException {
        byte[] template = xlsx(sheet -> {
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Book");
            header.createCell(1).setCellValue("Qty");
            Row line = sheet.createRow(1);
            line.createCell(0).setCellValue("{{items.book}}");
            line.createCell(1).setCellValue("{{items.qty}}");
            line.createCell(2).setCellFormula("B2*10");
            Row total = sheet.createRow(2);
            total.createCell(0).setCellValue("Total");
            total.createCell(1).setCellFormula("SUM(B2:B2)");
        });
        List<Line> lines = List.of(new Line("Dune", 2), new Line("Emma", 1), new Line("Ulysses", 4));

        Sheet out = render(template, model().table("items", lines,
                (row, l) -> row.text("book", l.book()).number("qty", l.qty()))).getSheetAt(0);

        assertThat(out.getRow(1).getCell(0).getStringCellValue()).isEqualTo("Dune");
        assertThat(out.getRow(3).getCell(0).getStringCellValue()).isEqualTo("Ulysses");
        assertThat(out.getRow(3).getCell(1).getNumericCellValue()).isEqualTo(4);
        assertThat(out.getRow(3).getCell(2).getCellFormula()).isEqualTo("B4*10");
        assertThat(out.getRow(3).getCell(2).getNumericCellValue()).isEqualTo(40);
        Cell sum = out.getRow(4).getCell(1);
        assertThat(sum.getCellFormula()).isEqualTo("SUM(B2:B4)");
        assertThat(sum.getNumericCellValue()).isEqualTo(7);
    }

    @Test
    void blanksTableRowForEmptyCollection() throws IOException {
        byte[] template = xlsx(sheet -> {
            Row line = sheet.createRow(0);
            line.createCell(0).setCellValue("{{items.book}}");
            line.createCell(1).setCellValue("{{other}}");
        });

        Sheet out = render(template, model().table("items", List.<Line>of(), (row, l) -> { })).getSheetAt(0);

        assertThat(out.getRow(0).getCell(0).getStringCellValue()).isEmpty();
        assertThat(out.getRow(0).getCell(1).getStringCellValue()).isEqualTo("{{other}}");
    }

    @Test
    void replacesInlineStringCells() throws IOException {
        // openpyxl and several exporters save text as inline strings, not shared strings.
        byte[] template = xlsx(sheet -> {
            Row row = sheet.createRow(0);
            inline((XSSFCell) row.createCell(0), "Order {{number}}");
            inline((XSSFCell) row.createCell(1), "{{qty}}");
        });

        XSSFWorkbook out = render(template, model().text("number", "SO-1").number("qty", 3));

        XSSFCell text = out.getSheetAt(0).getRow(0).getCell(0);
        assertThat(text.getStringCellValue()).isEqualTo("Order SO-1");
        assertThat(text.getCTCell().isSetIs()).isFalse();
        assertThat(out.getSheetAt(0).getRow(0).getCell(1).getCTCell().isSetIs()).isFalse();
        assertThat(out.getSheetAt(0).getRow(0).getCell(1).getNumericCellValue()).isEqualTo(3);
    }

    private static void inline(XSSFCell cell, String text) {
        cell.getCTCell().setT(STCellType.INLINE_STR);
        cell.getCTCell().addNewIs().setT(text);
    }

    private static TemplateModel model() {
        return new TemplateModel(String::valueOf, Locale.ROOT);
    }

    private XSSFWorkbook render(byte[] template, TemplateModel model) throws IOException {
        return new XSSFWorkbook(new ByteArrayInputStream(engine.render(template, model)));
    }

    private static byte[] xlsx(Consumer<Sheet> build) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            build.accept(wb.createSheet("Sheet1"));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }
}
