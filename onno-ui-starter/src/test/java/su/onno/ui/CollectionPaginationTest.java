package su.onno.ui;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import su.onno.access.AccessSubject;
import su.onno.annotations.*;
import su.onno.metadata.*;
import su.onno.model.*;
import su.onno.schema.SchemaGenerator;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CollectionPaginationTest {
    @AccumulationRegister(name="PageStock", type=AccumulationType.BALANCE)
    static class Stock extends AccumulationRecord {
        @Dimension @SuppressWarnings("unused") /* Read by metadata reflection. */ private String site;
        @Dimension @SuppressWarnings("unused") /* Read by metadata reflection. */ private String product;
        @Resource @SuppressWarnings("unused") /* Read by metadata reflection. */ private BigDecimal amount;
    }
    @Catalog(name="PageNodes", hierarchical=true)
    static class Node extends CatalogObject { @Attribute @SuppressWarnings("unused") /* Read by metadata reflection. */ private UUID parentRecord; }
    Jdbi jdbi;
    RegisterQueryService registers;
    CatalogQueryService catalogs;
    AccumulationRegisterDescriptor stock;
    CatalogDescriptor nodes;
    final AccessSubject subject=AccessSubject.system();
    final UUID parentRecord = UUID.randomUUID();
    @BeforeEach void setup() {
        var ds=new JdbcDataSource(); ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1");
        jdbi=Jdbi.create(ds); var registry=new MetadataRegistry();var scanner=new MetadataScanner(new DefaultNamingStrategy());
        stock=scanner.scanRegister(Stock.class);registry.registerAccumulation(stock);
        nodes=scanner.scan(Node.class);registry.registerCatalog(nodes);new SchemaGenerator(registry).execute(jdbi);
        registers=new RegisterQueryService(registry,jdbi);catalogs=new CatalogQueryService(registry,jdbi);
        jdbi.useHandle(h -> {
            for(int i=1;i<=6;i++) {
                h.execute("INSERT INTO "+stock.tableName()+" (_id,_period,_active,_movement_type,site,product,amount) VALUES (?, TIMESTAMP '2026-11-10 00:00:00',true,'RECEIPT','A',?,?)",
                        new UUID(0,i),"P"+i,i>3 ? null : 10);
                h.execute("INSERT INTO "+stock.totalsTableName()+" (site,product,amount) VALUES ('A',?,?)","P"+i,i>3 ? null : 10);
                h.execute("INSERT INTO "+nodes.tableName()+" (_id,_code,_description,_deletion_mark,_is_folder,parent_record) VALUES (?,?,'same',false,true,?)",new UUID(0,i),"N"+i,parentRecord);
            }
        });
    }
    ListFilter.Result filters(){return ListFilter.parse(List.of(),List.of(),List.of(),registers.movementFilterColumns(stock));}
    @Test void movementsAndCompositeBalancesWalkEqualAndNullSortValues() {
        for(boolean desc:List.of(false,true)) {
            Set<Object> movements=new HashSet<>(), balances=new HashSet<>();String cursor=null;
            do {var p=registers.movementsPage(subject,stock,null,null,filters(),"amount",desc,cursor,2);
                p.rows().forEach(r -> assertThat(movements.add(r.get("_id"))).isTrue());cursor=p.nextCursor();}
            while(cursor!=null);
            do {var p=registers.balancePage(subject,stock,filters(),"amount",desc,cursor,2);
                p.rows().forEach(r -> assertThat(balances.add(r.get("product"))).isTrue());cursor=p.nextCursor();}
            while(cursor!=null);
            assertThat(movements).hasSize(6);assertThat(balances).hasSize(6);
        }
    }
    @Test void insertsDoNotShiftMovementPagesAndForeignFeedIs400() {
        var first=registers.movementsPage(subject,stock,null,null,filters(),"_period",false,null,2);
        jdbi.useHandle(h -> h.execute("INSERT INTO "+stock.tableName()+" (_id,_period,_active) VALUES (?, TIMESTAMP '2020-01-01 00:00:00',true)",new UUID(0,100)));
        var second=registers.movementsPage(subject,stock,null,null,filters(),"_period",false,first.nextCursor(),2);
        assertThat(second.rows()).extracting(r -> r.get("_id")).containsExactly(new UUID(0,3),new UUID(0,4));
        assertThat(registers.movementsPage(subject,stock,null,null,filters(),"_period",false,"broken",2).rows()).hasSize(2);
        assertThatThrownBy(() -> registers.balancePage(subject,stock,filters(),"amount",false,first.nextCursor(),2))
                .isInstanceOfSatisfying(ResponseStatusException.class,e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
    }
    @Test void turnoverPagesDoNotSilentlyLoseDimensionTuples() {
        jdbi.useHandle(h -> {
            for (int i = 7; i <= 510; i++) h.execute("INSERT INTO " + stock.tableName()
                    + " (_id,_period,_active,_movement_type,site,product,amount) VALUES (?, TIMESTAMP '2026-11-10 00:00:00',true,'RECEIPT','A',?,1)",
                    new UUID(0, i), "P" + i);
        });
        // The HTTP controller passes every request parameter (incl. cursor/limit) as filters;
        // those must not change the cursor context between pages.
        var first = registers.turnoverPage(subject, stock, "2020-01-01", "2030-01-01",
                Map.of("from", "2020-01-01", "to", "2030-01-01", "limit", "500"), null, 500);
        var last = registers.turnoverPage(subject, stock, "2020-01-01", "2030-01-01",
                Map.of("from", "2020-01-01", "to", "2030-01-01", "limit", "500", "cursor", first.nextCursor()),
                first.nextCursor(), 500);
        assertThat(first.rows()).hasSize(500); assertThat(last.rows()).hasSize(10);
        assertThat(last.nextCursor()).isNull();
        // The complete-result Java API follows the cursor past one window instead of truncating.
        assertThat(registers.turnover(subject, stock, "2020-01-01", "2030-01-01", Map.of()))
                .hasSize(first.rows().size() + last.rows().size());
    }

    @Test void hierarchyAndRelatedListsAreBoundedAndCursorBoundToParent() {
        var first=catalogs.childrenPage(subject,nodes,null,true,null,2);
        var second=catalogs.childrenPage(subject,nodes,null,true,first.nextCursor(),2);
        assertThat(first.rows()).hasSize(2);assertThat(second.rows()).hasSize(2);
        assertThat(second.rows()).noneMatch(r -> first.rows().stream().anyMatch(a -> a.get("_id").equals(r.get("_id"))));
        var related=catalogs.relatedPage(subject,nodes,"parent_record",parentRecord,null,2);
        assertThat(related.rows()).hasSize(2);assertThat(related.hasMore()).isTrue();
        assertThat(catalogs.relatedPage(subject,nodes,"parent_record",parentRecord,related.nextCursor(),2).rows()).hasSize(2);
        assertThatThrownBy(() -> catalogs.relatedPage(subject,nodes,"parent_record",UUID.randomUUID(),related.nextCursor(),2))
                .isInstanceOf(ResponseStatusException.class);
        var tree=catalogs.treePage(subject,nodes,null,1);assertThat(tree.hasMore()).isTrue();
        assertThatThrownBy(() -> catalogs.childrenPage(subject,nodes,UUID.randomUUID(),true,first.nextCursor(),2))
                .isInstanceOf(ResponseStatusException.class);
    }
}
