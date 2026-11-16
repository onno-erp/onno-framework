package su.onno.crm.channels.gmail;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import su.onno.web.media.LoadedMedia;
import su.onno.web.media.MediaStorage;
import su.onno.web.media.StoredMedia;

/**
 * Serves the files of Gmail history mail through the ordinary media endpoint without storing them.
 * The history import records each file as a media key {@code gmail/<id>/<name>}; this wraps the
 * application's {@link MediaStorage} so such a key is described from the CRM's table and its bytes are
 * fetched from Gmail only when someone opens it. Every other key goes to the wrapped storage unchanged.
 *
 * <p>The id is random, so a file is as private as an uploaded one: it is reachable only by its URL,
 * which only the conversation that holds it shows.
 */
@Component
@ConditionalOnProperty(name = "onno.crm.channels.gmail.enabled", havingValue = "true")
public class GmailMediaFiles implements BeanPostProcessor {
    private final ObjectProvider<GmailBridge> bridge;

    public GmailMediaFiles(ObjectProvider<GmailBridge> bridge) {
        this.bridge = bridge;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String name) {
        return bean instanceof MediaStorage storage && !(bean instanceof Storage) ? new Storage(storage, bridge) : bean;
    }

    /** The application's storage, with Gmail-kept files resolved in front of it. */
    static final class Storage implements MediaStorage {
        private final MediaStorage delegate;
        private final ObjectProvider<GmailBridge> bridge;

        Storage(MediaStorage delegate, ObjectProvider<GmailBridge> bridge) {
            this.delegate = delegate;
            this.bridge = bridge;
        }

        @Override
        public StoredMedia store(InputStream content, String filename, String contentType, long size) throws IOException {
            return delegate.store(content, filename, contentType, size);
        }

        @Override
        public Optional<LoadedMedia> load(String key) {
            if (key != null && key.startsWith(GmailBridge.FILE_PREFIX)) {
                var gmail = bridge.getIfAvailable();
                return gmail == null ? Optional.empty() : gmail.file(key);
            }
            return delegate.load(key);
        }
    }
}
