package su.onno.command;

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

class RecordRowTypeSafetyTest {

    @TempDir
    Path output;

    @Test
    void acceptsOwnAndInheritedFieldsAcrossTheAuthoringApi() throws Exception {
        assertThat(compile("""
                RecordRow<Order> row = new RecordRow<>(Map.of());
                row.get(Order::getVip);
                row.text(Order::getVip);
                row.bool(Order::getVip);
                row.bool(Base::getActive);
                row.enumValue(Order::getStatus, Status.class);
                Commands<Order> view = new Commands<>() {
                    public Class<Order> entity() { return Order.class; }
                    public void define(CommandSpec<Order> commands) {
                        commands.command("test").title(r -> r.text(Order::getVip))
                            .availableWhen(r -> r.bool(Base::getActive))
                            .enabledWhen(r -> r.bool(Order::getVip))
                            .run(ctx -> CommandResult.ok());
                        commands.dynamic(live -> live.command("dynamic")
                            .availableWhen(r -> r.bool(Order::getVip)).run(ctx -> CommandResult.ok()));
                        Command<Order> command = commands.commands().getFirst();
                        command.enabledFn().test(row);
                    }
                };
                """)).noneMatch(d -> d.getKind() == Diagnostic.Kind.ERROR);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "new RecordRow<Order>(Map.of()).get(Customer::getVip);",
            "new RecordRow<Order>(Map.of()).text(Customer::getVip);",
            "new RecordRow<Order>(Map.of()).bool(Customer::getVip);",
            "new RecordRow<Order>(Map.of()).enumValue(Customer::getStatus, Status.class);",
            "new CommandSpec<Order>().command(\"test\").availableWhen(row -> row.bool(Customer::getVip));",
            "new CommandSpec<Order>().command(\"test\").enabledWhen(row -> row.bool(Customer::getVip));",
            "new CommandSpec<Order>().command(\"test\").title(row -> row.text(Customer::getVip));",
            "new CommandSpec<Order>().dynamic(live -> live.command(\"test\").availableWhen(row -> row.bool(Customer::getVip)));",
            "new CommandSpec<Order>().commands().getFirst().enabledFn().test(new RecordRow<Customer>(Map.of()));"
    })
    void rejectsFieldsAndRowsOfAnotherEntity(String body) throws Exception {
        assertThat(compile(body)).anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR);
    }

    private List<Diagnostic<? extends JavaFileObject>> compile(String body) throws Exception {
        String source = """
                import su.onno.command.*;
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
        String classpath = Path.of(RecordRow.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI()).toString();
        try (var files = compiler.getStandardFileManager(diagnostics, null, null)) {
            compiler.getTask(null, files, diagnostics, List.of("-proc:none", "-Xlint:rawtypes,unchecked",
                    "-Werror", "-classpath", classpath, "-d", output.toString()), null,
                    List.of(input)).call();
        }
        return diagnostics.getDiagnostics();
    }
}
