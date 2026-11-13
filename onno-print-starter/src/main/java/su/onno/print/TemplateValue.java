package su.onno.print;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.Locale;

/**
 * One value bound to a template placeholder. Text is inserted verbatim; numbers and dates stay typed
 * so a spreadsheet cell receives a real number/date (formatted by the template cell's own number
 * format), while a Word document renders them with the optional Java {@code pattern}.
 */
public sealed interface TemplateValue {

    /** The value rendered as document text (Word, or a placeholder embedded in a longer Excel string). */
    String asText(Locale locale);

    record Text(String value) implements TemplateValue {
        public Text {
            value = value == null ? "" : value;
        }

        @Override
        public String asText(Locale locale) {
            return value;
        }
    }

    /** A number; {@code pattern} is a {@link DecimalFormat} pattern used for document text only. */
    record Number(BigDecimal value, String pattern) implements TemplateValue {
        @Override
        public String asText(Locale locale) {
            if (value == null) return "";
            if (pattern == null) return value.toPlainString();
            return new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(locale)).format(value);
        }
    }

    /** A date or date-time; {@code pattern} is a {@link DateTimeFormatter} pattern used for document text only. */
    record Date(TemporalAccessor value, String pattern) implements TemplateValue {
        @Override
        public String asText(Locale locale) {
            if (value == null) return "";
            if (pattern != null) return DateTimeFormatter.ofPattern(pattern, locale).format(value);
            if (value instanceof LocalDateTime dt) return DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(dt.withNano(0));
            if (value instanceof LocalDate d) return DateTimeFormatter.ISO_LOCAL_DATE.format(d);
            return value.toString();
        }
    }
}
