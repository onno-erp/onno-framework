package su.onno.print;

import org.apache.poi.xwpf.usermodel.IBody;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHeaderFooter;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTText;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Fills a Word {@code .docx} template. Placeholders are found across run boundaries (Word often
 * splits {@code {{customer}}} into several runs when it is typed or spell-checked), and the
 * replacement keeps the formatting of the run the placeholder starts in. A table row that references
 * {@code {{items.<column>}}} is cloned once per item, keeping the row's formatting.
 */
public final class DocxTemplateEngine implements TemplateEngine {

    @Override
    public byte[] render(byte[] template, TemplateModel model) {
        try {
            byte[] expanded = expandTables(template, model);
            // Rows inserted while expanding are written through copies of the template row's XML,
            // so the second pass works on a freshly parsed document rather than stale row objects.
            try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(expanded))) {
                Function<String, TemplateValue> globals = key -> PlaceholderSyntax.lookup(key, model, null, null);
                for (IBody body : bodies(doc)) {
                    replaceInBody(body, globals, model.locale());
                }
                return write(doc);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not render Word template", e);
        }
    }

    private static byte[] expandTables(byte[] template, TemplateModel model) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(template))) {
            if (!model.tables().isEmpty()) {
                for (IBody body : bodies(doc)) {
                    expandTablesInBody(body, model);
                }
            }
            return write(doc);
        }
    }

    private static List<IBody> bodies(XWPFDocument doc) {
        List<IBody> bodies = new ArrayList<>();
        bodies.add(doc);
        for (XWPFHeaderFooter header : doc.getHeaderList()) bodies.add(header);
        for (XWPFHeaderFooter footer : doc.getFooterList()) bodies.add(footer);
        return bodies;
    }

    private static void expandTablesInBody(IBody body, TemplateModel model) {
        for (IBodyElement element : List.copyOf(body.getBodyElements())) {
            if (element instanceof XWPFTable table) {
                expandTable(table, model);
            }
        }
    }

    private static void expandTable(XWPFTable table, TemplateModel model) {
        int i = 0;
        while (i < table.getNumberOfRows()) {
            XWPFTableRow row = table.getRow(i);
            String name = PlaceholderSyntax.tableIn(rowText(row), model);
            if (name == null) {
                for (XWPFTableCell cell : row.getTableCells()) expandTablesInBody(cell, model);
                i++;
                continue;
            }
            List<TemplateModel.Row> items = model.tables().get(name);
            CTRow templateXml = row.getCtRow();
            for (int k = 0; k < items.size(); k++) {
                TemplateModel.Row item = items.get(k);
                XWPFTableRow copy = new XWPFTableRow((CTRow) templateXml.copy(), table);
                Function<String, TemplateValue> values = key -> PlaceholderSyntax.lookup(key, model, name, item);
                for (XWPFTableCell cell : copy.getTableCells()) replaceInBody(cell, values, model.locale());
                table.addRow(copy, i + k);
            }
            table.removeRow(i + items.size());
            i += items.size();
        }
    }

    private static String rowText(XWPFTableRow row) {
        StringBuilder text = new StringBuilder();
        for (XWPFTableCell cell : row.getTableCells()) text.append(cell.getText()).append('\n');
        return text.toString();
    }

    private static void replaceInBody(IBody body, Function<String, TemplateValue> values, Locale locale) {
        for (IBodyElement element : body.getBodyElements()) {
            if (element instanceof XWPFParagraph paragraph) {
                replaceInParagraph(paragraph, values, locale);
            } else if (element instanceof XWPFTable table) {
                for (XWPFTableRow row : table.getRows()) {
                    for (XWPFTableCell cell : row.getTableCells()) replaceInBody(cell, values, locale);
                }
            }
        }
    }

    /**
     * Replaces placeholders in one paragraph. Matches are applied last-to-first so the text before
     * each match, and therefore every earlier match's run offsets, is unchanged when it is reached.
     */
    static void replaceInParagraph(XWPFParagraph paragraph, Function<String, TemplateValue> values, Locale locale) {
        List<XWPFRun> runs = paragraph.getRuns();
        if (runs.isEmpty()) return;
        String[] texts = new String[runs.size()];
        int[] starts = new int[runs.size()];
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < runs.size(); i++) {
            starts[i] = joined.length();
            texts[i] = runText(runs.get(i));
            joined.append(texts[i]);
        }
        if (joined.indexOf("{{") < 0) return;

        boolean[] changed = new boolean[runs.size()];
        List<PlaceholderSyntax.MatchResultView> matches = PlaceholderSyntax.matches(joined.toString());
        for (int m = matches.size() - 1; m >= 0; m--) {
            PlaceholderSyntax.MatchResultView match = matches.get(m);
            TemplateValue value = values.apply(match.key());
            if (value == null) continue;
            int first = runAt(starts, match.start());
            int last = runAt(starts, match.end() - 1);
            String head = texts[first].substring(0, match.start() - starts[first]);
            String tail = texts[last].substring(match.end() - starts[last]);
            String replacement = value.asText(locale);
            if (first == last) {
                texts[first] = head + replacement + tail;
            } else {
                texts[first] = head + replacement;
                for (int i = first + 1; i < last; i++) texts[i] = "";
                texts[last] = tail;
            }
            for (int i = first; i <= last; i++) changed[i] = true;
        }
        for (int i = 0; i < runs.size(); i++) {
            if (changed[i]) setRunText(runs.get(i), texts[i]);
        }
    }

    private static int runAt(int[] starts, int offset) {
        int run = 0;
        while (run + 1 < starts.length && starts[run + 1] <= offset) run++;
        return run;
    }

    private static String runText(XWPFRun run) {
        StringBuilder text = new StringBuilder();
        for (CTText t : run.getCTR().getTArray()) text.append(t.getStringValue());
        return text.toString();
    }

    /** Sets a run's text, turning {@code \n} into line breaks. */
    private static void setRunText(XWPFRun run, String text) {
        while (run.getCTR().sizeOfTArray() > 1) run.getCTR().removeT(1);
        String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        run.setText(lines[0], 0);
        for (int i = 1; i < lines.length; i++) {
            run.addBreak();
            run.setText(lines[i]);
        }
    }

    private static byte[] write(XWPFDocument doc) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.write(out);
        return out.toByteArray();
    }
}
