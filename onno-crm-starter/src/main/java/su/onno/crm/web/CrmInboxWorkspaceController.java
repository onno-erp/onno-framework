package su.onno.crm.web;

import java.security.Principal;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.context.ApplicationEventPublisher;
import su.onno.crm.domain.Conversation;
import su.onno.crm.service.*;
import su.onno.ui.*;
import su.onno.ui.comments.*;
import su.onno.events.EntityChangedEvent;

/** Scoped inbox reads; generic conversation exports remain unavailable to non-admin workspace users. */
@RestController
@RequestMapping("/api/crm/inbox-workspaces")
public class CrmInboxWorkspaceController {
    private final CrmInboxWorkspaceService workspaces;
    private final CatalogQueryService catalogs;
    private final CurrentUserResolver users;
    private final CommentService comments;
    private final CommentAuthorAvatars avatars;
    private final ApplicationEventPublisher events;
    private final CrmContactService contacts;
    private final org.springframework.beans.factory.ObjectProvider<CrmAgentBinding<?>> agents;
    private final UiAccessService access;
    @org.springframework.beans.factory.annotation.Autowired private UiViewResolver listViews;
    @org.springframework.beans.factory.annotation.Autowired private CrmStateConfiguration states;
    @org.springframework.beans.factory.annotation.Autowired private CrmPriorityBinding priorities;
    public CrmInboxWorkspaceController(CrmInboxWorkspaceService workspaces,CatalogQueryService catalogs,
            CurrentUserResolver users,CommentService comments,CommentAuthorAvatars avatars,ApplicationEventPublisher events,CrmContactService contacts,
            org.springframework.beans.factory.ObjectProvider<CrmAgentBinding<?>> agents,UiAccessService access) {
        this.workspaces=workspaces;this.catalogs=catalogs;this.users=users;this.comments=comments;this.avatars=avatars;this.events=events;this.contacts=contacts;this.agents=agents;this.access=access;
    }
    @GetMapping public Object list(Principal principal) {
        return workspaces.available(principal).stream().map(w->Map.of("key",w.key(),"label",w.label(),"canWrite",workspaces.permitted(w,principal,true))).toList();
    }
    @GetMapping("/{key}/conversation/{id}") public Object conversation(@PathVariable String key,@PathVariable UUID id,
            @RequestParam org.springframework.util.MultiValueMap<String,String> params,Principal principal) {
        workspaces.requireConversation(key,id,principal,false);
        var scoped=new org.springframework.util.LinkedMultiValueMap<String,String>(params);
        scoped.set("conversation",id.toString());
        return read(key,Objects.toString(params.getFirst("q"),""),0,scoped,principal);
    }
    @GetMapping("/{key}") public Object read(@PathVariable String key,@RequestParam(defaultValue="") String q,
            @RequestParam(defaultValue="0") int offset,
            @RequestParam org.springframework.util.MultiValueMap<String,String> params, Principal principal) {
        var workspace=workspaces.requireWorkspace(key,principal,false);
        if(offset<0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        int limit=100;
        try {
            if(params.containsKey("cursor"))offset=Integer.parseInt(params.getFirst("cursor"));
            if(params.containsKey("limit"))limit=Math.max(1,Math.min(500,Integer.parseInt(params.getFirst("limit"))));
        } catch(NumberFormatException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid page cursor or limit");}
        if(offset<0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid page cursor");
        var spec=workspace.listSpec();
        if(states.source().isEmpty())spec.hide(Conversation::getStatus);
        var declared=spec.filters().stream().filter(f->!f.field().equals("status") || !states.source().isEmpty()).filter(f->!f.field().equals("priority") || !priorities.choices().source().isEmpty()).toList();
        var filters = CrmInboxFilters.predicate(declared, params);
        String search=q.strip().toLowerCase(Locale.ROOT);
        var members=workspaces.members(workspace);
        if(params.containsKey("conversation")) {
            UUID requested;
            try {requested=UUID.fromString(params.getFirst("conversation"));}catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid conversation");}
            var target=workspaces.requireConversation(key,requested,principal,false);
            members=members.stream().filter(c->target.getCustomer()==null?c.getId().equals(target.getId()):target.getCustomer().equals(c.getCustomer())).toList();
        }
        // The list host uses ids for live row patches, including read acknowledgements.
        // Intersect with workspace membership; never expand the authorized scope.
        if(params.containsKey("ids")) {
            var requested=new HashSet<UUID>();
            try {
                for(String value:params.get("ids"))
                    for(String id:value.split(",",-1)) requested.add(UUID.fromString(id.strip()));
            } catch(IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid conversation ids");
            }
            if(requested.size()>500)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Too many conversation ids");
            members=members.stream().filter(c->requested.contains(c.getId())).toList();
        }
        var descriptor=catalogs.forClass(Conversation.class);
        java.util.function.Function<Conversation,Map<String,Object>> decorate = conversation -> {
            var raw=catalogs.get(descriptor,conversation.getId());
            Map<String,Object> row=new LinkedHashMap<>();
            row.put("id",conversation.getId());row.put("description",conversation.getDescription());
            for(var attribute:descriptor.attributes()) {
                if(attribute.secret())continue;
                row.put(attribute.fieldName(),raw.get(attribute.columnName()));
                for(String suffix:List.of("display","color","ref")) {
                    Object value=raw.get(attribute.columnName()+"_"+suffix);
                    if(value!=null)row.put(attribute.fieldName()+Character.toUpperCase(suffix.charAt(0))+suffix.substring(1),value);
                }
            }
            if(conversation.getStatus()!=null) states.conversationStatuses().stream()
                .filter(s->s.choice().id().equals(conversation.getStatus())).findFirst().ifPresent(s->{
                    row.put("statusDisplay",s.choice().label());row.put("statusColor",s.choice().color());
                });
            if(conversation.getPriority()!=null) priorities.choices().conversationStatuses().stream()
                .filter(s->s.choice().id().equals(conversation.getPriority())).findFirst().ifPresent(s->{
                    row.put("priorityDisplay",s.choice().label());row.put("priorityColor",s.choice().color());
                });
            var contact=contacts.get(conversation.getCustomer(),principal);
            row.put("customerDisplay",contact.fields().get("description"));
            row.put("customerRef",Map.of("type",contact.catalogName(),"display",Objects.toString(contact.fields().get("description"),""),"id",contact.fields().get("id")));
            row.put("customerAvatar",contact.fields().getOrDefault("avatarUrl",contact.identities().stream()
                    .map(su.onno.crm.domain.ContactIdentity::getAvatarUrl).filter(Objects::nonNull).findFirst().orElse("")));
            var agent=agents.getIfAvailable();
            if(agent!=null && conversation.getAssignee()!=null && access.canRead(principal,"catalog",agent.catalog().name())
                    && agent.catalog().canRead(conversation.getAssignee(),principal)) {
                row.put("assigneeDisplay",agent.catalog().fields(conversation.getAssignee()).get("description"));
            } else { row.remove("assignee"); }
            return row;
        };
        // Authorization still precedes counting, filtering, pagination and decoration.
        var authorized=members.stream().filter(c->workspaces.canAccess(c,principal,false)).toList();
        String requestedSort=params.containsKey("sort")?params.getFirst("sort"):spec.sortField();
        boolean pageBeforeDecoration=CrmInboxRows.canPageBeforeDecoration(search,requestedSort,params);
        var byId=new HashMap<UUID,Conversation>();
        authorized.forEach(c->byId.put(c.getId(),c));
        var records=authorized.stream().map(pageBeforeDecoration?CrmInboxRows::raw:decorate)
            .filter(filters).filter(row->search.isEmpty()||(Objects.toString(row.get("customerDisplay"),"")+" "+Objects.toString(row.get("subject"),"")+" "+Objects.toString(row.get("lastMessagePreview"),"")).toLowerCase(Locale.ROOT).contains(search)).toList();
        String sort=params.getFirst("sort");
        if(sort==null)sort=spec.sortField();
        final String sortField=sort;
        if(sort!=null) {
            if(!Set.of("customer","customerDisplay","channel","subject","status","priority","assignee","assigneeDisplay","lastMessageAt","unreadCount").contains(sort))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown inbox sort");
            Comparator<Map<String,Object>> comparator=(left,right)-> {
                Object a=left.get(sortField),b=right.get(sortField);
                if(a==null)return b==null?0:1;
                if(b==null)return -1;
                if(a instanceof Number x && b instanceof Number y)return Double.compare(x.doubleValue(),y.doubleValue());
                return a.toString().compareToIgnoreCase(b.toString());
            };
            if((params.containsKey("dir") ? "desc".equals(params.getFirst("dir")) : spec.sortDescending()))comparator=comparator.reversed();
            records=records.stream().sorted(comparator.thenComparing(r->r.get("id").toString())).toList();
        }
        boolean canWrite=workspaces.permitted(workspace,principal,true);
        var config=workspaces.config(workspace);
        config=config.withActions(config.actions().stream().map(a->new CrmWorkspaceService.Action(a.key(),a.label(),
            a.visible()&&!a.key().equals("history")&&(canWrite||Set.of("reply","note","details").contains(a.key())))).toList());
        int end=(int)Math.min(records.size(),(long)offset+limit);
        var page=records.subList(Math.min(offset,records.size()),end);
        if(pageBeforeDecoration)page=page.stream().map(row->decorate.apply(byId.get((UUID)row.get("id")))).toList();
        var resolved=listViews.catalogList(descriptor,spec);
        Map<String,String> fieldNames=new HashMap<>();
        descriptor.attributes().forEach(a->fieldNames.put(a.columnName(),a.fieldName()));
        var list=new LinkedHashMap<String,Object>();
        list.put("title",resolved.title());list.put("searchable",resolved.searchable());
        list.put("selectionCheckboxes",resolved.selectionCheckboxes());list.put("pageSize",resolved.pageSize());
        list.put("sort",Map.of("column",Objects.toString(spec.sortField(),""),"descending",spec.sortDescending()));
        list.put("columns",resolved.columns().stream().filter(c->!c.fieldName().equals("status") || !states.source().isEmpty())
            .filter(c->!c.fieldName().equals("priority") || !priorities.choices().source().isEmpty())
            .map(c->new ResolvedListView.Column(c.label(),c.fieldName(),c.fieldName(),c.width(),c.widget(),c.format(),c.hint(),c.cellMenu())).toList());
        list.put("filters",resolved.filters().stream().filter(f->declared.stream().anyMatch(d->d.field().equals(f.key())))
            .map(f->Map.of("key",f.key(),"label",Objects.toString(f.label(),f.key()),"column",fieldNames.getOrDefault(f.columnName(),f.columnName()),"type",f.type(),"options",f.options())).toList());
        list.put("custom",resolved.customView());
        return Map.of("list",list,"key",workspace.key(),"label",workspace.label(),"filters",declared,"config",config,"canWrite",canWrite,
            "rows",page,"total",records.size(),"hasMore",end<records.size(),"nextCursor",end<records.size()?Integer.toString(end):"");
    }
    @GetMapping("/{key}/conversations/{id}/comments") public Object notes(@PathVariable String key,@PathVariable UUID id,Principal principal) {
        workspaces.requireConversation(key,id,principal,false);
        var me=users.resolve(principal);
        return comments.list("catalogs","crm_conversations",id).stream().map(c->note(c,me)).toList();
    }
    public record NoteRequest(String body){}
    @PostMapping("/{key}/conversations/{id}/comments") public Object note(@PathVariable String key,@PathVariable UUID id,
            @RequestBody NoteRequest request,Principal principal) {
        workspaces.requireConversation(key,id,principal,true);
        if(request==null||request.body()==null||request.body().isBlank()||request.body().length()>8000)
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Note must contain 1–8000 characters");
        var me=users.resolve(principal);
        var saved=comments.add("catalogs","crm_conversations",id,me.recordId(),me.displayName(),request.body().strip());
        events.publishEvent(new EntityChangedEvent(EntityChangedEvent.CREATED,"comment","crm_conversations",id,null));
        return note(saved,me);
    }
    private Map<String,Object> note(Comment comment,CurrentUserResolver.CurrentUser me) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("id",comment.id());result.put("authorName",comment.authorName());result.put("body",comment.body());
        result.put("createdAt",comment.createdAt());result.put("authorAvatarUrl",comment.authorId()==null?null:avatars.avatarFor(comment.authorId()));
        result.put("mine",me.recordId()!=null&&me.recordId().equals(comment.authorId()));return result;
    }
}
