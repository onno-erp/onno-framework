package su.onno.web;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Writes already authorized, projected rows. The caller supplies headings and display labels
 * for references and enums, and owns the output stream and the lifetime of the row source.
 */
final class ListExportWriter {

    private ListExportWriter() {
    }

    static void writeCsv(OutputStream output, List<String> headings, Iterable<? extends List<?>> rows)
            throws IOException {
        var csv = new CSVPrinter(new OutputStreamWriter(output, StandardCharsets.UTF_8), CSVFormat.RFC4180);
        printCsvRow(csv, headings);
        for (List<?> row : rows) {
            printCsvRow(csv, row);
        }
        csv.flush();
    }

    private static void printCsvRow(CSVPrinter csv, List<?> values) throws IOException {
        for (Object value : values) {
            String text = scalarText(value);
            // CSV quoting protects cell boundaries but does not prevent Excel from evaluating a formula.
            if (!(value instanceof Number) && looksLikeFormula(text)) {
                text = "'" + text;
            }
            csv.print(text);
        }
        csv.println();
    }

    private static boolean looksLikeFormula(String value) {
        for (int offset = 0; offset < value.length();) {
            int ch = value.codePointAt(offset);
            if ("=+-@＝＋－＠\t\r\n".indexOf(ch) >= 0) return true;
            if (!Character.isWhitespace(ch) && !Character.isSpaceChar(ch) && ch != '\uFEFF') return false;
            offset += Character.charCount(ch);
        }
        return false;
    }

    static void writeXlsx(OutputStream output, List<String> headings, Iterable<? extends List<?>> rows)
            throws IOException {
        try (var workbook = new SXSSFWorkbook(100)) {
            workbook.setCompressTempFiles(true);
            var sheet = workbook.createSheet("Export");
            sheet.createFreezePane(0, 1);
            writeHeadings(sheet.createRow(0), headings);

            var formats = workbook.createDataFormat();
            var date = workbook.createCellStyle();
            date.setDataFormat(formats.getFormat("yyyy-mm-dd"));
            var dateTime = workbook.createCellStyle();
            dateTime.setDataFormat(formats.getFormat("yyyy-mm-dd hh:mm:ss"));

            int rowIndex = 1;
            for (List<?> values : rows) {
                var row = sheet.createRow(rowIndex++);
                for (int column = 0; column < values.size(); column++) {
                    writeCell(row.createCell(column), values.get(column), date, dateTime);
                }
            }
            workbook.write(output);
        }
    }

    private static void writeHeadings(Row row, List<String> headings) {
        for (int column = 0; column < headings.size(); column++) {
            writeText(row.createCell(column), headings.get(column));
        }
    }

    private static void writeCell(Cell cell, Object value, CellStyle date, CellStyle dateTime) {
        switch (value) {
            case null -> cell.setBlank();
            case Boolean bool -> cell.setCellValue(bool);
            case Number number -> writeNumber(cell, number);
            case LocalDate day -> writeDate(cell, day.atStartOfDay(), date, day);
            case LocalDateTime stamp -> writeDate(cell, stamp, dateTime, stamp);
            case Instant stamp -> writeDate(cell, LocalDateTime.ofInstant(stamp, ZoneOffset.UTC), dateTime, stamp);
            case OffsetDateTime stamp ->
                    writeDate(cell, LocalDateTime.ofInstant(stamp.toInstant(), ZoneOffset.UTC), dateTime, stamp);
            case ZonedDateTime stamp ->
                    writeDate(cell, LocalDateTime.ofInstant(stamp.toInstant(), ZoneOffset.UTC), dateTime, stamp);
            default -> writeText(cell, scalarText(value));
        }
    }

    private static void writeText(Cell cell, String value) {
        if (value.length() > 32_767) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "A value exceeds the XLSX cell text limit of 32767 characters. Export as CSV.");
        }
        cell.setCellValue(value);
    }

    private static void writeNumber(Cell cell, Number number) {
        double numeric = number.doubleValue();
        if (Double.isFinite(numeric)) {
            // Excel keeps 15 significant digits. Preserve numbers outside its precision as text.
            BigDecimal exact = new BigDecimal(number.toString()).stripTrailingZeros();
            if (exact.precision() <= 15 && (numeric != 0 || exact.signum() == 0)) {
                cell.setCellValue(numeric);
                return;
            }
        }
        cell.setCellValue(scalarText(number));
    }

    private static void writeDate(Cell cell, LocalDateTime stamp, CellStyle format, Object original) {
        if (stamp.getYear() < 1900 || stamp.getYear() > 9999) {
            cell.setCellValue(scalarText(original));
        } else {
            cell.setCellValue(stamp);
            cell.setCellStyle(format);
        }
    }

    private static String scalarText(Object value) {
        return switch (value) {
            case null -> "";
            case BigDecimal decimal -> decimal.toPlainString();
            default -> value.toString();
        };
    }
}
