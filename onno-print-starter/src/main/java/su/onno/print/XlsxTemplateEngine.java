package su.onno.print;

import org.apache.poi.ss.formula.FormulaParser;
import org.apache.poi.ss.formula.FormulaRenderer;
import org.apache.poi.ss.formula.FormulaType;
import org.apache.poi.ss.formula.ptg.Area3DPxg;
import org.apache.poi.ss.formula.ptg.AreaPtgBase;
import org.apache.poi.ss.formula.ptg.Ptg;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellCopyPolicy;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFEvaluationWorkbook;
import org.apache.poi.xssf.usermodel.XSSFFormulaEvaluator;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Fills an Excel {@code .xlsx} template. A cell holding exactly one {@code number}/{@code date}
 * placeholder receives a real numeric/date value and keeps its own style and number format; other
 * placeholders are replaced as text. A row that references {@code {{items.<column>}}} is copied once
 * per item (styles, merged cells and relative formulas included); rows below move down, and any
 * range that ends on the template row — e.g. {@code SUM(C5:C5)} in a totals row — is stretched over
 * the inserted rows. Formulas are recalculated before saving.
 */
public final class XlsxTemplateEngine implements TemplateEngine {

    @Override
    public byte[] render(byte[] template, TemplateModel model) {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(template))) {
            Styles styles = new Styles(wb);
            Function<String, TemplateValue> globals = key -> PlaceholderSyntax.lookup(key, model, null, null);
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                XSSFSheet sheet = wb.getSheetAt(s);
                expandTables(wb, sheet, model, styles);
                for (Row row : sheet) fillRow(row, globals, styles, model.locale());
            }
            wb.setForceFormulaRecalculation(true);
            try {
                XSSFFormulaEvaluator.evaluateAllFormulaCells(wb);
            } catch (RuntimeException unsupported) {
                // A function POI cannot evaluate: Excel recalculates on open (forced above).
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not render Excel template", e);
        }
    }

    private static void expandTables(XSSFWorkbook wb, XSSFSheet sheet, TemplateModel model, Styles styles) {
        if (model.tables().isEmpty()) return;
        int r = Math.max(sheet.getFirstRowNum(), 0);
        while (r <= sheet.getLastRowNum()) {
            Row row = sheet.getRow(r);
            String name = row == null ? null : PlaceholderSyntax.tableIn(rowText(row), model);
            if (name == null) {
                r++;
                continue;
            }
            List<TemplateModel.Row> items = model.tables().get(name);
            if (items.isEmpty()) {
                // Blank the row rather than delete it: deleting would break formulas that reference it.
                fillRow(row, key -> key.startsWith(name + ".") ? new TemplateValue.Text("") : null, styles,
                        model.locale());
                r++;
                continue;
            }
            int extra = items.size() - 1;
            if (extra > 0) {
                if (r < sheet.getLastRowNum()) sheet.shiftRows(r + 1, sheet.getLastRowNum(), extra, true, false);
                CellCopyPolicy policy = new CellCopyPolicy();
                for (int k = 1; k <= extra; k++) sheet.copyRows(r, r, r + k, policy);
                stretchRanges(wb, sheet, r, extra);
            }
            for (int k = 0; k < items.size(); k++) {
                TemplateModel.Row item = items.get(k);
                fillRow(sheet.getRow(r + k), key -> PlaceholderSyntax.lookup(key, model, name, item), styles,
                        model.locale());
            }
            r += items.size();
        }
    }

    /**
     * Extends every range whose last row is the template row {@code r} down over the {@code extra}
     * copied rows. Formulas inside the expanded block itself are skipped: they are per-row formulas
     * already adjusted by the copy.
     */
    private static void stretchRanges(XSSFWorkbook wb, XSSFSheet sheet, int r, int extra) {
        XSSFEvaluationWorkbook ewb = XSSFEvaluationWorkbook.create(wb);
        int target = wb.getSheetIndex(sheet);
        for (int s = 0; s < wb.getNumberOfSheets(); s++) {
            XSSFSheet formulaSheet = wb.getSheetAt(s);
            for (Row row : formulaSheet) {
                if (s == target && row.getRowNum() >= r && row.getRowNum() <= r + extra) continue;
                for (Cell cell : row) {
                    if (cell.getCellType() != CellType.FORMULA) continue;
                    Ptg[] ptgs;
                    try {
                        ptgs = FormulaParser.parse(cell.getCellFormula(), ewb, FormulaType.CELL, s);
                    } catch (RuntimeException unparseable) {
                        continue; // e.g. an external-workbook reference: leave the formula as authored
                    }
                    boolean changed = false;
                    for (Ptg ptg : ptgs) {
                        if (!(ptg instanceof AreaPtgBase area)) continue;
                        boolean sameSheet = ptg instanceof Area3DPxg x
                                ? sheet.getSheetName().equalsIgnoreCase(x.getSheetName())
                                : s == target;
                        if (sameSheet && area.getFirstRow() <= r && area.getLastRow() == r) {
                            area.setLastRow(r + extra);
                            changed = true;
                        }
                    }
                    if (changed) cell.setCellFormula(FormulaRenderer.toFormulaString(ewb, ptgs));
                }
            }
        }
    }

    private static String rowText(Row row) {
        StringBuilder text = new StringBuilder();
        for (Cell cell : row) {
            if (cell.getCellType() == CellType.STRING) text.append(cell.getStringCellValue()).append('\n');
        }
        return text.toString();
    }

    private static void fillRow(Row row, Function<String, TemplateValue> values, Styles styles, Locale locale) {
        if (row == null) return;
        for (Cell cell : row) {
            if (cell.getCellType() != CellType.STRING) continue;
            String text = cell.getStringCellValue();
            if (text.indexOf("{{") < 0) continue;
            List<PlaceholderSyntax.MatchResultView> matches = PlaceholderSyntax.matches(text);
            if (matches.size() == 1 && text.trim().length() == matches.get(0).end() - matches.get(0).start()) {
                TemplateValue value = values.apply(matches.get(0).key());
                if (value != null) {
                    // Writing over an inline string ("t=inlineStr", as openpyxl and some exporters
                    // save text) leaves the old <is> text Excel actually shows; blanking first
                    // drops it and keeps the cell's style.
                    cell.setBlank();
                    setValue(cell, value, styles, locale);
                }
            } else {
                String replaced = PlaceholderSyntax.replace(text, values, locale);
                if (!replaced.equals(text)) {
                    cell.setBlank();
                    cell.setCellValue(replaced);
                }
            }
        }
    }

    private static void setValue(Cell cell, TemplateValue value, Styles styles, Locale locale) {
        switch (value) {
            case TemplateValue.Number n when n.value() == null -> { }
            case TemplateValue.Number n -> cell.setCellValue(n.value().doubleValue());
            case TemplateValue.Date d when d.value() == null -> { }
            case TemplateValue.Date d when d.value() instanceof LocalDateTime dt -> {
                cell.setCellValue(dt);
                styles.ensureDate(cell, "yyyy-mm-dd hh:mm");
            }
            case TemplateValue.Date d when d.value() instanceof LocalDate date -> {
                cell.setCellValue(date);
                styles.ensureDate(cell, "yyyy-mm-dd");
            }
            default -> cell.setCellValue(value.asText(locale));
        }
    }

    /**
     * Gives a date cell a date format when its template style has none, through a cloned style so
     * other cells sharing the original style are untouched. Clones are reused per (style, format).
     */
    private static final class Styles {
        private final XSSFWorkbook wb;
        private final Map<String, CellStyle> clones = new HashMap<>();

        Styles(XSSFWorkbook wb) {
            this.wb = wb;
        }

        void ensureDate(Cell cell, String format) {
            CellStyle style = cell.getCellStyle();
            if (DateUtil.isADateFormat(style.getDataFormat(), style.getDataFormatString())) return;
            cell.setCellStyle(clones.computeIfAbsent(style.getIndex() + "|" + format, k -> {
                CellStyle clone = wb.createCellStyle();
                clone.cloneStyleFrom(style);
                clone.setDataFormat(wb.createDataFormat().getFormat(format));
                return clone;
            }));
        }
    }
}
