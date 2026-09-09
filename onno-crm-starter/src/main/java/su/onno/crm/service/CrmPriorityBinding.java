package su.onno.crm.service;

import java.util.*;
import java.util.function.*;
import su.onno.model.CatalogObject;

/** Optional host-owned priority catalog or enumeration. No default value or built-in choices. */
public record CrmPriorityBinding(CrmStateConfiguration choices) {
    public CrmPriorityBinding { Objects.requireNonNull(choices); }
    public static CrmPriorityBinding empty(){return new CrmPriorityBinding(CrmStateConfiguration.empty());}
    public static <T extends CatalogObject> CrmPriorityBinding catalog(Class<T> type,Supplier<List<T>> records,Function<T,String> color) {
        return new CrmPriorityBinding(CrmStateConfiguration.catalog(type,records,color,row->false,null));
    }
    public static <E extends Enum<E>> CrmPriorityBinding enumeration(Class<E> type) {
        return new CrmPriorityBinding(CrmStateConfiguration.enumeration(type,value->false,null));
    }
    public Map<String,String> options(){return choices.options();}
}
