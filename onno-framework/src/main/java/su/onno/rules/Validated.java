package su.onno.rules;

import java.util.List;

/**
 * A document/catalog that declares typed validation rules, checked before write
 * and before posting. Implement it and return your {@link BusinessRule}s — the
 * Java-native replacement for {@code @BusinessRule} annotations.
 *
 * <pre>
 * public List&lt;BusinessRule&gt; rules() {
 *     return List.of(
 *         new BusinessRule("client-required", "Client is required", () -&gt; client != null),
 *         new BusinessRule("gross-positive", "Gross must be positive",
 *                 () -&gt; gross != null &amp;&amp; gross.signum() &gt; 0));
 * }
 * </pre>
 */
public interface Validated {

    List<BusinessRule> rules();
}
