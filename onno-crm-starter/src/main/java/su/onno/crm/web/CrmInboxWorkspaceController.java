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
    @org.springframework.beans.factory.annotation.Autowired private CrmFeedQuery feeds;
    @org.springframework.beans.factory.annotation.Autowired private UiViewResolver listViews;
    @org.springframework.beans.factory.annotation.Autowired private CrmStateConfiguration states;
    @org.springframework.beans.factory.annotation.Autowired private CrmPriorityBinding priorities;
    public CrmInboxWorkspaceController(CrmInboxWorkspaceService workspaces,CatalogQueryService catalogs,
            CurrentUserResolver users,CommentService comments,CommentAuthorAvatars avatars,ApplicationEventPublisher events,CrmContactService contacts,
            org.springframework.beans.factory.ObjectProvider<CrmAgentBinding<?>> agents,UiAccessService access) {
        this.workspaces=workspaces;this.catalogs=catalogs;this.users=users;this.comments=comments;this.avatars=avatars;this.events=events;this.contacts=contacts;this.agents=agents;this.access=access;
    }
    @GetMapping public Object list(Principal principal) {
        return workspaces.available(principal).stream().limit(100).map(w->Map.of("key",w.key(),"label",w.label(),"canWrite",workspaces.permitted(w,principal,true))).toList();
    }
    @GetMapping("/{key}/conversation/{id}") public Object conversation(@PathVariable String key,@PathVariable UUID id,
            @RequestParam org.springframework.util.MultiValueMap<String,String> params,Principal principal) {
        workspaces.requireConversation(key,id,principal,false);
        var scoped=new org.springframework.util.LinkedMultiValueMap<>(params);
        scoped.set("conversation",id.toString());
        return read(key,Objects.toString(params.getFirst("q"),""),0,scoped,principal);
    }
    @GetMapping("/{key}") public Object read(@PathVariable String key,@RequestParam(defaultValue="") String q,
            @RequestParam(defaultValue="0") int offset,
            @RequestParam org.springframework.util.MultiValueMap<String,String> params, Principal principal) {
        var workspace=workspaces.requireWorkspace(key,principal,false);
        if(offset<0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"offset must be non-negative");
        int limit=100;
        try {
            String requestedLimit = params.getFirst("limit");
            if (requestedLimit != null) limit=su.onno.query.KeysetWindow.clampLimit(Integer.parseInt(requestedLimit));
        } catch(NumberFormatException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid limit");}
        final int pageLimit=limit;
        String cursor=params.getFirst("cursor");
        var spec=workspace.listSpec();
        if(states.source().isEmpty())spec.hide(Conversation::getStatus);
        // Facet options are evaluated once per read. A facet with no options right now stays a valid
        // filter key (a stale link matches nothing rather than failing) but is not offered or chipped.
        var facetOptions=new LinkedHashMap<String,List<CrmInboxWorkspace.FacetOption>>();
        workspace.facets().forEach(f->facetOptions.put(f.key(),f.options()));
        var offeredFacets=workspace.facets().stream().filter(f->!facetOptions.get(f.key()).isEmpty()).toList();
        var declared=java.util.stream.Stream.concat(spec.filters().stream(),workspace.facets().stream().map(f->f.definition(facetOptions.get(f.key())))).filter(f->!f.field().equals("status") || !states.source().isEmpty()).filter(f->!f.field().equals("priority") || !priorities.choices().source().isEmpty()).toList();
        var filters = CrmInboxFilters.predicate(declared, params);
        String search=q.strip().toLowerCase(Locale.ROOT);
        Conversation target=params.containsKey("conversation")
                ? workspaces.requireConversation(key,uuid(params.getFirst("conversation")),principal,false) : null;
        var requested=new HashSet<UUID>();
        for(String value:params.getOrDefault("ids",List.of()))
            for(String id:value.split(",",-1)) requested.add(uuid(id.strip()));
        if(requested.size()>500)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Too many conversation ids");
        var descriptor=catalogs.forClass(Conversation.class);
        // Filled in below, once the page is known: decoration reads from it rather than querying.
        final Map<UUID,su.onno.crm.service.CrmContactService.Summary> rowContacts=new HashMap<>();
        java.util.function.Function<Conversation,Map<String,Object>> decorate = conversation -> {
            // Membership is already authorized by the workspace (and customers by their policy); read
            // the conversation row as trusted code.
            var raw=catalogs.get(su.onno.access.AccessSubject.system(),descriptor,conversation.getId());
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
            // Resolved for the page, not the row: the full contact read fetches identities, every
            // conversation the customer has and a write check, none of which a row shows.
            var contact=rowContacts.get(conversation.getCustomer());
            if(contact!=null) {
                row.put("customerDisplay",contact.fields().get("description"));
                row.put("customerRef",Map.of("type",contact.catalogName(),"display",Objects.toString(contact.fields().get("description"),""),"id",contact.fields().get("id")));
                row.put("customerAvatar",contact.avatarUrl());
            }
            var agent=agents.getIfAvailable();
            if(agent!=null && conversation.getAssignee()!=null && access.canRead(principal,"catalog",agent.catalog().name())
                    && agent.catalog().canRead(conversation.getAssignee(),principal)) {
                row.put("assigneeDisplay",agent.catalog().fields(conversation.getAssignee()).get("description"));
            } else { row.remove("assignee"); }
            return row;
        };
        var facetValues=new LinkedHashMap<String,Map<UUID,List<String>>>();
        // Row chips for facets that opt in: the option's label and colour, in option order.
        var rowFacets=offeredFacets.stream().filter(CrmInboxWorkspace.Facet::showOnRows).toList();
        java.util.function.Function<Map<String,Object>,Map<String,Object>> rowBadges=row->{
            if(rowFacets.isEmpty()) return row;
            var chips=new ArrayList<Map<String,String>>();
            for(var facet:rowFacets) {
                var values=facetValues.getOrDefault(facet.key(),Map.of()).getOrDefault((UUID)row.get("id"),List.of());
                for(var option:facetOptions.get(facet.key())) if(values.contains(option.value()))
                    chips.add(Map.of("label",option.label(),"color",Objects.toString(option.color(),"")));
            }
            row.put("rowBadges",chips);
            return row;
        };
        String requestedSort=params.containsKey("sort")?params.getFirst("sort"):spec.sortField();
        boolean pageBeforeDecoration=CrmInboxRows.canPageBeforeDecoration(search,requestedSort,params);
        if(requestedSort==null)requestedSort="lastMessageAt";
        if(!Set.of("customer","customerDisplay","channel","subject","status","priority","assignee","assigneeDisplay","lastMessageAt","unreadCount").contains(requestedSort))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown inbox sort");
        final String sortField=requestedSort;
        boolean descending=params.containsKey("dir") ? "desc".equals(params.getFirst("dir"))
                : spec.sortField()==null || spec.sortDescending();
        var accountSamples=new LinkedHashMap<UUID,Conversation>();
        java.util.function.Consumer<java.util.Collection<Conversation>> prefetchContacts = batch -> {
            var customers=batch.stream().map(Conversation::getCustomer).filter(Objects::nonNull)
                .filter(id->!rowContacts.containsKey(id)).distinct().toList();
            if(!customers.isEmpty()) rowContacts.putAll(contacts.summaries(customers,principal));
        };
        // Context excludes transport position/size/count but includes every effective filter and identity.
        var contextParams=new TreeMap<String,List<String>>();
        params.forEach((name,values)->{if(!Set.of("cursor","offset","limit","count","q","sort","dir").contains(name))contextParams.put(name,List.copyOf(values));});
        String context="crm-inbox:"+key+":"+principal.getName()+":"+new TreeSet<>(access.roles(principal))
                +":"+search+":"+sortField+":"+contextParams;
        var result=feeds.inbox(sortField,descending,cursor,pageLimit,offset,context,
                "exact".equals(params.getFirst("count")),(handle,sink)->feeds.scan(handle,batch->{
            var candidates=batch.stream().filter(workspace.selection())
                .filter(c->target==null || (target.getCustomer()==null ? Objects.equals(c.getId(),target.getId()) : target.getCustomer().equals(c.getCustomer())))
                .filter(c->!params.containsKey("ids") || requested.contains(c.getId())).toList();
            var authorized=workspaces.accessible(candidates,principal,false);
            for(var conversation:authorized)
                if(conversation.getInbox()!=null && accountSamples.size()<100)
                    accountSamples.putIfAbsent(conversation.getInbox().id(),conversation);
            facetValues.clear();
            for(var facet:workspace.facets()) {
                if((facet.showOnRows() && offeredFacets.contains(facet)) || params.getOrDefault("in",List.of()).stream().anyMatch(v->v.startsWith(facet.key()+",")))
                    facetValues.put(facet.key(),facet.resolver().resolve(authorized,principal));
            }
            rowContacts.clear();
            if(!pageBeforeDecoration)prefetchContacts.accept(authorized);
            for(var conversation:authorized) {
                var row=pageBeforeDecoration?CrmInboxRows.raw(conversation):decorate.apply(conversation);
                facetValues.forEach((field,values)->row.put(field,values.getOrDefault(conversation.getId(),List.of())));
                if(filters.test(row) && (search.isEmpty() || (Objects.toString(row.get("customerDisplay"),"")+" "
                        +Objects.toString(row.get("subject"),"")+" "+Objects.toString(row.get("lastMessagePreview"),"")).toLowerCase(Locale.ROOT).contains(search)))
                    sink.add(conversation.getId(),row.get(sortField));
            }
        }));
        var onPage=result.page().rows().stream().map(id->workspaces.requireConversation(key,id,principal,false)).toList();
        rowContacts.clear();prefetchContacts.accept(onPage);
        facetValues.clear();
        for(var facet:workspace.facets()) if((facet.showOnRows() && offeredFacets.contains(facet))
                || params.getOrDefault("in",List.of()).stream().anyMatch(v->v.startsWith(facet.key()+",")))
            facetValues.put(facet.key(),facet.resolver().resolve(onPage,principal));
        var page=onPage.stream().map(conversation->{
            var row=decorate.apply(conversation);
            facetValues.forEach((field,values)->row.put(field,values.getOrDefault((UUID)row.get("id"),List.of())));
            return rowBadges.apply(row);
        }).toList();
        boolean canWrite=workspaces.permitted(workspace,principal,true);
        var config=workspaces.config(workspace);
        config=config.withActions(config.actions().stream().map(a->new CrmWorkspaceService.Action(a.key(),a.label(),
            a.visible()&&!a.key().equals("history")&&(canWrite||Set.of("reply","note","details").contains(a.key())))).toList());
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
        var filterMetadata=new ArrayList<Map<String,Object>>();
        resolved.filters().stream().filter(f->declared.stream().anyMatch(d->d.field().equals(f.key()))).forEach(f->{
            var options=f.options().stream().map(o->{
                String color=o.color();
                if(f.key().equals("status")) color=states.conversationStatuses().stream().filter(state->state.choice().id().toString().equals(o.value())).map(state->state.choice().color()).findFirst().orElse(Objects.toString(color,""));
                return new ResolvedListView.Option(o.value(),o.label(),color,o.avatarUrl());
            }).toList();
            filterMetadata.add(Map.of("key",f.key(),"label",Objects.toString(f.label(),f.key()),"column",fieldNames.getOrDefault(f.columnName(),f.columnName()),"type",f.type(),"options",options));
        });
        offeredFacets.forEach(f->filterMetadata.add(Map.of("key",f.key(),"label",f.label(),"column",f.key(),"type","multiOptions","options",facetOptions.get(f.key()))));
        list.put("filters",filterMetadata);
        list.put("custom",resolved.customView());
        // Scope before publishing account choices; filters and pagination must not hide other accounts.
        // One sample per distinct account keeps the choices complete without decorating every
        // authorized conversation, which would defeat the page-before-decoration fast path.
        prefetchContacts.accept(accountSamples.values());
        list.put("channelAccounts",accountSamples.values().stream().map(decorate)
            .filter(row->row.get("inbox")!=null)
            .map(row->Map.of("id",row.get("inbox").toString(),"channel",Objects.toString(row.get("channel"),""),
                "label",Objects.toString(row.get("inboxDisplay"),row.get("inbox").toString())))
            .distinct().toList());
        var offeredFilters=declared.stream().filter(f->!facetOptions.containsKey(f.field()) || !facetOptions.get(f.field()).isEmpty()).toList();
        Map<String,Object> response=new LinkedHashMap<>();
        response.put("list",list);response.put("key",workspace.key());response.put("label",workspace.label());
        response.put("filters",offeredFilters);response.put("config",config);response.put("canWrite",canWrite);
        response.put("rows",page);response.put("nextCursor",result.page().nextCursor());response.put("hasMore",result.page().hasMore());
        if(result.total()!=null)response.put("total",result.total());
        return response;
    }
    private static UUID uuid(String value) {
        try {return UUID.fromString(value);}catch(IllegalArgumentException | NullPointerException e){
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid conversation id");}
    }
    @GetMapping("/{key}/conversations/{id}/comments") public Object notes(@PathVariable String key,@PathVariable UUID id,
            @RequestParam(required=false) String cursor,@RequestParam(defaultValue="100") int limit,Principal principal) {
        workspaces.requireConversation(key,id,principal,false);
        var me=users.resolve(principal);
        var page=comments.page("catalogs","crm_conversations",id,cursor,limit,
                "crm-notes:"+key+":"+principal.getName()+":"+new TreeSet<>(access.roles(principal)));
        return KeysetWindow.envelope(page.map(c->note(c,me)));
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
