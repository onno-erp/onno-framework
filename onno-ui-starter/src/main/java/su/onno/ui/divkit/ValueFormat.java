package su.onno.ui.divkit;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Currency;
import java.util.Locale;

/**
 * Applies a column's display {@code .format(...)} hint to a raw cell value, for the detail surface
 * (and any server-rendered table). Kept deliberately in lock-step with the client-side formatter in
 * {@code lib/cell-format.ts} so a formatted column looks identical in the React list and the DivKit
 * detail.
 *
 * <p>The hint is interpreted by the value's shape:</p>
 * <ul>
 *   <li><b>Numbers</b> — {@code "integer"}, {@code "decimal"}, {@code "percent"},
 *       {@code "currency"} (or {@code "currency:EUR"}), or a {@link DecimalFormat} pattern such as
 *       {@code "#,##0.00"}.</li>
 *   <li><b>Dates / date-times</b> — a date pattern, e.g. {@code "dd-MM-yy"}; uppercase {@code D}/{@code Y}
 *       are normalized to day/year so {@code "DD-MM-YYYY"} works as written.</li>
 * </ul>
 *
 * <p>Returns {@code null} when the hint is blank or the value doesn't fit the spec, so the caller
 * can fall back to the raw text.</p>
 */
final class ValueFormat {

    private ValueFormat() {
    }

    static String apply(String format, Object value) {
        if (format == null || value == null) {
            return null;
        }
        String fmt = format.trim();
        String raw = value.toString().trim();
        if (fmt.isEmpty() || raw.isEmpty()) {
            return null;
        }
        return isNumberSpec(fmt) ? number(raw, fmt) : date(raw, fmt);
    }

    private static boolean isNumberSpec(String fmt) {
        String l = fmt.toLowerCase(Locale.ROOT);
        return l.equals("integer") || l.equals("decimal") || l.equals("percent")
                || l.startsWith("currency") || fmt.matches(".*[#0].*");
    }

    private static String number(String raw, String fmt) {
        double n;
        try {
            n = Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return null;
        }
        String l = fmt.toLowerCase(Locale.ROOT);
        try {
            if (l.equals("integer")) {
                return new DecimalFormat("#,##0").format(n);
            }
            if (l.equals("decimal")) {
                return new DecimalFormat("#,##0.00").format(n);
            }
            if (l.equals("percent")) {
                NumberFormat pf = NumberFormat.getPercentInstance();
                pf.setMaximumFractionDigits(2);
                return pf.format(n);
            }
            if (l.startsWith("currency")) {
                NumberFormat cf = NumberFormat.getCurrencyInstance();
                int colon = fmt.indexOf(':');
                String code = colon >= 0 ? fmt.substring(colon + 1).trim().toUpperCase(Locale.ROOT) : "USD";
                try {
                    cf.setCurrency(Currency.getInstance(code));
                } catch (RuntimeException ignored) {
                    // Unknown code — keep the locale's default currency.
                }
                return cf.format(n);
            }
            return new DecimalFormat(fmt).format(n);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String date(String raw, String fmt) {
        DateTimeFormatter out;
        try {
            out = DateTimeFormatter.ofPattern(normalizeDatePattern(fmt), Locale.getDefault());
        } catch (RuntimeException e) {
            return null;
        }
        // Accept the shapes a value can arrive in: an ISO offset date-time
        // ("2027-02-09T09:00:00.000+00:00"), a JDBC timestamp ("2027-02-09 12:00:00.0" — space
        // separator), a plain ISO date-time, or a plain date ("2027-02-09").
        String norm = raw.indexOf('T') >= 0 ? raw : raw.replaceFirst(" ", "T");
        try {
            return OffsetDateTime.parse(norm).format(out);
        } catch (RuntimeException ignored) {
            // try next
        }
        try {
            return LocalDateTime.parse(norm).format(out);
        } catch (RuntimeException ignored) {
            // try next
        }
        try {
            return LocalDate.parse(norm).format(out);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** Accept the common {@code DD-MM-YYYY} spelling: uppercase {@code D}→day, {@code Y}→year. */
    private static String normalizeDatePattern(String p) {
        return p.replace('D', 'd').replace('Y', 'y');
    }
}
