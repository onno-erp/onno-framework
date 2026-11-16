package su.onno.crm.web;

import java.security.Principal;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import su.onno.crm.domain.Conversation;
import su.onno.crm.service.*;
import su.onno.web.*;
import su.onno.web.comments.*;

/** Scoped inbox reads; generic conversation exports remain unavailable to non-admin workspace users. */
@RestController
@RequestMapping("/api/crm/inbox-workspaces")
public class CrmInboxWorkspaceController {
    private final CrmInboxWorkspaceService workspaces;
    private final CatalogQueryService catalogs;
    private final CurrentUserResolver users;
    private final CommentService comments;
    private final CommentAuthorAvatars avatars;
    private final AccessSubjectResolver subjects;
    @org.springframework.beans.factory.annotation.Autowired private CrmFeedQuery feeds;
    private final CrmContactService contacts;
    private final org.springframework.beans.factory.ObjectProvider<CrmAgentBinding<?>> agents;
    private final AccessService access;
    @org.springframework.beans.factory.annotation.Autowired private DefaultListViews listViews;
    @org.springframework.beans.factory.annotation.Autowired private CrmStateConfiguration states;
    @org.springframework.beans.factory.annotation.Autowired private CrmPriorityBinding priorities;
    public CrmInboxWorkspaceController(CrmInboxWorkspaceService workspaces,CatalogQueryService catalogs,
            CurrentUserResolver users,CommentService comments,CommentAuthorAvatars avatars,
            AccessSubjectResolver subjects,CrmContactService contacts,
            org.springframework.beans.factory.ObjectProvider<CrmAgentBinding<?>> agents,AccessService access) {
        this.workspaces=workspaces;this.catalogs=catalogs;this.users=users;this.comments=comments;this.avatars=avatars;this.subjects=subjects;this.contacts=contacts;this.agents=agents;this.access=access;
    }
    /**
     * The workspaces this principal may use; with {@code conversation}, only those holding that
     * conversation (a conversation link opens in the first). A conversation outside every workspace
     * the principal may use is forbidden, never an empty answer.
     */
    @GetMapping public Object list(@RequestParam(required=false) UUID conversation,Principal principal) {
        var available=workspaces.available(principal);
        if(conversation!=null) {
            var target=workspaces.requireConversation(conversation,principal,false);
            available=available.stream().filter(w->w.selection().test(target)).toList();
        }
        return available.stream().limit(100).map(w->Map.of("key",w.key(),"label",w.label(),"canWrite",workspaces.permitted(w,principal,true))).toList();
    }
    @GetMapping("/{key}/conversation/{id}") public Object conversation(@PathVariable String key,@PathVariable UUID id,
            @RequestParam org.springframework.util.MultiValueMap<String,String> params,Principal principal) {
        workspaces.requireConversation(key,id,principal,false);
        var scoped=new org.springframework.util.LinkedMultiValueMap<>(params);
        scoped.set("conversation",id.toString());
        return read(key,Objects.toString(params.getFirst("q"),""),scoped,principal);
    }
    @GetMapping("/{key}") public Object read(@PathVariable String key,@RequestParam(defaultValue="") String q,
            @RequestParam org.springframework.util.MultiValueMap<String,String> params, Principal principal) {
        var workspace=workspaces.requireWorkspace(key,principal,false);
        int limit=100;
        try {
            String requestedLimit = params.getFirst("limit");
            if (requestedLimit != null) limit=su.onno.query.KeysetWindow.clampLimit(Integer.parseInt(requestedLimit));
        } catch(NumberFormatException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid limit");}
        final int pageLimit=limit;
        String cursor=params.getFirst("cursor");
        var descriptor=catalogs.forClass(Conversation.class);
        // Facet options are evaluated once per read. A facet with no options right now stays a valid
        // filter key (a stale link matches nothing rather than failing) but is not offered or chipped.
        var facetOptions=new LinkedHashMap<String,List<CrmInboxWorkspace.FacetOption>>();
        workspace.facets().forEach(f->facetOptions.put(f.key(),f.options()));
        var offeredFacets=workspace.facets().stream().filter(f->!facetOptions.get(f.key()).isEmpty()).toList();
        var filters = CrmInboxFilters.predicate(CrmInboxFilters.fields(descriptor,facetOptions.keySet()), params);
        String search=q.strip().toLowerCase(Locale.ROOT);
        Conversation target=params.containsKey("conversation")
                ? workspaces.requireConversation(key,uuid(params.getFirst("conversation")),principal,false) : null;
        // The list host uses ids for live row patches, including read acknowledgements.
        // Intersect with workspace membership; never expand the authorized scope.
        var requested=new HashSet<UUID>();
        for(String value:params.getOrDefault("ids",List.of()))
            for(String id:value.split(",",-1)) requested.add(uuid(id.strip()));
        if(requested.size()>500)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Too many conversation ids");
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
        // Without a sort the feed keeps the workspace's order: the latest message first.
        String requestedSort=params.getFirst("sort");
        boolean descending=params.containsKey("dir") ? "desc".equals(params.getFirst("dir")) : requestedSort==null;
        boolean pageBeforeDecoration=CrmInboxRows.canPageBeforeDecoration(search,requestedSort,params);
        if(requestedSort==null)requestedSort="lastMessageAt";
        if(!Set.of("customer","customerDisplay","channel","subject","status","priority","assignee","assigneeDisplay","lastMessageAt","unreadCount").contains(requestedSort))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown inbox sort");
        final String sortField=requestedSort;
        var accountSamples=new LinkedHashMap<UUID,Conversation>();
        // Contacts are fetched for whatever is about to be decorated, in a few queries for the lot.
        // Searching and sorting on a decorated field still decorates everything, so it prefetches
        // everything; the ordinary path prefetches only the page, further down.
        java.util.function.Consumer<java.util.Collection<Conversation>> prefetchContacts = batch -> {
            var customers=batch.stream().map(Conversation::getCustomer).filter(Objects::nonNull)
                .filter(id->!rowContacts.containsKey(id)).distinct().toList();
            // Nothing left to decorate means nothing to ask: a page whose contacts are already
            // resolved — and an empty one — must not touch contacts at all.
            if(!customers.isEmpty()) rowContacts.putAll(contacts.summaries(customers,principal));
        };
        // Context excludes transport position/size/count but includes every effective filter and identity.
        var contextParams=new TreeMap<String,List<String>>();
        params.forEach((name,values)->{if(!Set.of("cursor","limit","count","q","sort","dir").contains(name))contextParams.put(name,List.copyOf(values));});
        String context="crm-inbox:"+key+":"+principal.getName()+":"+new TreeSet<>(access.roles(principal))
                +":"+search+":"+sortField+":"+contextParams;
        var result=feeds.inbox(sortField,descending,cursor,pageLimit,context,
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
        if(!canWrite){
            var readable=new LinkedHashSet<>(config.actions());
            readable.retainAll(Set.of("reply","note","details"));
            config=config.withActions(readable);
        }
        // Scope before publishing account choices; filters and pagination must not hide other accounts.
        // One sample per distinct account keeps the choices complete without decorating every
        // authorized conversation, which would defeat the page-before-decoration fast path.
        prefetchContacts.accept(accountSamples.values());
        var channelAccounts=accountSamples.values().stream().map(decorate)
            .filter(row->row.get("inbox")!=null)
            .map(row->Map.of("id",row.get("inbox").toString(),"channel",Objects.toString(row.get("channel"),""),
                "label",Objects.toString(row.get("inboxDisplay"),row.get("inbox").toString())))
            .distinct().toList();
        Map<String,Object> response=new LinkedHashMap<>();
        response.put("list",defaultList(descriptor,offeredFacets,facetOptions));response.put("channelAccounts",channelAccounts);
        response.put("key",workspace.key());response.put("label",workspace.label());
        response.put("config",config);response.put("canWrite",canWrite);
        response.put("rows",page);response.put("nextCursor",result.page().nextCursor());response.put("hasMore",result.page().hasMore());
        if(result.total()!=null)response.put("total",result.total());
        return response;
    }
    /**
     * The conversation list's default descriptor, as for any catalog: its columns and filters, with
     * the feed's field names as data columns (the feed sorts and filters by field), the status and
     * priority columns only where a host binding gives them values, and the bound states', priorities'
     * and offered facets' choices as multi-select filters. The client decides what it shows.
     */
    private Map<String,Object> defaultList(su.onno.metadata.CatalogDescriptor descriptor,List<CrmInboxWorkspace.Facet> facets,
            Map<String,List<CrmInboxWorkspace.FacetOption>> facetOptions) {
        boolean statuses=!states.source().isEmpty(), priority=!priorities.choices().source().isEmpty();
        var resolved=listViews.catalogList(descriptor);
        var columns=resolved.columns().stream()
            .filter(c->statuses || !c.fieldName().equals("status")).filter(c->priority || !c.fieldName().equals("priority"))
            .map(c->new DefaultListView.Column(c.label(),c.fieldName(),c.fieldName(),c.width(),c.widget(),c.format(),c.hint(),c.type())).toList();
        var filters=new ArrayList<DefaultListView.Filter>();
        java.util.function.BiFunction<String,CrmStateConfiguration,DefaultListView.Filter> choices=(field,source)->new DefaultListView.Filter(field,
            descriptor.attributes().stream().filter(a->a.fieldName().equals(field)).map(a->a.displayName()).findFirst().orElse(field),field,"multiOptions",
            source.conversationStatuses().stream().map(s->new DefaultListView.Option(s.choice().id().toString(),s.choice().label(),s.choice().color(),"")).toList());
        if(statuses)filters.add(choices.apply("status",states));
        if(priority)filters.add(choices.apply("priority",priorities.choices()));
        facets.forEach(f->filters.add(new DefaultListView.Filter(f.key(),f.label(),f.key(),"multiOptions",
            facetOptions.get(f.key()).stream().map(o->new DefaultListView.Option(o.value(),o.label(),o.color(),"")).toList())));
        return ViewDescriptors.listDescriptor(new DefaultListView(resolved.title(),columns,filters,resolved.pageSize()),
            "catalogs","crm_conversations",null,false,List.of());
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
        try {
            var saved = comments.add("catalogs", "crm_conversations", id, me, request.body(), null,
                    subjects.resolve(principal));
            return note(saved, me);
        } catch (CommentService.InvalidCommentException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage(), e);
        }
    }
    private Map<String,Object> note(Comment comment,CurrentUserResolver.CurrentUser me) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("id",comment.id());result.put("authorName",comment.authorName());result.put("body",comment.body());
        result.put("createdAt",comment.createdAt());result.put("authorAvatarUrl",comment.authorId()==null?null:avatars.avatarFor(comment.authorId()));
        result.put("mine",me.recordId()!=null&&me.recordId().equals(comment.authorId()));return result;
    }
}
