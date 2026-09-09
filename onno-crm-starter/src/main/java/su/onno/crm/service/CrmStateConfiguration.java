package su.onno.crm.service;

import java.util.*;

/** Application-owned state definitions. IDs are persistent identities; labels may change freely. */
public final class CrmStateConfiguration {
    private final java.util.function.Supplier<List<Status>> choices;
    private final Transitions transitions;
    private final String source;
    public List<Status> conversationStatuses() { return List.copyOf(choices.get()); }
    public Transitions transitions() { return transitions; }
    public String source() { return source; }
    /** Ordinary ListSpec option values for a filter over Conversation.status. */
    public Map<String,String> options() {
        var result=new LinkedHashMap<String,String>();
        conversationStatuses().forEach(s->result.put(s.choice().id().toString(),s.choice().label()));
        return result;
    }
    private CrmStateConfiguration(String source, java.util.function.Supplier<List<Status>> choices, Transitions transitions) {
        this.source=source; this.choices=choices; this.transitions=transitions;
    }
    /** Read live choices from an ordinary host catalog; CRM never writes or mirrors it. */
    public static <T extends su.onno.model.CatalogObject> CrmStateConfiguration catalog(
            Class<T> type, java.util.function.Supplier<List<T>> records,
            java.util.function.Function<T,String> color, java.util.function.Predicate<T> closed, Transitions transitions) {
        var annotation=type.getAnnotation(su.onno.annotations.Catalog.class);
        if(annotation==null)throw new IllegalArgumentException("Status source must be a catalog");
        return new CrmStateConfiguration(annotation.name(), () -> records.get().stream().filter(r->!r.isDeletionMark())
            .map(r->new Status(new Choice(r.getId(),r.getDescription(),color.apply(r)),closed.test(r))).toList(), transitions);
    }
    /** Bind a normal onno enumeration, preserving its standard persistent IDs and labels. */
    public static <E extends Enum<E>> CrmStateConfiguration enumeration(Class<E> type,
            java.util.function.Predicate<E> closed, Transitions transitions) {
        var annotation=type.getAnnotation(su.onno.annotations.Enumeration.class);
        if(annotation==null)throw new IllegalArgumentException("Status source must be an onno enumeration");
        return new CrmStateConfiguration(annotation.name(), () -> Arrays.stream(type.getEnumConstants()).map(value -> {
            try {
                var label=type.getField(value.name()).getAnnotation(su.onno.annotations.EnumLabel.class);
                return new Status(new Choice(su.onno.repository.EnumerationPersistence.resolveId(type,value),
                    label==null?value.name():label.value(),label==null?null:label.color()),closed.test(value));
            } catch(NoSuchFieldException e) { throw new IllegalStateException(e); }
        }).toList(), transitions);
    }
    public record Choice(UUID id, String label, String color) {
        public Choice {
            Objects.requireNonNull(id,"State ID");
            color=color==null?"":color;
            if(label==null||label.isBlank()||label.length()>200)throw new IllegalArgumentException("State label must contain 1–200 characters");
            if(color!=null&&!color.isBlank()&&!color.matches("#[0-9a-fA-F]{6}"))throw new IllegalArgumentException("State color must be #RRGGBB");
        }
    }
    public record Status(Choice choice, boolean closed) { public Status { Objects.requireNonNull(choice); } }
    public record Transitions(UUID incoming, UUID reply, UUID close, UUID reopen) {}
    /** No status model or transitions are installed by default. */
    public static CrmStateConfiguration empty(){return new CrmStateConfiguration("",List::of,null);}
}
