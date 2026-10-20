package su.onno.print;

import su.onno.ui.RecordActionContributor;

import java.util.List;

/**
 * Offers each print form the viewer may use in the record surface's header menu. The action key is
 * {@code print-{key}}, so {@code f.action("print-invoice").primary()} promotes it to a button.
 */
public class PrintRecordActions implements RecordActionContributor {

    private final PrintService print;

    public PrintRecordActions(PrintService print) {
        this.print = print;
    }

    @Override
    public List<RecordAction> actions(RecordActionRequest request) {
        return print.formsFor(request.type()).stream()
                .filter(form -> print.permits(form, request.subject()))
                .map(form -> new RecordAction("print-" + form.key(), print.icon(form), form.label(),
                        "onno://download/api/print/" + request.kind() + "/" + request.name() + "/"
                                + request.id() + "/" + form.key()))
                .toList();
    }
}
