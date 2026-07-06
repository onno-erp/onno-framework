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
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;

/**
 * First-launch demo data so the app looks like a real, running shop on a fresh database: a dozen
 * categories, eight suppliers and staff, a few dozen customers, ~80 books, a big opening stock
 * receipt, and ~80 orders spread over the last quarter across the whole lifecycle. Generated from a
 * fixed RNG seed so the dataset is the same every time. Idempotent — does nothing once any book exists.
 *
 * <p>It follows the framework's one hard rule about posting: <b>save the document, let it commit,
 * then post</b> (posting runs in its own transaction). The opening {@link StockReceipt} is posted
 * first so there are copies on hand before the {@link Order}s take them back out — an oversold order
 * couldn't post, because {@link com.example.domain.registers.BookStock} is a BALANCE register, which
 * is why the opening quantity per title is set well above total demand.</p>
 */
@Component
public class BookstoreSeeder implements ApplicationRunner {

    /** Books: {title, author, category, price}. Category must match one of {@link #CATEGORIES}. */
    private static final String[][] BOOKS = {
        // Fiction
        {"The Great Gatsby", "F. Scott Fitzgerald", "Fiction", "9.50"},
        {"To Kill a Mockingbird", "Harper Lee", "Fiction", "10.00"},
        {"Beloved", "Toni Morrison", "Fiction", "12.00"},
        {"Pride and Prejudice", "Jane Austen", "Fiction", "8.00"},
        {"The Catcher in the Rye", "J.D. Salinger", "Fiction", "9.00"},
        {"One Hundred Years of Solitude", "Gabriel García Márquez", "Fiction", "13.50"},
        {"Normal People", "Sally Rooney", "Fiction", "11.00"},
        // Children's
        {"Where the Wild Things Are", "Maurice Sendak", "Children's", "7.99"},
        {"Matilda", "Roald Dahl", "Children's", "8.50"},
        {"The Very Hungry Caterpillar", "Eric Carle", "Children's", "6.99"},
        {"Charlotte's Web", "E.B. White", "Children's", "7.50"},
        {"The Gruffalo", "Julia Donaldson", "Children's", "7.00"},
        {"Green Eggs and Ham", "Dr. Seuss", "Children's", "6.50"},
        {"The Cat in the Hat", "Dr. Seuss", "Children's", "6.50"},
        // Science
        {"A Brief History of Time", "Stephen Hawking", "Science", "14.00"},
        {"The Selfish Gene", "Richard Dawkins", "Science", "13.25"},
        {"Cosmos", "Carl Sagan", "Science", "15.00"},
        {"The Gene", "Siddhartha Mukherjee", "Science", "16.00"},
        {"Silent Spring", "Rachel Carson", "Science", "12.50"},
        {"The Origin of Species", "Charles Darwin", "Science", "11.00"},
        {"Astrophysics for People in a Hurry", "Neil deGrasse Tyson", "Science", "10.50"},
        // History
        {"Sapiens", "Yuval Noah Harari", "History", "11.99"},
        {"Guns, Germs, and Steel", "Jared Diamond", "History", "13.00"},
        {"SPQR", "Mary Beard", "History", "14.50"},
        {"The Silk Roads", "Peter Frankopan", "History", "15.00"},
        {"A People's History of the United States", "Howard Zinn", "History", "13.75"},
        {"The Wright Brothers", "David McCullough", "History", "12.00"},
        {"1776", "David McCullough", "History", "12.00"},
        // Biography
        {"Steve Jobs", "Walter Isaacson", "Biography", "16.00"},
        {"Long Walk to Freedom", "Nelson Mandela", "Biography", "14.00"},
        {"The Diary of a Young Girl", "Anne Frank", "Biography", "9.00"},
        {"Becoming", "Michelle Obama", "Biography", "17.00"},
        {"Educated", "Tara Westover", "Biography", "13.50"},
        {"Born a Crime", "Trevor Noah", "Biography", "12.50"},
        {"Leonardo da Vinci", "Walter Isaacson", "Biography", "18.00"},
        // Mystery & Thriller
        {"The Girl with the Dragon Tattoo", "Stieg Larsson", "Mystery & Thriller", "11.00"},
        {"Gone Girl", "Gillian Flynn", "Mystery & Thriller", "10.50"},
        {"The Da Vinci Code", "Dan Brown", "Mystery & Thriller", "9.99"},
        {"And Then There Were None", "Agatha Christie", "Mystery & Thriller", "8.50"},
        {"The Silence of the Lambs", "Thomas Harris", "Mystery & Thriller", "10.00"},
        {"Big Little Lies", "Liane Moriarty", "Mystery & Thriller", "11.50"},
        {"The Reversal", "Michael Connelly", "Mystery & Thriller", "9.50"},
        // Science Fiction
        {"Dune", "Frank Herbert", "Science Fiction", "12.99"},
        {"Neuromancer", "William Gibson", "Science Fiction", "11.00"},
        {"Foundation", "Isaac Asimov", "Science Fiction", "10.00"},
        {"The Left Hand of Darkness", "Ursula K. Le Guin", "Science Fiction", "11.50"},
        {"Snow Crash", "Neal Stephenson", "Science Fiction", "12.00"},
        {"Hyperion", "Dan Simmons", "Science Fiction", "12.50"},
        {"The Martian", "Andy Weir", "Science Fiction", "11.99"},
        // Fantasy
        {"The Hobbit", "J.R.R. Tolkien", "Fantasy", "12.00"},
        {"A Game of Thrones", "George R.R. Martin", "Fantasy", "14.00"},
        {"The Name of the Wind", "Patrick Rothfuss", "Fantasy", "13.00"},
        {"Mistborn", "Brandon Sanderson", "Fantasy", "12.50"},
        {"The Way of Kings", "Brandon Sanderson", "Fantasy", "15.00"},
        {"American Gods", "Neil Gaiman", "Fantasy", "13.50"},
        {"The Lion, the Witch and the Wardrobe", "C.S. Lewis", "Fantasy", "8.00"},
        // Self-Help
        {"Atomic Habits", "James Clear", "Self-Help", "14.00"},
        {"The 7 Habits of Highly Effective People", "Stephen Covey", "Self-Help", "13.00"},
        {"Thinking, Fast and Slow", "Daniel Kahneman", "Self-Help", "15.00"},
        {"The Power of Now", "Eckhart Tolle", "Self-Help", "12.00"},
        {"Mindset", "Carol Dweck", "Self-Help", "11.50"},
        {"Daring Greatly", "Brené Brown", "Self-Help", "12.50"},
        {"Man's Search for Meaning", "Viktor Frankl", "Self-Help", "10.00"},
        // Business
        {"Zero to One", "Peter Thiel", "Business", "13.00"},
        {"Good to Great", "Jim Collins", "Business", "14.00"},
        {"The Lean Startup", "Eric Ries", "Business", "13.50"},
        {"Clean Code", "Robert C. Martin", "Business", "32.00"},
        {"The Pragmatic Programmer", "Hunt & Thomas", "Business", "35.00"},
        {"The Innovator's Dilemma", "Clayton Christensen", "Business", "16.00"},
        {"Shoe Dog", "Phil Knight", "Business", "14.50"},
        // Cooking
        {"Salt, Fat, Acid, Heat", "Samin Nosrat", "Cooking", "22.00"},
        {"The Joy of Cooking", "Irma Rombauer", "Cooking", "20.00"},
        {"Mastering the Art of French Cooking", "Julia Child", "Cooking", "25.00"},
        {"Plenty", "Yotam Ottolenghi", "Cooking", "24.00"},
        {"How to Cook Everything", "Mark Bittman", "Cooking", "21.00"},
        {"The Food Lab", "J. Kenji López-Alt", "Cooking", "30.00"},
        {"Essentials of Classic Italian Cooking", "Marcella Hazan", "Cooking", "23.00"},
        // Poetry
        {"Milk and Honey", "Rupi Kaur", "Poetry", "9.00"},
        {"The Sun and Her Flowers", "Rupi Kaur", "Poetry", "10.00"},
        {"Leaves of Grass", "Walt Whitman", "Poetry", "8.50"},
        {"Ariel", "Sylvia Plath", "Poetry", "9.50"},
        {"The Waste Land", "T.S. Eliot", "Poetry", "8.00"},
        {"Devotions", "Mary Oliver", "Poetry", "11.00"},
        {"Citizen", "Claudia Rankine", "Poetry", "10.50"},
    };

    private static final String[] CATEGORIES = {
        "Fiction", "Children's", "Science", "History", "Biography", "Mystery & Thriller",
        "Science Fiction", "Fantasy", "Self-Help", "Business", "Cooking", "Poetry"
    };

    private static final String[][] SUPPLIERS = {
        {"Penguin Random House", "orders@penguin.example"},
        {"HarperCollins", "trade@harpercollins.example"},
        {"Hachette Book Group", "orders@hachette.example"},
        {"Simon & Schuster", "trade@simonschuster.example"},
        {"Macmillan", "orders@macmillan.example"},
        {"O'Reilly Media", "trade@oreilly.example"},
        {"Bloomsbury", "orders@bloomsbury.example"},
        {"Oxford University Press", "trade@oup.example"},
    };

    /** Staff: {name, email, position}. The first two emails double as the demo logins (see
     *  application.yaml): {@code admin@onnobooks.local} (ADMIN) and {@code manager@onnobooks.local}
     *  (MANAGER) — so the signed-in person links to a real Employee row and the shell shows their
     *  photo. */
    private static final String[][] EMPLOYEES = {
        {"Ada Sinclair", "admin@onnobooks.local", "MANAGER"},
        {"Mara Ellis", "manager@onnobooks.local", "MANAGER"},
        {"Owen Reid", "owen@onnobooks.local", "MANAGER"},
        {"Theo Park", "theo@onnobooks.local", "BOOKSELLER"},
        {"Nadia Brooks", "nadia@onnobooks.local", "BOOKSELLER"},
        {"Priya Nair", "priya@onnobooks.local", "BOOKSELLER"},
        {"Hana Kim", "hana@onnobooks.local", "BOOKSELLER"},
        {"Liam Walsh", "liam@onnobooks.local", "FULFILMENT"},
        {"Diego Santos", "diego@onnobooks.local", "FULFILMENT"},
    };

    private static final String[] FIRST_NAMES = {
        "Alice", "Bruno", "Chen", "Dara", "Elena", "Farid", "Grace", "Hugo", "Ines", "Jonas",
        "Keira", "Luca", "Maya", "Noah", "Olga", "Pablo", "Quinn", "Rosa", "Sami", "Tara",
        "Umar", "Vera", "Wes", "Xenia", "Yusuf", "Zoe", "Anil", "Bea", "Cole", "Dina",
        "Emre", "Faye", "Gabe", "Hana", "Ivo", "Juno"
    };
    private static final String[] LAST_NAMES = {
        "Hoffman", "Costa", "Wei", "Okafor", "Ricci", "Haddad", "Lindqvist", "Mbeki", "Novak", "Reyes",
        "Tanaka", "Berg", "Sokolov", "Adeyemi", "Petrova", "Singh", "Moreau", "Fischer", "Diaz", "Ahmed"
    };

    /** Cities customers are spread across: {name, latitude, longitude}. */
    private static final String[][] CITIES = {
        {"New York", "40.7128", "-74.0060"},
        {"Los Angeles", "34.0522", "-118.2437"},
        {"Chicago", "41.8781", "-87.6298"},
        {"Toronto", "43.6532", "-79.3832"},
        {"London", "51.5074", "-0.1278"},
        {"Paris", "48.8566", "2.3522"},
        {"Berlin", "52.5200", "13.4050"},
        {"Madrid", "40.4168", "-3.7038"},
        {"Rome", "41.9028", "12.4964"},
        {"Amsterdam", "52.3676", "4.9041"},
        {"Tokyo", "35.6762", "139.6503"},
        {"Singapore", "1.3521", "103.8198"},
        {"Dubai", "25.2048", "55.2708"},
        {"Mumbai", "19.0760", "72.8777"},
        {"Sydney", "-33.8688", "151.2093"},
        {"São Paulo", "-23.5505", "-46.6333"},
        {"Mexico City", "19.4326", "-99.1332"},
        {"Cape Town", "-33.9249", "18.4241"},
    };

    /** Order-status mix, weighted toward completed/shipped business with a few open and cancelled. */
    private static final OrderStatus[] STATUS_MIX = {
        OrderStatus.COMPLETED, OrderStatus.COMPLETED, OrderStatus.COMPLETED, OrderStatus.COMPLETED,
        OrderStatus.COMPLETED, OrderStatus.COMPLETED, OrderStatus.COMPLETED, OrderStatus.COMPLETED,
        OrderStatus.SHIPPED, OrderStatus.SHIPPED, OrderStatus.SHIPPED, OrderStatus.SHIPPED,
        OrderStatus.CONFIRMED, OrderStatus.CONFIRMED, OrderStatus.CONFIRMED,
        OrderStatus.NEW, OrderStatus.NEW,
        OrderStatus.CANCELLED,
    };

    private static final int ORDER_COUNT = 80;
    private static final int OPENING_STOCK_PER_BOOK = 150;

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
        Random rnd = new Random(20240601L); // fixed seed → the same dataset every fresh launch

        // --- Master data --------------------------------------------------------------------------
        Map<String, Ref<BookCategory>> categoryRefs = new LinkedHashMap<>();
        for (String name : CATEGORIES) {
            categoryRefs.put(name, category(name));
        }

        List<Ref<Supplier>> supplierRefs = new ArrayList<>();
        for (String[] s : SUPPLIERS) {
            supplierRefs.add(supplier(s[0], s[1]));
        }

        List<Ref<Employee>> employeeRefs = new ArrayList<>();
        for (String[] e : EMPLOYEES) {
            employeeRefs.add(employee(e[0], e[1], Position.valueOf(e[2])));
        }

        List<Ref<Customer>> customerRefs = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            String first = FIRST_NAMES[i % FIRST_NAMES.length];
            String last = LAST_NAMES[(i * 7 + 3) % LAST_NAMES.length];
            String email = (first + "." + last).toLowerCase().replace(" ", "") + "@example.com";
            // Spread customers across cities, with a little jitter so two in the same city don't
            // sit on the exact same pixel on the map.
            String[] cityRow = CITIES[i % CITIES.length];
            double lat = Double.parseDouble(cityRow[1]) + (rnd.nextDouble() - 0.5) * 0.12;
            double lng = Double.parseDouble(cityRow[2]) + (rnd.nextDouble() - 0.5) * 0.12;
            customerRefs.add(customer(first + " " + last, email, cityRow[0], lat, lng));
        }

        // Books, with prices kept in a parallel list so order lines charge the catalog price.
        List<Ref<Book>> bookRefs = new ArrayList<>();
        List<BigDecimal> bookPrices = new ArrayList<>();
        for (int i = 0; i < BOOKS.length; i++) {
            String[] b = BOOKS[i];
            BigDecimal price = new BigDecimal(b[3]);
            String isbn = "978" + String.format("%010d", 1_000_000 + i); // synthetic but well-formed
            bookRefs.add(book(b[0], b[1], isbn, categoryRefs.get(b[2]),
                    supplierRefs.get(i % supplierRefs.size()), price));
            bookPrices.add(price);
        }

        // --- Opening stock: receive a generous quantity of every title, then post -------------------
        StockReceipt opening = new StockReceipt();
        opening.setSupplier(supplierRefs.get(0));
        opening.setNote("Opening stock");
        opening.setDate(LocalDateTime.now().minusDays(95));
        for (Ref<Book> b : bookRefs) {
            opening.getLines().add(receiptLine(b, OPENING_STOCK_PER_BOOK));
        }
        receipts.save(opening);   // commit first…
        posting.post(opening);    // …then post

        // --- ~80 orders spread over the last quarter, across the whole lifecycle --------------------
        // Posted orders (CONFIRMED+) draw down stock and record sales; NEW/CANCELLED do not.
        for (int i = 0; i < ORDER_COUNT; i++) {
            OrderStatus status = STATUS_MIX[rnd.nextInt(STATUS_MIX.length)];
            Ref<Customer> cust = customerRefs.get(rnd.nextInt(customerRefs.size()));
            Ref<Employee> seller = employeeRefs.get(rnd.nextInt(employeeRefs.size()));
            int daysAgo = rnd.nextInt(90);

            // 1–4 distinct titles per order, 1–3 copies each.
            int lineCount = 1 + rnd.nextInt(4);
            TreeSet<Integer> picks = new TreeSet<>();
            while (picks.size() < lineCount) {
                picks.add(rnd.nextInt(bookRefs.size()));
            }
            List<OrderLine> lines = new ArrayList<>();
            for (int idx : picks) {
                lines.add(line(bookRefs.get(idx), 1 + rnd.nextInt(3), bookPrices.get(idx)));
            }
            order(cust, seller, status, daysAgo, lines);
        }
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

    private Ref<Customer> customer(String name, String email, String city, double lat, double lng) {
        Customer c = new Customer();
        c.setDescription(name);
        c.setEmail(email);
        c.setCity(city);
        c.setLatitude(BigDecimal.valueOf(lat).setScale(6, RoundingMode.HALF_UP));
        c.setLongitude(BigDecimal.valueOf(lng).setScale(6, RoundingMode.HALF_UP));
        return Ref.of(Customer.class, customers.save(c).getId());
    }

    private Ref<Employee> employee(String name, String email, Position position) {
        Employee e = new Employee();
        e.setDescription(name);
        e.setEmail(email);
        e.setPosition(position);
        e.setAvatarUrl(avatarUrl(name));
        return Ref.of(Employee.class, employees.save(e).getId());
    }

    /** A deterministic notionists-neutral avatar per staff name (DiceBear), so every employee has a
     *  photo and the signed-in person's shows in the shell's account block without shipping image
     *  files. */
    private static String avatarUrl(String name) {
        String seed = URLEncoder.encode(name, StandardCharsets.UTF_8);
        return "https://api.dicebear.com/9.x/notionists-neutral/svg?radius=50&seed=" + seed;
    }

    private Ref<Book> book(String title, String author, String isbn,
                           Ref<BookCategory> category, Ref<Supplier> supplier, BigDecimal price) {
        Book b = new Book();
        b.setDescription(title);
        b.setAuthor(author);
        b.setIsbn(isbn);
        b.setCategory(category);
        b.setSupplier(supplier);
        b.setPrice(price);
        return Ref.of(Book.class, books.save(b).getId());
    }

    private StockReceiptLine receiptLine(Ref<Book> book, int qty) {
        StockReceiptLine l = new StockReceiptLine();
        l.setBook(book);
        l.setQuantity(BigDecimal.valueOf(qty));
        return l;
    }

    private OrderLine line(Ref<Book> book, int qty, BigDecimal price) {
        OrderLine l = new OrderLine();
        l.setBook(book);
        l.setQuantity(BigDecimal.valueOf(qty));
        l.setUnitPrice(price);
        return l;
    }

    /** Build, save and (for non-draft statuses) post an order dated {@code daysAgo} days back. */
    private void order(Ref<Customer> customer, Ref<Employee> seller, OrderStatus status,
                       int daysAgo, List<OrderLine> lines) {
        Order o = new Order();
        o.setCustomer(customer);
        o.setAssignedTo(seller);
        o.setStatus(status);
        o.setDate(LocalDateTime.now().minusDays(daysAgo));  // explicit date — onFilling won't overwrite it
        o.getItems().addAll(lines);
        orders.save(o);  // commit first…
        // …then post the ones that represent committed business (skip NEW drafts and cancellations).
        if (status != OrderStatus.NEW && status != OrderStatus.CANCELLED) {
            posting.post(o);
        }
    }
}
