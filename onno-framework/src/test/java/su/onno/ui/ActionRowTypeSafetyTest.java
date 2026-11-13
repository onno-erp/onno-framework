package su.onno.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ActionRowTypeSafetyTest {

    @TempDir
    Path output;

    @Test
    void acceptsOwnAndInheritedFieldsAcrossTheAuthoringApi() throws Exception {
        assertThat(compile("""
                ActionRow<Order> row = new ActionRow<>(Map.of());
                row.get(Order::getVip);
                row.text(Order::getVip);
                row.bool(Order::getVip);
                row.bool(Base::getActive);
                row.enumValue(Order::getStatus, Status.class);
                EntityView<Order> view = new EntityView<>() {
                    public Class<Order> entity() { return Order.class; }
                    public void list(ListSpec<Order> list) {
                        list.rowStyle(r -> r.bool(Order::getVip) ? ListSpec.RowStyle.SUCCESS : null);
                    }
                    public void actions(ActionSpec<Order> actions) {
                        actions.action("test").label(r -> r.text(Order::getVip))
                            .icon(r -> r.text(Order::getStatus))
                            .visibleWhen(r -> r.bool(Base::getActive))
                            .enabledWhen(r -> r.bool(Order::getVip));
                        actions.dynamic(live -> live.action("dynamic")
                            .visibleWhen(r -> r.bool(Order::getVip)));
                        ActionSpec.Action<Order> action = actions.actions().getFirst();
                        action.enabledFn().test(row);
                    }
                };
                new PageBuilder().actions("Page", actions -> actions.action("test").label("Test"));
                """)).noneMatch(d -> d.getKind() == Diagnostic.Kind.ERROR);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "new ActionRow<Order>(Map.of()).get(Customer::getVip);",
            "new ActionRow<Order>(Map.of()).text(Customer::getVip);",
            "new ActionRow<Order>(Map.of()).bool(Customer::getVip);",
            "new ActionRow<Order>(Map.of()).enumValue(Customer::getStatus, Status.class);",
            "new ListSpec<Order>().rowStyle(row -> row.bool(Customer::getVip) ? ListSpec.RowStyle.DANGER : null);",
            "new ActionSpec<Order>().action(\"test\").visibleWhen(row -> row.bool(Customer::getVip));",
            "new ActionSpec<Order>().action(\"test\").enabledWhen(row -> row.bool(Customer::getVip));",
            "new ActionSpec<Order>().action(\"test\").label(row -> row.text(Customer::getVip));",
            "new ActionSpec<Order>().action(\"test\").icon(row -> row.text(Customer::getVip));",
            "new ActionSpec<Order>().dynamic(live -> live.action(\"test\").visibleWhen(row -> row.bool(Customer::getVip)));",
            "new ActionSpec<Order>().actions().getFirst().enabledFn().test(new ActionRow<Customer>(Map.of()));"
    })
    void rejectsFieldsAndRowsOfAnotherEntity(String body) throws Exception {
        assertThat(compile(body)).anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR);
    }

    private List<Diagnostic<? extends JavaFileObject>> compile(String body) throws Exception {
        String source = """
                import su.onno.ui.*;
                import java.util.Map;
                class TypeSafetyExample {
                    enum Status { NEW }
                    static class Base { public Boolean getActive() { return true; } }
                    static class Order extends Base {
                        public Boolean getVip() { return true; }
                        public Status getStatus() { return Status.NEW; }
                    }
                    static class Customer {
                        public Boolean getVip() { return true; }
                        public Status getStatus() { return Status.NEW; }
                    }
                    void configure() {
                """ + body + "\n}}";
        JavaFileObject input = new SimpleJavaFileObject(URI.create("string:///TypeSafetyExample.java"),
                JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        // Compile against the actual core artifact, independent of Gradle's worker classpath.
        String classpath = Path.of(ActionRow.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI()).toString();
        try (var files = compiler.getStandardFileManager(diagnostics, null, null)) {
            compiler.getTask(null, files, diagnostics, List.of("-proc:none", "-Xlint:rawtypes,unchecked",
                    "-Werror", "-classpath", classpath, "-d", output.toString()), null,
                    List.of(input)).call();
        }
        return diagnostics.getDiagnostics();
    }
}
