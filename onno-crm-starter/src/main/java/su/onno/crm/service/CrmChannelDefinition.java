package su.onno.crm.service;
/** Connector-owned channel presentation. No fixed channel enumeration or built-in configuration page. */
public record CrmChannelDefinition(String key, String label, String iconUrl) {
    public CrmChannelDefinition {
        if(key==null || !key.matches("[A-Za-z][A-Za-z0-9_.:-]{0,99}"))throw new IllegalArgumentException("Invalid channel key");
        if(label==null || label.isBlank())throw new IllegalArgumentException("Channel label required");
        iconUrl=iconUrl==null?"":iconUrl;
        if(!iconUrl.isEmpty() && (!iconUrl.startsWith("/") || iconUrl.startsWith("//")))throw new IllegalArgumentException("Use a local connector icon resource");
    }
}
