package su.onno.ui.tags;

import java.security.Principal;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import su.onno.ui.*;

@RestController
@RequestMapping("/api/tags/{kind}/{name}")
public class TagController {
    private final java.util.List<TagAccessPolicy> policies;
    private final org.springframework.context.ApplicationEventPublisher events;
    private final TagService tags;
    private final UiAccessService access;
    private final CatalogQueryService catalogs;
    private final DocumentQueryService documents;
    public TagController(TagService tags, UiAccessService access, CatalogQueryService catalogs, DocumentQueryService documents, java.util.List<TagAccessPolicy> policies, org.springframework.context.ApplicationEventPublisher events) {
        this.policies=policies; this.events=events; this.tags=tags; this.access=access; this.catalogs=catalogs; this.documents=documents;
    }
    private String require(String kind,String name,UUID id,Principal user,boolean write) {
        String type = switch(kind) { case "catalogs" -> "catalog"; case "documents" -> "document"; default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND); };
        if (!(write ? access.canWrite(user,type,name) : access.canRead(user,type,name))) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        String canonical;
        Map<String,Object> row;
        if (kind.equals("catalogs")) { var descriptor=catalogs.require(name); canonical=descriptor.logicalName(); row=id==null?null:catalogs.get(descriptor,id); }
        else { var descriptor=documents.require(name); canonical=descriptor.logicalName(); row=id==null?null:documents.get(descriptor,id); }
        if (id!=null && (row==null || Boolean.TRUE.equals(row.get("_deletion_mark")))) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (id!=null) for (var policy:policies) policy.require(kind,canonical,id,user,write);
        return TagService.scope(kind,canonical);
    }
    @GetMapping public List<TagService.Tag> library(@PathVariable String kind,@PathVariable String name,Principal user) { return tags.library(require(kind,name,null,user,false)); }
    @GetMapping("/{id}") public List<TagService.Tag> assigned(@PathVariable String kind,@PathVariable String name,@PathVariable UUID id,Principal user) {
        return tags.assigned(require(kind,name,id,user,false),id);
    }
    @PostMapping("/{id}/{tag}") public void assign(@PathVariable String kind,@PathVariable String name,@PathVariable UUID id,@PathVariable UUID tag,Principal user) {
        tags.assign(require(kind,name,id,user,true),id,tag);
        changed(name,id);
    }
    @DeleteMapping("/{id}/{tag}") public void remove(@PathVariable String kind,@PathVariable String name,@PathVariable UUID id,@PathVariable UUID tag,Principal user) {
        tags.remove(require(kind,name,id,user,true),id,tag);
        changed(name,id);
    }
    private void changed(String name,UUID id) { events.publishEvent(new su.onno.events.EntityChangedEvent("updated","tag",name,id,null)); }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String,String> invalid(IllegalArgumentException error) { return Map.of("message",error.getMessage()); }
}
