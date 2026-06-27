package com.example.seed;

import com.example.domain.catalogs.Book;
import com.example.domain.catalogs.BookCategory;
import com.example.domain.catalogs.Customer;
import com.example.domain.catalogs.Employee;
import com.example.domain.catalogs.Supplier;
import com.example.domain.documents.Order;
import com.example.domain.documents.OrderLine;
import com.example.domain.documents.StockReceipt;
import com.example.domain.documents.StockReceiptLine;
import com.example.domain.enumerations.OrderStatus;
import com.example.domain.enumerations.Position;
import com.example.repositories.BookCategoryRepository;
import com.example.repositories.BookRepository;
import com.example.repositories.CustomerRepository;
import com.example.repositories.EmployeeRepository;
import com.example.repositories.OrderRepository;
import com.example.repositories.StockReceiptRepository;
import com.example.repositories.SupplierRepository;
import su.onno.posting.PostingService;
import su.onno.types.Ref;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * First-launch demo data so the app isn't empty on a fresh database: a few categories, suppliers,
 * customers and staff; a dozen books; an opening stock receipt; and a handful of orders spread
 * across the lifecycle. Idempotent — it does nothing once any book exists.
 *
 * <p>It follows the framework's one hard rule about posting: <b>save the document, let it commit,
 * then post</b> (posting runs in its own transaction). The opening {@link StockReceipt} is posted
 * first so there are copies on hand before the {@link Order}s take them back out — an oversold order
 * couldn't post, because {@link com.example.domain.registers.BookStock} is a BALANCE register.</p>
 */
@Component
public class BookstoreSeeder implements ApplicationRunner {

    private final BookCategoryRepository categories;
    private final SupplierRepository suppliers;
    private final CustomerRepository customers;
    private final EmployeeRepository employees;
    private final BookRepository books;
    private final StockReceiptRepository receipts;
    private final OrderRepository orders;
    private final PostingService posting;

    public BookstoreSeeder(BookCategoryRepository categories, SupplierRepository suppliers,
                           CustomerRepository customers, EmployeeRepository employees,
                           BookRepository books, StockReceiptRepository receipts,
                           OrderRepository orders, PostingService posting) {
        this.categories = categories;
        this.suppliers = suppliers;
        this.customers = customers;
        this.employees = employees;
        this.books = books;
        this.receipts = receipts;
        this.orders = orders;
        this.posting = posting;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (books.count() > 0) {
            return; // already seeded
        }

        // --- Master data --------------------------------------------------------------------------
        Ref<BookCategory> fiction = category("Fiction");
        Ref<BookCategory> children = category("Children's");
        Ref<BookCategory> science = category("Science");

        Ref<Supplier> penguin = supplier("Penguin Random House", "orders@penguin.example");
        Ref<Supplier> oreilly = supplier("O'Reilly Media", "trade@oreilly.example");

        Ref<Customer> alice = customer("Alice Hoffman", "alice@example.com");
        Ref<Customer> bruno = customer("Bruno Costa", "bruno@example.com");
        Ref<Customer> chen = customer("Chen Wei", "chen@example.com");

        Ref<Employee> mara = employee("Mara Ellis", "manager@onnobooks.local", Position.MANAGER);
        Ref<Employee> theo = employee("Theo Park", "theo@onnobooks.local", Position.BOOKSELLER);

        Ref<Book> dune = book("Dune", "Frank Herbert", "9780441013593", fiction, penguin, "12.99");
        Ref<Book> gatsby = book("The Great Gatsby", "F. Scott Fitzgerald", "9780743273565", fiction, penguin, "9.50");
        Ref<Book> mockingbird = book("To Kill a Mockingbird", "Harper Lee", "9780061120084", fiction, penguin, "10.00");
        Ref<Book> wildThings = book("Where the Wild Things Are", "Maurice Sendak", "9780064431781", children, penguin, "7.99");
        Ref<Book> matilda = book("Matilda", "Roald Dahl", "9780142410370", children, penguin, "8.50");
        Ref<Book> brief = book("A Brief History of Time", "Stephen Hawking", "9780553380163", science, penguin, "14.00");
        Ref<Book> selfish = book("The Selfish Gene", "Richard Dawkins", "9780198788607", science, penguin, "13.25");
        Ref<Book> cleanCode = book("Clean Code", "Robert C. Martin", "9780132350884", science, oreilly, "32.00");
        Ref<Book> pragmatic = book("The Pragmatic Programmer", "Hunt & Thomas", "9780135957059", science, oreilly, "35.00");
        Ref<Book> sapiens = book("Sapiens", "Yuval Noah Harari", "9780099590088", science, penguin, "11.99");

        // --- Opening stock: receive copies of everything, then post to raise the balances ----------
        StockReceipt opening = new StockReceipt();
        opening.setSupplier(penguin);
        opening.setNote("Opening stock");
        opening.setDate(LocalDateTime.now().minusDays(20));
        for (Ref<Book> b : List.of(dune, gatsby, mockingbird, wildThings, matilda, brief, selfish, cleanCode, pragmatic, sapiens)) {
            opening.getLines().add(receiptLine(b, 25));
        }
        receipts.save(opening);   // commit first…
        posting.post(opening);    // …then post

        // --- A spread of orders across the lifecycle and across the last two weeks -----------------
        // Posted orders (CONFIRMED+) draw down stock and record sales; NEW/CANCELLED do not. Dates are
        // spread so the dashboard's revenue-over-time chart has something to show.
        order(alice, mara, OrderStatus.COMPLETED, 12, line(dune, 1, "12.99"), line(sapiens, 1, "11.99"));
        order(bruno, theo, OrderStatus.COMPLETED, 9, line(cleanCode, 1, "32.00"), line(pragmatic, 1, "35.00"));
        order(chen, mara, OrderStatus.SHIPPED, 6, line(matilda, 2, "8.50"));
        order(alice, theo, OrderStatus.CONFIRMED, 3, line(brief, 1, "14.00"), line(selfish, 1, "13.25"));
        order(bruno, mara, OrderStatus.CONFIRMED, 1, line(sapiens, 2, "11.99"));
        order(chen, theo, OrderStatus.NEW, 0, line(gatsby, 1, "9.50"));               // unposted draft
        order(alice, mara, OrderStatus.CANCELLED, 2, line(mockingbird, 1, "10.00"));  // cancelled draft
    }

    // --- helpers ----------------------------------------------------------------------------------

    private Ref<BookCategory> category(String name) {
        BookCategory c = new BookCategory();
        c.setDescription(name);
        return Ref.of(BookCategory.class, categories.save(c).getId());
    }

    private Ref<Supplier> supplier(String name, String email) {
        Supplier s = new Supplier();
        s.setDescription(name);
        s.setEmail(email);
        return Ref.of(Supplier.class, suppliers.save(s).getId());
    }

    private Ref<Customer> customer(String name, String email) {
        Customer c = new Customer();
        c.setDescription(name);
        c.setEmail(email);
        return Ref.of(Customer.class, customers.save(c).getId());
    }

    private Ref<Employee> employee(String name, String email, Position position) {
        Employee e = new Employee();
        e.setDescription(name);
        e.setEmail(email);
        e.setPosition(position);
        return Ref.of(Employee.class, employees.save(e).getId());
    }

    private Ref<Book> book(String title, String author, String isbn,
                           Ref<BookCategory> category, Ref<Supplier> supplier, String price) {
        Book b = new Book();
        b.setDescription(title);
        b.setAuthor(author);
        b.setIsbn(isbn);
        b.setCategory(category);
        b.setSupplier(supplier);
        b.setPrice(new BigDecimal(price));
        return Ref.of(Book.class, books.save(b).getId());
    }

    private StockReceiptLine receiptLine(Ref<Book> book, int qty) {
        StockReceiptLine l = new StockReceiptLine();
        l.setBook(book);
        l.setQuantity(BigDecimal.valueOf(qty));
        return l;
    }

    private OrderLine line(Ref<Book> book, int qty, String price) {
        OrderLine l = new OrderLine();
        l.setBook(book);
        l.setQuantity(BigDecimal.valueOf(qty));
        l.setUnitPrice(new BigDecimal(price));
        return l;
    }

    /** Build, save and (for non-draft statuses) post an order dated {@code daysAgo} days back. */
    private void order(Ref<Customer> customer, Ref<Employee> seller, OrderStatus status, int daysAgo, OrderLine... lines) {
        Order o = new Order();
        o.setCustomer(customer);
        o.setAssignedTo(seller);
        o.setStatus(status);
        o.setDate(LocalDateTime.now().minusDays(daysAgo));  // explicit date — onFilling won't overwrite it
        for (OrderLine l : lines) {
            o.getItems().add(l);
        }
        orders.save(o);  // commit first…
        // …then post the ones that represent committed business (skip the NEW draft and the cancelled one).
        if (status != OrderStatus.NEW && status != OrderStatus.CANCELLED) {
            posting.post(o);
        }
    }
}
