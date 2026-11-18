package su.onno.archunit.fixtures;

import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import su.onno.model.CatalogObject;
import su.onno.model.DocumentObject;
import su.onno.model.TabularSectionRow;
import su.onno.posting.PostingService;
import su.onno.query.KeysetWindow;
import su.onno.repository.CatalogRepository;
import su.onno.types.Ref;

import java.util.List;
import java.util.Map;

/** Small compliant and violating classes for {@code OnnoRulesTest}; never instantiated. */
public final class Fixtures {
    private Fixtures() {
    }

    // --- domain --------------------------------------------------------------------------------

    public static class Product extends CatalogObject {
    }

    public interface ProductRepository extends CatalogRepository<Product> {
    }

    public static class GoodLine extends TabularSectionRow {
        Ref<Product> product;
        List<GoodLine> children;
    }

    public static class EmbeddedProductLine extends TabularSectionRow {
        Product product;
    }

    public static class EmbeddedProductList extends DocumentObject {
        List<Product> products;
        Map<String, Product[]> byCode;
    }

    public static class SpringAwareOrder extends DocumentObject {
        ApplicationContext context;
    }

    // --- posting -------------------------------------------------------------------------------

    @Service
    public static class SavesThenPosts {
        PostingService posting;

        @Transactional
        public void save(DocumentObject document) {
        }

        public void post(DocumentObject document) {
            posting.post(document);
        }

        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        public void postSuspended(DocumentObject document) {
            posting.repost(document);
        }
    }

    @Service
    public static class PostsInsideTransaction {
        PostingService posting;

        @Transactional
        public void saveAndPost(DocumentObject document) {
            posting.post(document);
        }
    }

    @Service
    @Transactional
    public static class TransactionalClassPostsInLambda {
        PostingService posting;

        public void postAll(List<DocumentObject> documents) {
            documents.forEach(d -> posting.unpost(d));
        }
    }

    // --- controllers ---------------------------------------------------------------------------

    @RestController
    public static class RepositoryController {
        ProductRepository products;

        @GetMapping("/products/{id}")
        public Object one() {
            return products.findAll();
        }
    }

    @org.springframework.stereotype.Controller
    public static class PlainController {
        ProductRepository products;
    }

    @RestController
    public static class PostingController {
        PostingService posting;
    }

    @RestController
    public static class PagedController {
        @GetMapping("/products")
        public KeysetWindow.Page<Product> page() {
            return null;
        }

        @GetMapping("/file")
        public byte[] file() {
            return null;
        }

        @GetMapping("/one")
        public ResponseEntity<Product> one() {
            return null;
        }
    }

    @RestController
    public static class BareListController {
        @GetMapping("/all")
        public List<Product> all() {
            return null;
        }

        @RequestMapping(path = "/wrapped", method = RequestMethod.GET)
        public ResponseEntity<List<Product>> wrapped() {
            return null;
        }
    }

    // --- keyset windows ------------------------------------------------------------------------

    public static class Lists {
        KeysetWindow.Page<String> page(String cursor, int limit) {
            return null;
        }

        public List<String> all() {
            return KeysetWindow.drain(c -> page(c, KeysetWindow.MAX_LIMIT));
        }

        public KeysetWindow.Page<String> window(String cursor) {
            return page(cursor, 100);
        }

        public List<String> followsCursorByHand() {
            List<String> all = new java.util.ArrayList<>();
            String cursor = null;
            do {
                KeysetWindow.Page<String> page = page(cursor, KeysetWindow.MAX_LIMIT);
                all.addAll(page.rows());
                cursor = page.nextCursor();
            } while (cursor != null);
            return all;
        }

        public List<String> firstWindowOnly() {
            return page(null, KeysetWindow.MAX_LIMIT).rows();
        }
    }
}
