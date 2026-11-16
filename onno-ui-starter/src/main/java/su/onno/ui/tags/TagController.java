package su.onno.ui.tags;

import java.security.Principal;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import su.onno.ui.*;

@RestController
@RequestMapping("/api/tags/{kind}/{name}")
@SuppressWarnings("deprecation") // still runs the deprecated TagAccessPolicy hooks in addition
public class TagController {
    private final java.util.List<TagAccessPolicy> policies;
    private final TagService tags;
    private final UiAccessService access;
    private final CatalogQueryService catalogs;
    private final DocumentQueryService documents;
    /** Null when record policies are not configured; then no subject is record-restricted. */
    private final RecordAccess recordAccess;
    public TagController(TagService tags, UiAccessService access, CatalogQueryService catalogs, DocumentQueryService documents, java.util.List<TagAccessPolicy> policies, RecordAccess recordAccess) {
        this.policies=policies; this.tags=tags; this.access=access; this.catalogs=catalogs; this.documents=documents; this.recordAccess=recordAccess;
    }
    /**
     * Entity grant, then — for a record — that it is live and inside the caller's record scope (read
     * scope to list tags, write scope to change them; 404 otherwise), then any {@link TagAccessPolicy}.
     */
    private String require(String kind,String name,UUID id,Principal user,su.onno.access.AccessSubject subject,boolean write) {
        String type = switch(kind) { case "catalogs" -> "catalog"; case "documents" -> "document"; default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND); };
        if (!(write ? access.canWrite(user,type,name) : access.canRead(user,type,name))) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        String canonical;
        Map<String,Object> row;
        su.onno.access.AccessMode mode = write ? su.onno.access.AccessMode.WRITE : su.onno.access.AccessMode.READ;
        if (kind.equals("catalogs")) { var descriptor=catalogs.require(name); canonical=descriptor.logicalName(); row=id==null?null:catalogs.get(subject,descriptor,id); if (id!=null && write && !catalogs.inScope(subject,descriptor,id,mode)) row=null; }
        else { var descriptor=documents.require(name); canonical=descriptor.logicalName(); row=id==null?null:documents.get(subject,descriptor,id); if (id!=null && write && !documents.inScope(subject,descriptor,id,mode)) row=null; }
        if (id!=null && (row==null || Boolean.TRUE.equals(row.get("_deletion_mark")))) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (id!=null) for (var policy:policies) policy.require(kind,canonical,id,user,write);
        return TagService.scope(kind,canonical);
    }
    @GetMapping public List<TagService.Tag> library(@PathVariable String kind,@PathVariable String name,Principal user,su.onno.access.AccessSubject subject) { return tags.library(require(kind,name,null,user,subject,false)); }
    public record NewTag(String name, String color) {}
    /**
     * Create a tag in this entity's library (or return the existing one with that name) so pickers can
     * add tags inline. Requires write access to the tagged entity and, when an application catalog
     * owns the definitions ({@link TagCatalog}), write access to that catalog too. The library is
     * shared by every record of the entity, so a record-restricted subject (an external user under a
     * {@link su.onno.access.RecordAccessPolicy}) cannot add to it; it can still assign existing tags.
     */
    @PostMapping public TagService.Tag create(@PathVariable String kind,@PathVariable String name,@RequestBody NewTag tag,Principal user,su.onno.access.AccessSubject subject) {
        String scope=require(kind,name,null,user,subject,true);
        if(recordAccess!=null && subject!=null && recordAccess.policies().restricts(subject)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        var bound=tags.catalogFor(scope);
        if(bound.isPresent() && !access.canWrite(user,"catalog",bound.get().catalogName())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return tags.create(scope,tag==null?null:tag.name(),tag==null?null:tag.color());
    }
    @GetMapping("/{id}") public List<TagService.Tag> assigned(@PathVariable String kind,@PathVariable String name,@PathVariable UUID id,Principal user,su.onno.access.AccessSubject subject) {
        return tags.assigned(require(kind,name,id,user,subject,false),id);
    }
    @PostMapping("/{id}/{tag}") public void assign(@PathVariable String kind,@PathVariable String name,@PathVariable UUID id,@PathVariable UUID tag,Principal user,su.onno.access.AccessSubject subject) {
        tags.assign(require(kind,name,id,user,subject,true),id,tag);
    }
    @DeleteMapping("/{id}/{tag}") public void remove(@PathVariable String kind,@PathVariable String name,@PathVariable UUID id,@PathVariable UUID tag,Principal user,su.onno.access.AccessSubject subject) {
        tags.remove(require(kind,name,id,user,subject,true),id,tag);
    }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String,String> invalid(IllegalArgumentException error) { return Map.of("message",error.getMessage()); }
}
