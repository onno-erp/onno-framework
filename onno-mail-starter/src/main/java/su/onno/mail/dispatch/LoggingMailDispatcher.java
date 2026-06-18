package su.onno.mail.dispatch;

import su.onno.mail.MailMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dev dispatcher ({@code provider=log}). Logs the message instead of sending it.
 * Useful in local/test profiles where no real provider should be hit.
 */
public class LoggingMailDispatcher implements MailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(LoggingMailDispatcher.class);

    @Override
    public String name() {
        return "log";
    }

    @Override
    public void dispatch(MailMessage message) {
        log.info("[mail:log] from={} to={} cc={} bcc={} subject=\"{}\" html={} attachments={}",
                message.from(), message.to(), message.cc(), message.bcc(),
                message.subject(), message.isHtml(), message.attachments().size());
        if (log.isDebugEnabled()) {
            log.debug("[mail:log] body:\n{}", message.isHtml() ? message.html() : message.text());
        }
    }
}
