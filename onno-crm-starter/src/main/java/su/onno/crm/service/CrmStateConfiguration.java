package su.onno.crm.service;

import java.util.*;

/** Application-owned state definitions. IDs are persistent identities; labels may change freely. */
public record CrmStateConfiguration(List<Choice> contactStages, List<Status> conversationStatuses,
                                    Transitions transitions) {
    public record Choice(UUID id, String label, String color) {
        public Choice {
            Objects.requireNonNull(id,"State ID");
            if(label==null||label.isBlank()||label.length()>200)throw new IllegalArgumentException("State label must contain 1–200 characters");
            if(color==null||!color.matches("#[0-9a-fA-F]{6}"))throw new IllegalArgumentException("State color must be #RRGGBB");
        }
    }
    public record Status(Choice choice, boolean closed) { public Status { Objects.requireNonNull(choice); } }
    public record Transitions(UUID incoming, UUID reply, UUID close, UUID reopen) {}
    public CrmStateConfiguration {
        contactStages=List.copyOf(contactStages);conversationStatuses=List.copyOf(conversationStatuses);
        unique(contactStages.stream().map(Choice::id).toList());
        unique(conversationStatuses.stream().map(s->s.choice().id()).toList());
        if(!conversationStatuses.isEmpty()) {
            Objects.requireNonNull(transitions,"Configure conversation transitions");
            requireStatus(conversationStatuses,transitions.incoming(),false);
            requireStatus(conversationStatuses,transitions.reply(),false);
            requireStatus(conversationStatuses,transitions.close(),true);
            requireStatus(conversationStatuses,transitions.reopen(),false);
        } else if(transitions!=null)throw new IllegalArgumentException("Transitions require configured statuses");
    }
    public static CrmStateConfiguration empty(){return new CrmStateConfiguration(List.of(),List.of(),null);}
    private static void unique(List<UUID> ids){if(new HashSet<>(ids).size()!=ids.size())throw new IllegalArgumentException("Duplicate state ID");}
    private static void requireStatus(List<Status> statuses,UUID id,boolean closed){
        if(statuses.stream().noneMatch(s->s.choice().id().equals(id)&&s.closed()==closed))
            throw new IllegalArgumentException("Transition must target a configured "+(closed?"closed":"open")+" status: "+id);
    }
}
