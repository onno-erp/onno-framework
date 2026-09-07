package su.onno.crm.service;
import java.util.*;
import su.onno.crm.domain.Tag;
import su.onno.crm.repository.TagRepository;
import su.onno.ui.tags.*;
public class CrmTagCatalog implements TagCatalog {
    private final TagRepository tags;
    public CrmTagCatalog(TagRepository tags){this.tags=tags;}
    @Override public String catalogName(){return "CrmTags";}
    @Override public String scope(){return TagService.scope("catalogs","CrmCustomers");}
    @Override public List<TagService.Tag> list(){return tags.findAllActive().stream().map(this::view).sorted(Comparator.comparing(TagService.Tag::name,String.CASE_INSENSITIVE_ORDER)).toList();}
    @Override public TagService.Tag importTag(UUID id,String name,String color){
        var existing=tags.findById(id);
        if(existing.isPresent())return view(existing.get());
        Tag tag=new Tag();tag.setId(id);tag.setDescription(name);tag.setColor(color);return view(tags.save(tag));
    }
    private TagService.Tag view(Tag tag){return new TagService.Tag(tag.getId(),tag.getDescription(),tag.getColor());}
}
