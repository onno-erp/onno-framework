package su.onno.crm.service;

import java.util.List;

/** Connector-owned setup and health commands. Credentials never appear in the public view. */
public interface CrmChannelConnection {
    record View(String key, String label, String channel, String state, String account,
                String description, List<String> actions) {}
    String key();
    View view();
    /** Supported actions are connector-owned. Throw on invalid credentials without including them. */
    void command(String action, String credential);
}
