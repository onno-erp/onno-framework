package su.onno.print;

import java.util.Map;

/**
 * Renders a registered template for a given target object into bytes.
 * The format (HTML/PDF) is determined by the template's declared {@link PrintFormat}.
 */
public interface PrintService {

    /** Render the template registered under {@code templateName} for {@code target}'s class. */
    PrintResult render(Object target, String templateName);

    /** Render with explicit extra model variables exposed to the template under {@code "extra"}. */
    PrintResult render(Object target, String templateName, Map<String, Object> extras);
}
