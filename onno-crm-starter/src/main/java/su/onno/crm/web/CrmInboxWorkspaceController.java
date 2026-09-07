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
    public CrmInboxWorkspaceController(CrmInboxWorkspaceService workspaces,CatalogQueryService catalogs,
            CurrentUserResolver users,CommentService comments,CommentAuthorAvatars avatars,ApplicationEventPublisher events) {
        this.workspaces=workspaces;this.catalogs=catalogs;this.users=users;this.comments=comments;this.avatars=avatars;this.events=events;
    }
    @GetMapping public Object list(Principal principal) {
        return workspaces.available(principal).stream().map(w->Map.of("key",w.key(),"label",w.label(),"canWrite",workspaces.permitted(w,principal,true))).toList();
    }
    @GetMapping("/{key}/conversation/{id}") public Object conversation(@PathVariable String key,@PathVariable UUID id,
            @RequestParam org.springframework.util.MultiValueMap<String,String> params,Principal principal) {
        workspaces.requireConversation(key,id,principal,false);
        var scoped=new org.springframework.util.LinkedMultiValueMap<String,String>(params);
        scoped.set("conversation",id.toString());
        return read(key,Objects.toString(params.getFirst("q"),""),0,Objects.toString(params.getFirst("status"),"all"),Objects.toString(params.getFirst("channel"),"all"),Objects.toString(params.getFirst("priority"),"all"),scoped,principal);
    }
    @GetMapping("/{key}") public Object read(@PathVariable String key,@RequestParam(defaultValue="") String q,
            @RequestParam(defaultValue="0") int offset,
            @RequestParam(defaultValue="all") String status, @RequestParam(defaultValue="all") String channel,
            @RequestParam(defaultValue="all") String priority,
            @RequestParam org.springframework.util.MultiValueMap<String,String> params, Principal principal) {
        var workspace=workspaces.requireWorkspace(key,principal,false);
        if(offset<0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        int limit=100;
        try {
            if(params.containsKey("cursor"))offset=Integer.parseInt(params.getFirst("cursor"));
            if(params.containsKey("limit"))limit=Math.max(1,Math.min(500,Integer.parseInt(params.getFirst("limit"))));
        } catch(NumberFormatException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid page cursor or limit");}
        if(offset<0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid page cursor");
        var selections=new HashMap<String,Set<String>>();
        for(String expression:params.getOrDefault("in",List.of())) {
            String[] parts=expression.split(",",2);
            if(parts.length!=2 || !Set.of("status","channel","priority").contains(parts[0]))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown inbox filter");
            selections.computeIfAbsent(parts[0],ignored->new HashSet<>()).add(parts[1]);
        }
        String search=q.strip().toLowerCase(Locale.ROOT);
        var members=workspaces.members(workspace);
        if(params.containsKey("conversation")) {
            UUID requested;
            try {requested=UUID.fromString(params.getFirst("conversation"));}catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid conversation");}
            var target=workspaces.requireConversation(key,requested,principal,false);
            members=members.stream().filter(c->target.getCustomer()==null?c.getId().equals(target.getId()):target.getCustomer().equals(c.getCustomer())).toList();
        }
        var descriptor=catalogs.forClass(Conversation.class);
        // Authoritative membership is checked before even resolving row references.
        var records=members.stream()
            .filter(c -> "all".equals(status) || Arrays.stream(status.split(",")).map(CrmWorkspaceService::statusId).anyMatch(id -> c.getStatus()!=null && id.equals(c.getStatus().id())))
            .filter(c -> "all".equals(channel) || Arrays.asList(channel.split(",")).contains(Objects.toString(c.getChannel(), "")))
            .filter(c -> "all".equals(priority) || Arrays.asList(priority.split(",")).contains(Objects.toString(c.getPriority(), "")))
            .filter(c -> !selections.containsKey("status") || selections.get("status").stream().map(CrmWorkspaceService::statusId).anyMatch(id -> c.getStatus()!=null && id.equals(c.getStatus().id())))
            .filter(c -> !selections.containsKey("channel") || selections.get("channel").contains(Objects.toString(c.getChannel(),"")))
            .filter(c -> !selections.containsKey("priority") || selections.get("priority").contains(Objects.toString(c.getPriority(),"")))
            .map(conversation->{
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
            // Avatar resolution uses the same customer record that backs the contact pane.
            if(conversation.getCustomer()!=null) {
                var customer=catalogs.get(catalogs.require("CrmCustomers"),conversation.getCustomer().id());
                row.put("customerAvatar",customer.get("avatar_url"));
            }
            return row;
        }).filter(row->search.isEmpty()||(Objects.toString(row.get("customerDisplay"),"")+" "+Objects.toString(row.get("subject"),"")+" "+Objects.toString(row.get("lastMessagePreview"),"")).toLowerCase(Locale.ROOT).contains(search)).toList();
        String sort=params.getFirst("sort");
        if(sort!=null) {
            if(!Set.of("customerDisplay","channel","subject","status","priority","assigneeDisplay","lastMessageAt","unreadCount").contains(sort))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown inbox sort");
            Comparator<Map<String,Object>> comparator=(left,right)-> {
                Object a=left.get(sort),b=right.get(sort);
                if(a==null)return b==null?0:1;
                if(b==null)return -1;
                if(a instanceof Number x && b instanceof Number y)return Double.compare(x.doubleValue(),y.doubleValue());
                return a.toString().compareToIgnoreCase(b.toString());
            };
            if("desc".equals(params.getFirst("dir")))comparator=comparator.reversed();
            records=records.stream().sorted(comparator.thenComparing(r->r.get("id").toString())).toList();
        }
        boolean canWrite=workspaces.permitted(workspace,principal,true);
        var config=workspaces.config(workspace);
        config=config.withActions(config.actions().stream().map(a->new CrmWorkspaceService.Action(a.key(),a.label(),
            a.visible()&&!a.key().equals("history")&&(canWrite||Set.of("reply","note","details").contains(a.key())))).toList());
        int end=(int)Math.min(records.size(),(long)offset+limit);
        return Map.of("key",workspace.key(),"label",workspace.label(),"config",config,"canWrite",canWrite,
            "rows",records.subList(Math.min(offset,records.size()),end),"total",records.size(),"hasMore",end<records.size(),"nextCursor",end<records.size()?Integer.toString(end):"");
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
