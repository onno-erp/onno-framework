package com.example.seed;

import com.example.domain.catalogs.BankAccount;
import com.example.domain.catalogs.Client;
import com.example.domain.catalogs.Country;
import com.example.domain.catalogs.Employee;
import com.example.domain.catalogs.Property;
import com.example.domain.documents.Bill;
import com.example.domain.documents.Booking;
import com.example.domain.documents.Guest;
import com.example.domain.documents.Payment;
import com.example.domain.enumerations.DocType;
import com.example.repositories.BankAccountRepository;
import com.example.repositories.BillRepository;
import com.example.repositories.BookingRepository;
import com.example.repositories.ClientRepository;
import com.example.repositories.CountryRepository;
import com.example.repositories.EmployeeRepository;
import com.example.repositories.PaymentRepository;
import com.example.repositories.PropertyRepository;
import com.onec.posting.PostingService;
import com.onec.types.Ref;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Seeds realistic rental-business data on first launch. Skips when the Properties catalog
 * already has rows. Volume: ~30 countries, 5 properties, 3 bank accounts, ~50 clients,
 * ~120 bookings (2022-2023), ~80 bills, ~60 payments — all posted.
 */
@Component
@Order(100)
public class RentalSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(RentalSeeder.class);

    private final PropertyRepository properties;
    private final ClientRepository clients;
    private final CountryRepository countries;
    private final BankAccountRepository banks;
    private final EmployeeRepository employees;
    private final BookingRepository bookings;
    private final BillRepository bills;
    private final PaymentRepository payments;
    private final PostingService postingService;

    private final Random rnd = new Random(42);

    public RentalSeeder(PropertyRepository properties, ClientRepository clients,
                        CountryRepository countries, BankAccountRepository banks,
                        EmployeeRepository employees,
                        BookingRepository bookings, BillRepository bills,
                        PaymentRepository payments, PostingService postingService) {
        this.properties = properties;
        this.clients = clients;
        this.countries = countries;
        this.banks = banks;
        this.employees = employees;
        this.bookings = bookings;
        this.bills = bills;
        this.payments = payments;
        this.postingService = postingService;
    }

    @Override
    public void run(String... args) {
        if (properties.count() > 0) {
            log.info("RentalSeeder: data already present ({} properties), skipping", properties.count());
            return;
        }
        log.info("RentalSeeder: populating demo dataset…");
        Map<String, Country> countryByName = seedCountries();
        List<Property> props = seedProperties();
        List<BankAccount> bankAccounts = seedBankAccounts();
        List<Employee> staff = seedEmployees();
        List<Client> clientList = seedClients(countryByName);
        seedTransactions(props, bankAccounts, staff, clientList);
        log.info("RentalSeeder: done. {} properties, {} employees, {} clients, {} bookings, {} bills, {} payments",
                properties.count(), employees.count(), clients.count(), bookings.count(), bills.count(), payments.count());
    }

    // ------------------------------ catalogs ------------------------------

    private Map<String, Country> seedCountries() {
        Object[][] data = {
                {"Spain", "Spanish", "ES"}, {"France", "French", "FR"}, {"Germany", "German", "DE"},
                {"United Kingdom", "British", "GB"}, {"Italy", "Italian", "IT"},
                {"Netherlands", "Dutch", "NL"}, {"Belgium", "Belgian", "BE"},
                {"Switzerland", "Swiss", "CH"}, {"Austria", "Austrian", "AT"},
                {"Portugal", "Portuguese", "PT"}, {"Ireland", "Irish", "IE"},
                {"Sweden", "Swedish", "SE"}, {"Norway", "Norwegian", "NO"},
                {"Denmark", "Danish", "DK"}, {"Finland", "Finnish", "FI"},
                {"Poland", "Polish", "PL"}, {"Czech Republic", "Czech", "CZ"},
                {"Hungary", "Hungarian", "HU"}, {"Greece", "Greek", "GR"},
                {"Russia", "Russian", "RU"}, {"Ukraine", "Ukrainian", "UA"},
                {"USA", "American", "US"}, {"Canada", "Canadian", "CA"},
                {"Australia", "Australian", "AU"}, {"Brazil", "Brazilian", "BR"},
                {"Argentina", "Argentinian", "AR"}, {"Mexico", "Mexican", "MX"},
                {"Japan", "Japanese", "JP"}, {"China", "Chinese", "CN"},
                {"India", "Indian", "IN"}
        };
        Map<String, Country> map = new HashMap<>();
        for (Object[] row : data) {
            Country c = new Country();
            c.setDescription((String) row[0]);
            c.setName((String) row[0]);
            c.setNationality((String) row[1]);
            c.setIso2((String) row[2]);
            map.put((String) row[0], countries.save(c));
        }
        log.info("  seeded {} countries", map.size());
        return map;
    }

    private List<Property> seedProperties() {
        Object[][] data = {
                {"Can Joe", "Carrer Major 12, Cadaqués", 6, "220.00", "90.00"},
                {"Can Toni", "Carrer del Mar 5, Cadaqués", 4, "180.00", "60.00"},
                {"Pool Place", "Camí del Far 18, Cadaqués", 8, "280.00", "120.00"},
                {"Loft", "Plaça Doctor Pont 3, Cadaqués", 2, "90.00", "40.00"},
                {"Garden Suite", "Carrer Nou 22, Cadaqués", 4, "140.00", "60.00"},
        };
        List<Property> out = new ArrayList<>();
        for (Object[] row : data) {
            Property p = new Property();
            p.setDescription((String) row[0]);
            p.setDisplayName((String) row[0]);
            p.setAddress((String) row[1]);
            p.setCapacityAdults((Integer) row[2]);
            p.setDefaultNightRate(new BigDecimal((String) row[3]));
            p.setCleaningFee(new BigDecimal((String) row[4]));
            out.add(properties.save(p));
        }
        log.info("  seeded {} properties", out.size());
        return out;
    }

    private List<BankAccount> seedBankAccounts() {
        Object[][] data = {
                {"Michel Joseph de Geofroy", "ES57 0061 0032 5901 1243 0212", "BSABESBB", "Banco Sabadell"},
                {"Michel George de Geofroy", "ES54 0061 0032 5201 1707 0119", "BSABESBB", "Banco Sabadell"},
                {"Anna Petrova",            "ES25 2100 0187 1402 0054 8746", "CAIXESBB", "La Caixa"},
        };
        List<BankAccount> out = new ArrayList<>();
        for (Object[] row : data) {
            BankAccount b = new BankAccount();
            b.setNominee((String) row[0]);
            b.setIban((String) row[1]);
            b.setBic((String) row[2]);
            b.setBankName((String) row[3]);
            b.setDescription(((String) row[1]).substring(((String) row[1]).length() - 4) + " · " + row[0]);
            out.add(banks.save(b));
        }
        log.info("  seeded {} bank accounts", out.size());
        return out;
    }

    private List<Employee> seedEmployees() {
        Object[][] data = {
                // {full name,             role,         department,    hourly, hired,        email,                       mobile,            avatar slug}
                {"Cynthia Reyes",          "Cleaner",    "Housekeeping","15.00", "2018-04-12", "cynthia@rentals.local",     "+34 628 437 651", "cynthia"},
                {"Sheri Wallace",          "Cleaner",    "Housekeeping","15.00", "2019-06-03", "sheri@rentals.local",       "+34 603 210 819", "sheri"},
                {"Irina Volkova",          "Cleaner",    "Housekeeping","15.00", "2021-03-22", "irina@rentals.local",       "+34 666 076 517", "irina"},
                {"Marc Dubois",            "Host",       "Operations",  "22.00", "2017-09-01", "marc@rentals.local",        "+34 612 884 220", "marc"},
                {"Lucía Roca",             "Manager",    "Operations",  "30.00", "2016-01-10", "lucia@rentals.local",       "+34 600 119 042", "lucia"},
                {"Tomás Pérez",            "Maintenance","Maintenance", "18.00", "2020-11-18", "tomas@rentals.local",       "+34 678 552 941", "tomas"},
                {"Aleix Garrido",          "Gardener",   "Maintenance", "16.00", "2022-02-07", "aleix@rentals.local",       "+34 660 770 318", "aleix"},
        };
        List<Employee> out = new ArrayList<>();
        for (Object[] row : data) {
            Employee e = new Employee();
            e.setFullName((String) row[0]);
            e.setDescription((String) row[0]);
            e.setRole((String) row[1]);
            e.setDepartment((String) row[2]);
            e.setHourlyRate(new BigDecimal((String) row[3]));
            e.setHiredOn(LocalDate.parse((String) row[4]));
            e.setEmail((String) row[5]);
            e.setMobile((String) row[6]);
            e.setAvatarUrl("https://i.pravatar.cc/96?u=" + row[7]);
            e.setActive(true);
            out.add(employees.save(e));
        }
        log.info("  seeded {} employees", out.size());
        return out;
    }

    private record NamePool(String country, String[] firstNames, String[] lastNames) {}

    private List<Client> seedClients(Map<String, Country> countryByName) {
        NamePool[] pools = {
                new NamePool("Spain",
                        new String[]{"Antonio", "María", "Carmen", "Javier", "Lucía", "Manuel", "Paula", "Diego"},
                        new String[]{"García", "López", "Martínez", "Sánchez", "Rodríguez", "Fernández", "González"}),
                new NamePool("France",
                        new String[]{"Pierre", "Marie", "Jean", "Sophie", "Luc", "Élodie", "Hugo", "Camille"},
                        new String[]{"Dupont", "Martin", "Bernard", "Petit", "Moreau", "Laurent", "Durand"}),
                new NamePool("Germany",
                        new String[]{"Klaus", "Anna", "Hans", "Petra", "Stefan", "Julia", "Markus", "Lena"},
                        new String[]{"Müller", "Schmidt", "Schneider", "Fischer", "Weber", "Becker", "Wagner"}),
                new NamePool("United Kingdom",
                        new String[]{"James", "Emily", "Oliver", "Charlotte", "Henry", "Sophie", "George", "Amelia"},
                        new String[]{"Smith", "Brown", "Taylor", "Wilson", "Davies", "Walker", "Robinson"}),
                new NamePool("Italy",
                        new String[]{"Marco", "Giulia", "Luca", "Sofia", "Andrea", "Chiara", "Matteo", "Valentina"},
                        new String[]{"Rossi", "Russo", "Ferrari", "Esposito", "Bianchi", "Romano", "Ricci"}),
                new NamePool("Netherlands",
                        new String[]{"Jan", "Anouk", "Pieter", "Saskia", "Bram", "Eva", "Tim"},
                        new String[]{"de Vries", "van den Berg", "Bakker", "Visser", "Smit", "Meijer"}),
                new NamePool("USA",
                        new String[]{"Michael", "Jennifer", "David", "Sarah", "Christopher", "Jessica", "Robert", "Ashley"},
                        new String[]{"Johnson", "Williams", "Jones", "Miller", "Davis", "Anderson", "Thomas"}),
                new NamePool("Switzerland",
                        new String[]{"Lukas", "Lara", "Reto", "Andrea"},
                        new String[]{"Meier", "Keller", "Huber"}),
        };
        List<Client> out = new ArrayList<>();
        DocType[] passportLikely = {DocType.PASSPORT, DocType.PASSPORT, DocType.PASSPORT, DocType.NATIONAL_ID};

        for (int i = 0; i < 60; i++) {
            NamePool pool = pools[i % pools.length];
            Country country = countryByName.get(pool.country());
            String first = pick(pool.firstNames());
            String last1 = pick(pool.lastNames());
            String last2 = pool.country().equals("Spain") && rnd.nextBoolean() ? pick(pool.lastNames()) : "";

            Client c = new Client();
            c.setFirstName(first);
            c.setLastName1(last1);
            c.setLastName2(last2);
            c.setDescription((first + " " + last1 + " " + last2).trim());
            // gender / docType left null — Spring Data JDBC enum-as-UUID conversion not yet wired
            c.setBirthday(LocalDate.of(1955 + rnd.nextInt(50), 1 + rnd.nextInt(12), 1 + rnd.nextInt(28)));
            DocType assumedDocType = pool.country().equals("Spain") ? DocType.NATIONAL_ID : passportLikely[rnd.nextInt(passportLikely.length)];
            c.setDocNumber(generateDocNumber(assumedDocType));
            c.setDocIssuedOn(LocalDate.of(2010 + rnd.nextInt(13), 1 + rnd.nextInt(12), 1 + rnd.nextInt(28)));
            c.setAddress(rnd.nextInt(200) + 1 + " " + pick(new String[]{"Main", "High", "Park", "Oak", "Pine"}) + " St");
            c.setCity(pick(new String[]{"Barcelona", "Paris", "Berlin", "London", "Milan", "Amsterdam", "New York", "Zurich"}));
            c.setPostCode(String.format("%05d", rnd.nextInt(99999)));
            c.setEmail(simpleEmail(first, last1));
            c.setMobile("+" + (30 + rnd.nextInt(70)) + " " + (600 + rnd.nextInt(400)) + " " + (100000 + rnd.nextInt(900000)));
            if (country != null) {
                Ref<Country> cref = Ref.of(Country.class, country.getId());
                c.setNationality(cref);
                c.setCountry(cref);
            }
            out.add(clients.save(c));
        }
        log.info("  seeded {} clients", out.size());
        return out;
    }

    // ------------------------------ documents ------------------------------

    private void seedTransactions(List<Property> props, List<BankAccount> bankAccounts,
                                  List<Employee> staff, List<Client> clientList) {
        // Hosts/managers actually take bookings; cleaners/maintenance get assigned less often.
        List<Employee> assignable = staff.stream()
                .filter(e -> {
                    String r = e.getRole();
                    return "Host".equals(r) || "Manager".equals(r) || "Cleaner".equals(r);
                })
                .toList();
        int bookingsCreated = 0, billsCreated = 0, paymentsCreated = 0;
        boolean cancelNext;

        for (int i = 0; i < 140; i++) {
            Property property = props.get(rnd.nextInt(props.size()));
            Client primary = clientList.get(rnd.nextInt(clientList.size()));

            // Distribute around today: ~95% within ±180 days, clustering near now.
            int dayOffset = (int) Math.round(rnd.nextGaussian() * 90);
            LocalDate checkIn = LocalDate.now().plusDays(dayOffset);
            int nights = 3 + rnd.nextInt(11); // 3..13
            LocalDate checkOut = checkIn.plusDays(nights);

            int adults = 1 + rnd.nextInt(Math.max(1, property.getCapacityAdults() - 1));
            int children = rnd.nextInt(3);

            BigDecimal nightRate = property.getDefaultNightRate()
                    .multiply(BigDecimal.valueOf(0.9 + rnd.nextDouble() * 0.4));
            nightRate = nightRate.setScale(2, java.math.RoundingMode.HALF_UP);

            cancelNext = rnd.nextInt(20) == 0;

            Booking b = new Booking();
            b.setDate(LocalDateTime.of(checkIn, java.time.LocalTime.NOON));
            b.setProperty(Ref.of(Property.class, property.getId()));
            // status / channel left null; cancellation marked via deletionMark below
            b.setCheckIn(checkIn);
            b.setCheckOut(checkOut);
            b.setAdults(adults);
            b.setChildren(children);
            b.setNightRate(nightRate);
            b.setCleaningFee(property.getCleaningFee());
            b.setPrimaryClient(Ref.of(Client.class, primary.getId()));
            if (!assignable.isEmpty()) {
                Employee assignee = assignable.get(rnd.nextInt(assignable.size()));
                b.setAssignedTo(Ref.of(Employee.class, assignee.getId()));
            }
            // Travelers as a tabular section — persisted end-to-end through the typed
            // repository (bookings.save) now that #24 maps tabular sections in Spring Data JDBC.
            b.getGuests().add(guest(primary.getId(), true, false));
            for (int g = 1; g < adults; g++) {
                Client extraAdult = clientList.get(rnd.nextInt(clientList.size()));
                b.getGuests().add(guest(extraAdult.getId(), false, false));
            }
            for (int c = 0; c < children; c++) {
                Client child = clientList.get(rnd.nextInt(clientList.size()));
                b.getGuests().add(guest(child.getId(), false, true));
            }

            Booking saved = bookings.save(b);
            bookingsCreated++;
            if (!cancelNext) {
                try {
                    postingService.post(saved);
                } catch (Exception e) {
                    log.warn("Failed to post booking {}: {}", saved.getNumber(), e.getMessage());
                }
            }

            // Bill in ~70% of non-cancelled bookings
            if (!cancelNext && rnd.nextInt(10) < 7) {
                BigDecimal gross = nightRate.multiply(BigDecimal.valueOf(nights)).add(property.getCleaningFee());
                BigDecimal ivaPct = new BigDecimal("10");
                BigDecimal net = gross.divide(BigDecimal.valueOf(1.10), 2, java.math.RoundingMode.HALF_UP);

                Bill bill = new Bill();
                bill.setDate(LocalDateTime.of(checkOut, java.time.LocalTime.of(10, 0)));
                bill.setClient(Ref.of(Client.class, primary.getId()));
                bill.setProperty(Ref.of(Property.class, property.getId()));
                bill.setBooking(Ref.of(Booking.class, saved.getId()));
                bill.setNet(net);
                bill.setIvaPercent(ivaPct);
                bill.setComments("Stay " + checkIn + " → " + checkOut + " (" + nights + " nights)");
                Bill billSaved = bills.save(bill);
                billsCreated++;
                try {
                    postingService.post(billSaved);
                } catch (Exception e) {
                    log.warn("Failed to post bill {}: {}", billSaved.getNumber(), e.getMessage());
                }

                // Payment in ~80% of bills
                if (rnd.nextInt(10) < 8) {
                    Payment pay = new Payment();
                    pay.setDate(LocalDateTime.of(checkOut.plusDays(rnd.nextInt(14)), java.time.LocalTime.of(15, 0)));
                    pay.setClient(Ref.of(Client.class, primary.getId()));
                    BankAccount acc = bankAccounts.get(rnd.nextInt(bankAccounts.size()));
                    pay.setAccount(Ref.of(BankAccount.class, acc.getId()));
                    // method left null pending Spring Data JDBC enum-as-UUID converter wiring
                    pay.setBill(Ref.of(Bill.class, billSaved.getId()));
                    pay.setAmount(billSaved.getGross());
                    Payment paySaved = payments.save(pay);
                    paymentsCreated++;
                    try {
                        postingService.post(paySaved);
                    } catch (Exception e) {
                        log.warn("Failed to post payment {}: {}", paySaved.getNumber(), e.getMessage());
                    }
                }
            }
        }
        log.info("  seeded {} bookings, {} bills, {} payments", bookingsCreated, billsCreated, paymentsCreated);
    }

    // ------------------------------ helpers ------------------------------

    private Guest guest(java.util.UUID clientId, boolean mainGuest, boolean isChild) {
        Guest g = new Guest();
        g.setClient(Ref.of(Client.class, clientId));
        g.setMainGuest(mainGuest);
        g.setChild(isChild);
        return g;
    }

    private String pick(String[] options) {
        return options[rnd.nextInt(options.length)];
    }

    private String simpleEmail(String first, String last) {
        String slug = (first + "." + last).toLowerCase()
                .replaceAll("[^a-z0-9.]", "");
        String[] domains = {"example.com", "mail.test", "demo.local"};
        return slug + "@" + domains[rnd.nextInt(domains.length)];
    }

    private String generateDocNumber(DocType type) {
        String prefix = switch (type) {
            case PASSPORT -> "P";
            case NATIONAL_ID -> "ID";
            case DRIVING_LICENSE -> "DL";
            default -> "X";
        };
        return prefix + String.format("%08d", rnd.nextInt(99_999_999));
    }
}
