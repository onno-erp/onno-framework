package su.onno.crm.ui;
import su.onno.crm.domain.Tag;
import su.onno.ui.*;
public class TagView implements EntityView<Tag> {
    @Override public Class<Tag> entity(){return Tag.class;}
    @Override public void list(ListSpec<Tag> list){list.title("Tags");list.columns(Tag::getDescription,Tag::getColor).label(Tag::getDescription,"Name").sortBy(Tag::getDescription);}
    @Override public void fields(EntityConfigBuilder<Tag> fields){fields.field(Tag::getDescription).label("Name").order(0).field(Tag::getColor).widget("color").order(1);}
}
