package su.onno.crm.service;

import java.util.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.transaction.annotation.Transactional;
import su.onno.crm.domain.*;
import su.onno.crm.repository.*;

/** Mirrors code definitions for existing Ref rendering. Catalog writes are not a configuration API. */
public class CrmStateProjection implements ApplicationRunner, Ordered {
    private final CrmStateConfiguration config;
    private final LifecycleStageRepository stages;
    private final ChatStatusRepository statuses;
    public CrmStateProjection(CrmStateConfiguration config,LifecycleStageRepository stages,ChatStatusRepository statuses){this.config=config;this.stages=stages;this.statuses=statuses;}
    @Override public int getOrder(){return Ordered.HIGHEST_PRECEDENCE;}
    @Override @Transactional public void run(ApplicationArguments args){
        if(config.contactStages().isEmpty() && config.conversationStatuses().isEmpty())return;
        Set<UUID> stageIds=new HashSet<>(),statusIds=new HashSet<>();
        for(var choice:config.contactStages()) {
            stageIds.add(choice.id());var row=stages.findById(choice.id()).orElseGet(LifecycleStage::new);
            if(!row.isDeletionMark()&&choice.label().equals(row.getDescription())&&choice.color().equals(row.getColor()))continue;
            row.setId(choice.id());row.setDeletionMark(false);row.setDescription(choice.label());row.setColor(choice.color());stages.save(row);
        }
        for(var definition:config.conversationStatuses()) {
            var choice=definition.choice();statusIds.add(choice.id());var row=statuses.findById(choice.id()).orElseGet(ChatStatus::new);
            var t=config.transitions();boolean incoming=choice.id().equals(t.incoming()),reply=choice.id().equals(t.reply()),close=choice.id().equals(t.close()),reopen=choice.id().equals(t.reopen());
            if(!row.isDeletionMark()&&choice.label().equals(row.getDescription())&&choice.color().equals(row.getColor())&&row.isClosed()==definition.closed()
                &&row.isAfterIncoming()==incoming&&row.isAfterReply()==reply&&row.isAfterClose()==close&&row.isAfterReopen()==reopen)continue;
            row.setId(choice.id());row.setDeletionMark(false);row.setDescription(choice.label());row.setColor(choice.color());row.setClosed(definition.closed());
            row.setAfterIncoming(incoming);row.setAfterReply(reply);row.setAfterClose(close);row.setAfterReopen(reopen);statuses.save(row);
        }
        // Archive removed choices without breaking the labels on historical references.
        stages.findAllActive().stream().filter(s->!stageIds.contains(s.getId())).forEach(stages::delete);
        statuses.findAllActive().stream().filter(s->!statusIds.contains(s.getId())).forEach(statuses::delete);
    }
}
