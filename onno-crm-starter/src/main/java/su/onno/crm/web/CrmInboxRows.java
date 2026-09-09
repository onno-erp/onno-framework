package su.onno.crm.web;

import java.util.*;
import org.springframework.util.MultiValueMap;
import su.onno.crm.domain.Conversation;

/** Fields whose filtering and sorting do not require resolving a host catalog or reference. */
final class CrmInboxRows {
    private static final Set<String> FIELDS=Set.of("customer","channel","subject","status","priority","lastMessageAt","lastMessagePreview","unreadCount");
    private CrmInboxRows() {}
    static boolean canPageBeforeDecoration(String search,String sort,MultiValueMap<String,String> params) {
        if(!search.isEmpty() || (sort!=null && !FIELDS.contains(sort)))return false;
        for(String op:List.of("eq","in","like","prefix","ge","le"))
            for(String expression:params.getOrDefault(op,List.of()))
                if(!FIELDS.contains(expression.split(",",2)[0]))return false;
        return true;
    }
    static Map<String,Object> raw(Conversation c) {
        Map<String,Object> row=new LinkedHashMap<>();
        row.put("id",c.getId());row.put("customer",c.getCustomer());row.put("channel",c.getChannel());
        row.put("subject",c.getSubject());row.put("status",c.getStatus());row.put("priority",c.getPriority());
        row.put("lastMessageAt",c.getLastMessageAt());row.put("lastMessagePreview",c.getLastMessagePreview());
        row.put("unreadCount",c.getUnreadCount());return row;
    }
}
