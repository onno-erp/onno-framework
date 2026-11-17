package su.onno.print;

import su.onno.web.RecordActionContributor;

import java.util.ArrayList;
import java.util.List;

/**
 * Offers each print form the viewer may use as a record action keyed {@code print-{key}}; the
 * client's {@code commands} customization places and icons it by that key. When a
 * PDF converter is configured, a form whose default output is its template format also gets a
 * {@code print-{key}-pdf} entry (and a PDF form a {@code print-{key}-source} one).
 */
public class PrintRecordActions implements RecordActionContributor {

    private final PrintService print;

    public PrintRecordActions(PrintService print) {
        this.print = print;
    }

    @Override
    public List<RecordAction> actions(RecordActionRequest request) {
        List<RecordAction> actions = new ArrayList<>();
        for (PrintForm<?> form : print.formsFor(request.type())) {
            if (!print.permits(form, request.subject())) continue;
            String url = "onno://download/api/print/" + request.kind() + "/" + request.name() + "/"
                    + request.id() + "/" + form.key();
            actions.add(new RecordAction("print-" + form.key(), form.label(), url));
            PrintVariant variant = PrintVariant.of(print, form);
            if (variant != null) {
                actions.add(new RecordAction("print-" + form.key() + "-" + variant.suffix(),
                        form.label() + " · " + variant.format().toUpperCase(), url + "?format=" + variant.format()));
            }
        }
        return actions;
    }
}
