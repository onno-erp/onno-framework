package su.onno.web;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ListExportWriterTest {

    @Test
    void xlsxRejectsTextBeyondItsCellLimitAndCsvPreservesIt() throws IOException {
        String text = "Д".repeat(32_768);
        assertThatThrownBy(() -> ListExportWriter.writeXlsx(new ExportOutput(), List.of("Text"),
                List.of(List.of(text))))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        var csv = new ExportOutput();
        ListExportWriter.writeCsv(csv, List.of("Text"), List.of(List.of(text)));
        try (var parsed = CSVFormat.RFC4180.parse(new java.io.StringReader(csv.toString(StandardCharsets.UTF_8)))) {
            assertThat(parsed.getRecords().get(1).get(0)).isEqualTo(text);
        }
    }

    @Test
    void xlsxAllowsMaximumCellTextAndAnEmptyExport() throws IOException {
        var output = new ExportOutput();
        ListExportWriter.writeXlsx(output, List.of("Text"), List.of(List.of("x".repeat(32_767))));
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(output.toByteArray()))) {
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue()).hasSize(32_767);
        }
        var empty = new ExportOutput();
        ListExportWriter.writeXlsx(empty, List.of("Text"), List.of());
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(empty.toByteArray()))) {
            assertThat(workbook.getSheetAt(0).getPhysicalNumberOfRows()).isEqualTo(1);
        }
    }

    @Test
    void csvRoundTripsUnicodeDelimitersAndMultilineText() throws IOException {
        var output = new ExportOutput();
        var cells = Arrays.asList("Анна, \"Тест\"\nвторая строка", new BigDecimal("-12.50"), null);

        ListExportWriter.writeCsv(output, List.of("Клиент", "Сумма", "Комментарий"), List.of(cells));

        try (var csv = CSVFormat.RFC4180.parse(new java.io.StringReader(output.toString(StandardCharsets.UTF_8)))) {
            assertThat(csv.getRecords()).extracting(CSVRecord::toList).containsExactly(
                    List.of("Клиент", "Сумма", "Комментарий"),
                    List.of("Анна, \"Тест\"\nвторая строка", "-12.50", ""));
        }
        assertThat(output.closed).isFalse();
    }

    @Test
    void csvNeutralizesFormulaLikeHeadingsAndTextValues() throws IOException {
        var output = new ExportOutput();
        ListExportWriter.writeCsv(output, List.of("=Header", "Value", "Tag"),
                List.of(List.of("  =2+2", "\t@tag", "＋123")));

        try (var csv = CSVFormat.RFC4180.parse(new java.io.StringReader(output.toString(StandardCharsets.UTF_8)))) {
            assertThat(csv.getRecords()).extracting(CSVRecord::toList).containsExactly(
                    List.of("'=Header", "Value", "Tag"),
                    List.of("'  =2+2", "'\t@tag", "'＋123"));
        }
    }

    @Test
    void xlsxKeepsTypesAndPreservesTextThatMustNotBecomeAFormulaOrLosePrecision() throws IOException {
        var output = new ExportOutput();
        var day = LocalDate.of(2026, 11, 16);
        var stamp = OffsetDateTime.parse("2026-11-16T10:30:00+03:00");
        var value = new BigDecimal("1234567890123456");
        ListExportWriter.writeXlsx(output, List.of("Text", "Amount", "Day", "Time", "Exact", "Flag", "Empty"),
                List.of(Arrays.asList("=2+2", new BigDecimal("-12.50"), day, stamp, value, true, null)));

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(output.toByteArray()))) {
            var sheet = workbook.getSheetAt(0);
            var row = sheet.getRow(1);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Text");
            assertThat(row.getCell(0).getCellType()).isEqualTo(CellType.STRING);
            assertThat(row.getCell(0).getStringCellValue()).isEqualTo("=2+2");
            assertThat(row.getCell(1).getNumericCellValue()).isEqualTo(-12.5);
            assertThat(DateUtil.isCellDateFormatted(row.getCell(2))).isTrue();
            assertThat(row.getCell(2).getLocalDateTimeCellValue()).isEqualTo(day.atStartOfDay());
            assertThat(row.getCell(3).getLocalDateTimeCellValue()).isEqualTo(day.atTime(7, 30));
            assertThat(row.getCell(4).getStringCellValue()).isEqualTo(value.toPlainString());
            assertThat(row.getCell(5).getBooleanCellValue()).isTrue();
            assertThat(row.getCell(6).getCellType()).isEqualTo(CellType.BLANK);
        }
        assertThat(output.closed).isFalse();
    }

    @Test
    void xlsxRetainsRowsBeyondTheWorkbookMemoryWindow() throws IOException {
        var output = new ExportOutput();
        Iterable<List<Integer>> rows = () -> IntStream.rangeClosed(1, 125).mapToObj(List::of).iterator();

        ListExportWriter.writeXlsx(output, List.of("Index"), rows);

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(output.toByteArray()))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getPhysicalNumberOfRows()).isEqualTo(126);
            assertThat(sheet.getRow(1).getCell(0).getNumericCellValue()).isEqualTo(1);
            assertThat(sheet.getRow(125).getCell(0).getNumericCellValue()).isEqualTo(125);
        }
    }

    private static final class ExportOutput extends ByteArrayOutputStream {
        private boolean closed;

        @Override
        public void close() {
            closed = true;
        }
    }
}
