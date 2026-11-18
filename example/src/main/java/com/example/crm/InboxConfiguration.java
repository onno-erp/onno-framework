package com.example.crm;

import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.boot.ApplicationRunner;
import com.example.domain.catalogs.Customer;
import com.example.domain.catalogs.Employee;
import com.example.repositories.CustomerRepository;
import com.example.repositories.EmployeeRepository;
import com.example.repositories.OrderRepository;
import su.onno.crm.domain.*;
import su.onno.crm.repository.*;
import su.onno.crm.service.*;
import su.onno.types.Ref;

/**
 * Run with the crm profile to add an inbox to the bookstore's existing catalogs. The inbox page
 * itself ({@code /support}) is React: {@code src/main/ui/pages/SupportPage.tsx}, registered in
 * {@code main.tsx} when the CRM is present.
 */
@Configuration
@Profile("crm")
public class InboxConfiguration {
    @Bean CrmCustomerBinding<Customer> bookstoreContacts(CustomerRepository customers) {
        var catalog = new CrmCatalogBinding<>(Customer.class, customers::findActiveById)
                .field("email", Customer::getEmail)
                .field("phone", Customer::getPhone)
                .field("city", Customer::getCity);
        return new CrmCustomerBinding<>(catalog, incoming -> {
            // This app's policy creates a distinct customer for a new channel identity.
            // Apps may instead resolve an existing record, require review, or reject unknown peers.
            var customer = new Customer();
            customer.setDescription(incoming.name());
            customer.setEmail(incoming.email());
            customer.setPhone(incoming.phone());
            return Ref.of(Customer.class, customers.save(customer).getId());
        });
    }
    @Bean CrmAgentBinding<Employee> bookstoreAgents(EmployeeRepository employees) {
        return new CrmAgentBinding<>(new CrmCatalogBinding<>(Employee.class, employees::findActiveById)
                .field("avatarUrl", Employee::getAvatarUrl), user -> {
            if(user==null || !"Employees".equals(user.entityName()) || user.recordId()==null)return Optional.empty();
            return Optional.of(UUID.fromString(user.recordId()));
        });
    }
    /** Which conversations the support inbox holds and who may use it; how it presents is {@code SupportPage.tsx}. */
    @Bean CrmInboxWorkspace bookstoreInbox() {
        return new CrmInboxWorkspace("support", "Customer support", Set.of("MANAGER"), conversation -> true);
    }
    @Bean ApplicationRunner inboxSample(CustomerRepository customers, InboxRepository inboxes,
            ConversationRepository conversations, ConversationMessageRepository messages, OrderRepository orders) {
        return args -> {
            seedOrderChats(customers, inboxes, conversations, messages, orders);
            if(conversations.findAllActive().isEmpty()) {
                var customer=new Customer();customer.setDescription("Book club coordinator");customer.setEmail("books@example.test");customers.save(customer);
                var inbox=new Inbox();inbox.setDescription("Bookstore support");inbox.setChannel(Channel.EMAIL);inbox.setAddress("support@example.test");inboxes.save(inbox);
                var conversation=new Conversation();conversation.setCustomer(customer.getId());conversation.setInbox(Ref.of(Inbox.class,inbox.getId()));
                conversation.setChannel(Channel.EMAIL);conversation.setSubject("Book club order enquiry");conversation.setDescription(conversation.getSubject());conversations.save(conversation);
            }
            // A busier inbox, seeded once: website chats (which reply locally through
            // SampleWebChatTransport), email and Instagram, with read and unread threads.
            if(inboxes.findAllActive().stream().anyMatch(i->WEB_CHAT_ADDRESS.equals(i.getAddress())))return;
            var chat=inbox(inboxes,"Website chat",Channel.WEB_CHAT,WEB_CHAT_ADDRESS,"#6366F1");
            var mail=inbox(inboxes,"Orders mailbox",Channel.EMAIL,"orders@onnobooks.local","#0EA5E9");
            var insta=inbox(inboxes,"Instagram · @onnobooks",Channel.INSTAGRAM,"@onnobooks","#EC4899");
            var now=java.time.LocalDateTime.now();
            for(var t:SAMPLE_THREADS) {
                var customer=new Customer();customer.setDescription(t.name());customer.setEmail(t.email());customer.setPhone(t.phone());customer.setCity(t.city());
                customers.save(customer);
                var inbox=switch(t.channel()){case Channel.EMAIL->mail;case Channel.INSTAGRAM->insta;default->chat;};
                var conversation=new Conversation();conversation.setCustomer(customer.getId());conversation.setInbox(Ref.of(Inbox.class,inbox.getId()));
                conversation.setChannel(t.channel());conversation.setSubject(t.subject());conversation.setDescription(t.subject());
                conversation.setUnreadCount(t.unread());
                conversations.save(conversation);
                java.time.LocalDateTime last=null;String preview=null;
                for(var m:t.messages()) {
                    var message=new ConversationMessage();
                    message.setConversation(Ref.of(Conversation.class,conversation.getId()));
                    message.setChannel(t.channel());
                    boolean inbound=m.inbound();
                    message.setKind(inbound?MessageKind.CUSTOMER_MESSAGE:MessageKind.AGENT_REPLY);
                    message.setDirection(inbound?MessageDirection.INBOUND:MessageDirection.OUTBOUND);
                    message.setAuthorName(inbound?t.name():"Mara Ellis");
                    message.setBody(m.text());message.setDescription(m.text().length()>100?m.text().substring(0,100):m.text());
                    if(Channel.EMAIL.equals(t.channel()))message.setSubject(t.subject());
                    message.setSentAt(now.minusMinutes(m.minutesAgo()));
                    message.setDeliveryStatus(inbound?DeliveryStatus.RECEIVED:DeliveryStatus.DELIVERED);
                    messages.save(message);
                    last=message.getSentAt();preview=m.text();
                }
                conversation.setLastMessageAt(last);
                conversation.setLastMessagePreview(preview.length()>180?preview.substring(0,180):preview);
                conversations.save(conversation);
            }
        };
    }

    private static final String ORDER_CHAT_SUBJECT="Question about my order";

    /**
     * Two chats with customers who already bought from the shop, so the support page's order cards
     * (a timeline source in SupportPage.tsx) have orders to show beside the messages. Seeded once.
     */
    private static void seedOrderChats(CustomerRepository customers, InboxRepository inboxes, ConversationRepository conversations,
            ConversationMessageRepository messages, OrderRepository orders) {
        if(conversations.findAllActive().stream().anyMatch(c->ORDER_CHAT_SUBJECT.equals(c.getSubject())))return;
        var chat=inboxes.findAllActive().stream().filter(i->WEB_CHAT_ADDRESS.equals(i.getAddress())).findFirst()
            .orElseGet(()->inbox(inboxes,"Website chat",Channel.WEB_CHAT,WEB_CHAT_ADDRESS,"#6366F1"));
        var buyers=orders.findAllActive().stream().map(order->order.getCustomer()).filter(Objects::nonNull)
            .map(ref->ref.id()).distinct().limit(2).toList();
        var now=java.time.LocalDateTime.now();
        int index=0;
        for(var buyerId:buyers) {
            var customer=customers.findActiveById(buyerId).orElse(null);
            if(customer==null)continue;
            var conversation=new Conversation();conversation.setCustomer(customer.getId());conversation.setInbox(Ref.of(Inbox.class,chat.getId()));
            conversation.setChannel(Channel.WEB_CHAT);conversation.setSubject(ORDER_CHAT_SUBJECT);conversation.setDescription(ORDER_CHAT_SUBJECT);
            conversation.setUnreadCount(1);
            conversations.save(conversation);
            String text=index++==0?"Hi! Has my last order shipped yet? I need the books by Friday.":"Could I add one more book to my order before it ships?";
            var message=new ConversationMessage();
            message.setConversation(Ref.of(Conversation.class,conversation.getId()));
            message.setChannel(Channel.WEB_CHAT);message.setKind(MessageKind.CUSTOMER_MESSAGE);message.setDirection(MessageDirection.INBOUND);
            message.setAuthorName(customer.getDescription());message.setBody(text);message.setDescription(text);
            message.setSentAt(now.minusMinutes(3+index));message.setDeliveryStatus(DeliveryStatus.RECEIVED);
            messages.save(message);
            conversation.setLastMessageAt(message.getSentAt());conversation.setLastMessagePreview(text);
            conversations.save(conversation);
        }
    }

    private static final String WEB_CHAT_ADDRESS="onnobooks.local/chat";

    private static Inbox inbox(InboxRepository inboxes,String name,String channel,String address,String color) {
        var inbox=new Inbox();inbox.setDescription(name);inbox.setChannel(channel);inbox.setAddress(address);inbox.setAccentColor(color);
        return inboxes.save(inbox);
    }

    private record Line(boolean inbound,String text,long minutesAgo) {}
    private record SampleThread(String name,String email,String phone,String city,String channel,String subject,int unread,List<Line> messages) {}
    private static Line in(long minutesAgo,String text) { return new Line(true,text,minutesAgo); }
    private static Line out(long minutesAgo,String text) { return new Line(false,text,minutesAgo); }

    /** Fictional customers and conversations. */
    private static final List<SampleThread> SAMPLE_THREADS=List.of(
        new SampleThread("Olivia Martin","olivia.martin@example.test","+44 20 7946 0101","London",Channel.WEB_CHAT,"Signed copies of The Night Library",2,List.of(
            in(190,"Hi! Do you still have signed copies of The Night Library?"),
            out(185,"Hello Olivia, we have three left. Shall I reserve one for you?"),
            in(30,"Yes please! Could I collect it on Saturday?"),
            in(28,"Also, is gift wrapping possible?"))),
        new SampleThread("Mia Chen","mia.chen@example.test","+1 415 555 0142","San Francisco",Channel.EMAIL,"Order SO-1042: delivery date",1,List.of(
            in(2900,"Hello, I ordered five books last week (SO-1042). When will they ship?"),
            out(2860,"Hi Mia, the last title arrives from the publisher on Thursday. We ship the same day."),
            in(55,"Thank you! Can you send it to my office address instead? 500 Market St, 3rd floor."))),
        new SampleThread("Amelia Rossi","amelia.rossi@example.test","+39 06 5555 0199","Rome",Channel.INSTAGRAM,"Italian translations",0,List.of(
            in(1500,"Ciao! Do you stock Italian translations of children's books?"),
            out(1440,"Ciao Amelia! A small shelf, mostly picture books. I can send you the list."),
            in(1430,"Perfect, grazie!"),
            out(1420,"Here it is: Il piccolo principe, Pippi Calzelunghe, and La gabbianella e il gatto."))),
        new SampleThread("Grace Thompson","grace.t@example.test","+44 161 555 0177","Manchester",Channel.WEB_CHAT,"School order: 30 copies",1,List.of(
            in(4300,"Hi, I'm a teacher at Northfield Primary. We need 30 copies of Wonder for the spring term."),
            out(4280,"Hello Grace! We can do that with a 15% school discount. Would you like a quote?"),
            in(4270,"Yes, please. Delivery before 10 January?"),
            out(4200,"Quote sent to grace.t@example.test. Delivery on 8 January works."),
            in(12,"Approved by the head! How do I pay, by invoice?"))),
        new SampleThread("Isabella Romero","isabella.romero@example.test","+34 91 555 0123","Madrid",Channel.EMAIL,"Missing page in my copy",0,List.of(
            in(8600,"My copy of Cloud Atlas is missing pages 211 to 226."),
            out(8580,"I'm so sorry, Isabella. We'll send a replacement today, and there's no need to return the faulty one."),
            in(7200,"The replacement arrived, thank you!"))),
        new SampleThread("Lucy Morgan","lucy.morgan@example.test","+1 212 555 0155","New York",Channel.WEB_CHAT,"Book club pick for March",0,List.of(
            in(15000,"Our book club wants something short and discussable for March. Ideas?"),
            out(14950,"How about Small Things Like These by Claire Keegan? About 120 pages, and it's great for discussion."),
            in(14900,"Love it. 12 copies please."))),
        new SampleThread("Chloé Laurent","chloe.laurent@example.test","+33 1 55 55 01 88","Paris",Channel.INSTAGRAM,"Event: author reading",1,List.of(
            in(380,"Bonjour! Is the author reading on Friday open to everyone?"),
            in(379,"And should I book a seat?"))),
        new SampleThread("Priya Sharma","priya.sharma@example.test","+91 22 5555 0166","Mumbai",Channel.EMAIL,"Bulk order for the library",0,List.of(
            in(26000,"We'd like to order 120 titles for the college library. Do you offer institutional pricing?"),
            out(25900,"Hello Priya, yes: 20% off orders over 100 books, plus free delivery. Send us the list whenever you're ready."))),
        new SampleThread("Sofia & Marco","sofia.marco@example.test","+39 02 5555 0111","Milan",Channel.WEB_CHAT,"Wedding guest book",0,List.of(
            in(52000,"We'd love a linen-bound guest book for our wedding. Do you do custom covers?"),
            out(51900,"Congratulations! Yes: linen, gold-foil initials, about two weeks to make."))),
        new SampleThread("Daniel Okafor","daniel.okafor@example.test","+234 1 555 0133","Lagos",Channel.WEB_CHAT,"Gift card balance",1,List.of(
            in(6,"Hi, how can I check the balance on my gift card? The number ends in 4821."))),
        new SampleThread("Hannah Becker","hannah.becker@example.test","+49 30 5555 0144","Berlin",Channel.EMAIL,"Preorder: the new Murakami",0,List.of(
            in(9800,"Can I preorder the new Murakami novel? English edition please."),
            out(9790,"Done, Hannah! It's reserved, and we'll email you on release day."),
            in(9700,"Wonderful, thanks."))),
        new SampleThread("Kenji Watanabe","kenji.w@example.test","+81 3 5555 0122","Tokyo",Channel.INSTAGRAM,"Rare edition request",0,List.of(
            in(31000,"Do you ever find first editions of Kafka on the Shore?"),
            out(30950,"Occasionally! I've added you to our rare-books list, and we'll message you if one turns up."))));
}
