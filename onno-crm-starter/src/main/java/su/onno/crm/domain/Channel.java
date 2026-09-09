package su.onno.crm.domain;
/** Conventional connector keys. Any connector may use its own stable namespaced key. Not model metadata. */
public final class Channel {
    private Channel() {}
    public static final String TELEGRAM="TELEGRAM", WHATSAPP="WHATSAPP", EMAIL="EMAIL",
        WEB_CHAT="WEB_CHAT", PHONE="PHONE", INSTAGRAM="INSTAGRAM";
}
