package su.onno.ui;

/**
 * One column of a {@link PageRow}: a {@code width} and a {@link PageBuilder} region holding the
 * column's content (any block — widget, list, text, custom — plus further nested rows).
 *
 * <p>{@code width} controls the column's share of the row:</p>
 * <ul>
 *   <li>a fraction — {@code "1/2"}, {@code "2/3"}, {@code "1/3"}, {@code "1/4"} … — the column takes
 *       that proportion (the numerator is used as a flex weight, so {@code 2/3}+{@code 1/3} splits
 *       2:1);</li>
 *   <li>{@code "<n>px"} — a fixed width in dp (e.g. {@code "300px"} for a rail that never flexes);</li>
 *   <li>{@code null} or {@code "full"} — an equal share of the remaining space.</li>
 * </ul>
 */
public record PageColumn(String width, PageBuilder region) {
}
