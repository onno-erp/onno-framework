package su.onno.process;

/**
 * Serialization boundary used by the durable process engine.
 *
 * <p>The core runtime deliberately does not choose a JSON library. The Spring starter supplies a
 * Jackson implementation configured with the application's modules.</p>
 */
public interface ProcessPayloadCodec {

    String write(Object value);

    <T> T read(String value, Class<T> type);
}
