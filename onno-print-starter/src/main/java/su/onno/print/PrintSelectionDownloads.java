package su.onno.print;

import su.onno.ui.SelectionDownloadContributor;

import java.util.ArrayList;
import java.util.List;

/**
 * Batch printing: with rows selected, the list's selection toolbar offers each permitted form, which
 * downloads a zip of the selected records filled into it ({@code POST /api/print/batch/...}).
 */
public class PrintSelectionDownloads implements SelectionDownloadContributor {

    private final PrintService print;

    public PrintSelectionDownloads(PrintService print) {
        this.print = print;
    }

    @Override
    public List<SelectionDownload> downloads(SelectionRequest request) {
        List<SelectionDownload> downloads = new ArrayList<>();
        for (PrintForm<?> form : print.formsFor(request.type())) {
            if (!print.permits(form, request.subject())) continue;
            String url = "/api/print/batch/" + request.kind() + "/" + request.name() + "/" + form.key();
            downloads.add(new SelectionDownload("print-" + form.key(), print.icon(form), form.label(), url));
            PrintVariant variant = PrintVariant.of(print, form);
            if (variant != null) {
                downloads.add(new SelectionDownload("print-" + form.key() + "-" + variant.suffix(), variant.icon(),
                        form.label() + " · " + variant.format().toUpperCase(), url + "?format=" + variant.format()));
            }
        }
        return downloads;
    }
}
