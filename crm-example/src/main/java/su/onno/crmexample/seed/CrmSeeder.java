package su.onno.crmexample.seed;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import su.onno.crm.domain.Agent;
import su.onno.crm.domain.Channel;
import su.onno.crm.domain.Conversation;
import su.onno.crm.domain.ConversationMessage;
import su.onno.crm.domain.ConversationPriority;
import su.onno.crm.domain.ConversationStatus;
import su.onno.crm.domain.Customer;
import su.onno.crm.domain.CustomerStage;
import su.onno.crm.domain.DeliveryStatus;
import su.onno.crm.domain.Inbox;
import su.onno.crm.domain.MessageDirection;
import su.onno.crm.domain.MessageKind;
import su.onno.crm.domain.Opportunity;
import su.onno.crm.domain.OpportunityStage;
import su.onno.crm.repository.AgentRepository;
import su.onno.crm.repository.ConversationMessageRepository;
import su.onno.crm.repository.ConversationRepository;
import su.onno.crm.repository.CustomerRepository;
import su.onno.crm.repository.InboxRepository;
import su.onno.crm.repository.OpportunityRepository;
import su.onno.types.Ref;
import su.onno.ui.comments.CommentService;

/** Deterministic first-run data that makes the unified inbox useful immediately. */
@Component
public class CrmSeeder implements ApplicationRunner {

    private final AgentRepository agents;
    private final CustomerRepository customers;
    private final InboxRepository inboxes;
    private final ConversationRepository conversations;
    private final ConversationMessageRepository messages;
    private final OpportunityRepository opportunities;
    private final CommentService comments;

    public CrmSeeder(
            AgentRepository agents,
            CustomerRepository customers,
            InboxRepository inboxes,
            ConversationRepository conversations,
            ConversationMessageRepository messages,
            OpportunityRepository opportunities,
            CommentService comments
    ) {
        this.agents = agents;
        this.customers = customers;
        this.inboxes = inboxes;
        this.conversations = conversations;
        this.messages = messages;
        this.opportunities = opportunities;
        this.comments = comments;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!agents.findAllActive().isEmpty()) {
            return;
        }

        Agent alice = agent("Alice Morgan", "alice@onno.crm", "Customer success lead", "alice");
        Agent ben = agent("Ben Carter", "ben@onno.crm", "Sales specialist", "ben");
        Agent maya = agent("Maya Chen", "maya@onno.crm", "CRM manager", "maya");

        Inbox telegram = inbox("Telegram sales", Channel.TELEGRAM, "@onno_sales", "#229ED9");
        Inbox email = inbox("Support email", Channel.EMAIL, "hello@onno.crm", "#6366F1");
        Inbox web = inbox("Website chat", Channel.WEB_CHAT, "onno.crm/chat", "#8B5CF6");

        Customer mike = customer("Mike De Geofroy", CustomerStage.HOT_LEAD, "mike@example.com",
                "+34 600 100 200", "Northstar Studio", alice, "Product demo", "Madrid", "vip,saas");
        Customer ana = customer("Ana Ribeiro", CustomerStage.NEW_LEAD, "ana@lumen.io",
                "+351 910 300 400", "Lumen Labs", ben, "Website", "Lisbon", "trial");
        Customer tom = customer("Tom Becker", CustomerStage.QUALIFIED, "tom@acme.de",
                "+49 151 200 300", "Acme GmbH", ben, "Referral", "Berlin", "b2b,priority");
        Customer leila = customer("Leila Haddad", CustomerStage.CUSTOMER, "leila@atlas.co",
                "+971 50 100 200", "Atlas & Co", alice, "Conference", "Dubai", "customer");
        Customer jamie = customer("Jamie Wilson", CustomerStage.CHURN_RISK, "jamie@harbor.uk",
                "+44 7700 900 123", "Harbor Works", maya, "Partner", "London", "renewal,risk");

        conversation(mike, telegram, alice, "Pricing and rollout", ConversationStatus.OPEN,
                ConversationPriority.HIGH, 2, LocalDateTime.now().minusMinutes(18), List.of(
                        inbound("Hi! Can we start with five seats and expand later?", "Mike", 42),
                        reply("Absolutely. The workspace scales without a migration.", "Alice Morgan", 31),
                        inbound("Great — can you send the annual pricing too?", "Mike", 18)));

        conversation(ana, web, null, "Choosing the right plan", ConversationStatus.OPEN,
                ConversationPriority.NORMAL, 1, LocalDateTime.now().minusHours(1), List.of(
                        inbound("We are a team of twelve. Which plan includes shared inboxes?", "Ana", 60)));

        Conversation tomConversation = conversation(tom, email, ben, "Security questionnaire",
                ConversationStatus.WAITING_CUSTOMER,
                ConversationPriority.URGENT, 0, LocalDateTime.now().minusHours(3), List.of(
                        inbound("Our procurement team needs the security answers by Friday.", "Tom", 250),
                        reply("I have attached the completed first section and will send the remainder tomorrow.",
                                "Ben Carter", 180)));
        comments.add("catalogs", "crm_conversations", tomConversation.getId(), ben.getId().toString(),
                ben.getDescription(), "Legal approved the standard DPA. Waiting on infrastructure answers.");

        conversation(leila, telegram, alice, "New team onboarding", ConversationStatus.OPEN,
                ConversationPriority.NORMAL, 1, LocalDateTime.now().minusDays(1), List.of(
                        inbound("Can we arrange an onboarding session for our new support team?", "Leila", 1500)));

        Conversation jamieConversation = conversation(jamie, email, maya, "Renewal concern",
                ConversationStatus.SNOOZED,
                ConversationPriority.HIGH, 0, LocalDateTime.now().minusDays(2), List.of(
                        inbound("Usage is lower than expected, so we may reduce seats at renewal.", "Jamie", 3200)));
        comments.add("catalogs", "crm_conversations", jamieConversation.getId(), maya.getId().toString(),
                maya.getDescription(), "Prepare a usage review and adoption plan before replying.");

        opportunity("Northstar annual rollout", mike, alice, OpportunityStage.PROPOSAL,
                "18000", 65, 21, "Send annual pricing and implementation timeline.");
        opportunity("Acme service desk", tom, ben, OpportunityStage.NEGOTIATION,
                "42000", 80, 14, "Complete security review with procurement.");
        opportunity("Lumen shared inbox", ana, ben, OpportunityStage.DISCOVERY,
                "9600", 25, 45, "Confirm channel and seat requirements.");
        opportunity("Harbor renewal", jamie, maya, OpportunityStage.QUALIFIED,
                "24000", 45, 30, "Run adoption review with the account team.");

        seedLeadEvents();
    }

    /** Add durable lead activity to the freshly seeded conversations. */
    private void seedLeadEvents() {
        for (Conversation conversation : conversations.findAllActive()) {
            List<ConversationMessage> existing = messages
                    .findByConversationAndDeletionMarkFalseOrderBySentAtAsc(
                            Ref.of(Conversation.class, conversation.getId()));
            if (existing.stream().anyMatch(message -> message.getKind() == MessageKind.SYSTEM_EVENT)) {
                continue;
            }
            Customer customer = customers.findActiveById(conversation.getCustomer().id()).orElse(null);
            if (customer == null) {
                continue;
            }
            LocalDateTime firstMessageAt = existing.stream()
                    .map(ConversationMessage::getSentAt)
                    .min(LocalDateTime::compareTo)
                    .orElse(conversation.getLastMessageAt() == null
                            ? LocalDateTime.now()
                            : conversation.getLastMessageAt());
            saveSystemEvent(conversation, "Lead created from " + customer.getSource(),
                    firstMessageAt.minusMinutes(30));
            saveSystemEvent(conversation, "Lifecycle stage set to " + stageLabel(customer.getStage()),
                    firstMessageAt.minusMinutes(20));
            if (conversation.getAssignee() != null) {
                agents.findActiveById(conversation.getAssignee().id()).ifPresent(agent ->
                        saveSystemEvent(conversation, "Assigned to " + agent.getDescription(),
                                firstMessageAt.minusMinutes(10)));
            }
        }
    }

    private Agent agent(String name, String email, String title, String avatarSeed) {
        Agent agent = new Agent();
        agent.setDescription(name);
        agent.setEmail(email);
        agent.setJobTitle(title);
        agent.setAvatarUrl(glassAvatar(avatarSeed));
        return agents.save(agent);
    }

    private Inbox inbox(String name, Channel channel, String address, String color) {
        Inbox inbox = new Inbox();
        inbox.setDescription(name);
        inbox.setChannel(channel);
        inbox.setAddress(address);
        inbox.setAccentColor(color);
        return inboxes.save(inbox);
    }

    private Customer customer(
            String name,
            CustomerStage stage,
            String email,
            String phone,
            String company,
            Agent owner,
            String source,
            String city,
            String tags
    ) {
        Customer customer = new Customer();
        customer.setDescription(name);
        customer.setStage(stage);
        customer.setEmail(email);
        customer.setPhone(phone);
        customer.setCompany(company);
        customer.setAvatarUrl(glassAvatar(name));
        customer.setOwner(Ref.of(Agent.class, owner.getId()));
        customer.setSource(source);
        customer.setCity(city);
        customer.setTags(tags);
        return customers.save(customer);
    }

    private Conversation conversation(
            Customer customer,
            Inbox inbox,
            Agent assignee,
            String subject,
            ConversationStatus status,
            ConversationPriority priority,
            int unread,
            LocalDateTime lastAt,
            List<SeedMessage> seedMessages
    ) {
        Conversation conversation = new Conversation();
        conversation.setDescription(customer.getDescription() + " — " + subject);
        conversation.setCustomer(Ref.of(Customer.class, customer.getId()));
        conversation.setInbox(Ref.of(Inbox.class, inbox.getId()));
        conversation.setChannel(inbox.getChannel());
        conversation.setAssignee(assignee == null ? null : Ref.of(Agent.class, assignee.getId()));
        conversation.setSubject(subject);
        conversation.setStatus(status);
        conversation.setPriority(priority);
        conversation.setUnreadCount(unread);
        conversation.setLastMessageAt(lastAt);
        conversation.setLastMessagePreview(seedMessages.get(seedMessages.size() - 1).body());
        conversations.save(conversation);

        List<SeedMessage> ordered = new ArrayList<>(seedMessages);
        ordered.sort((left, right) -> Integer.compare(right.minutesBefore(), left.minutesBefore()));
        for (SeedMessage seed : ordered) {
            ConversationMessage message = new ConversationMessage();
            message.setConversation(Ref.of(Conversation.class, conversation.getId()));
            message.setKind(seed.kind());
            message.setDirection(seed.direction());
            message.setChannel(inbox.getChannel());
            message.setAuthorName(seed.author());
            message.setBody(seed.body());
            message.setDescription(seed.body().length() <= 100 ? seed.body() : seed.body().substring(0, 99) + "…");
            message.setSentAt(LocalDateTime.now().minusMinutes(seed.minutesBefore()));
            message.setDeliveryStatus(seed.direction() == MessageDirection.INBOUND
                    ? DeliveryStatus.RECEIVED
                    : DeliveryStatus.DELIVERED);
            messages.save(message);
        }
        return conversation;
    }

    private void opportunity(
            String name,
            Customer customer,
            Agent owner,
            OpportunityStage stage,
            String value,
            int probability,
            int closeInDays,
            String nextStep
    ) {
        Opportunity opportunity = new Opportunity();
        opportunity.setDescription(name);
        opportunity.setCustomer(Ref.of(Customer.class, customer.getId()));
        opportunity.setOwner(Ref.of(Agent.class, owner.getId()));
        opportunity.setStage(stage);
        opportunity.setAmount(new BigDecimal(value));
        opportunity.setProbability(probability);
        opportunity.setExpectedClose(LocalDate.now().plusDays(closeInDays));
        opportunity.setNextStep(nextStep);
        opportunities.save(opportunity);
    }

    private static SeedMessage inbound(String body, String author, int minutesBefore) {
        return new SeedMessage(MessageKind.CUSTOMER_MESSAGE, MessageDirection.INBOUND, author, body, minutesBefore);
    }

    private static SeedMessage reply(String body, String author, int minutesBefore) {
        return new SeedMessage(MessageKind.AGENT_REPLY, MessageDirection.OUTBOUND, author, body, minutesBefore);
    }

    private void saveSystemEvent(Conversation conversation, String body, LocalDateTime sentAt) {
        ConversationMessage message = new ConversationMessage();
        message.setConversation(Ref.of(Conversation.class, conversation.getId()));
        message.setKind(MessageKind.SYSTEM_EVENT);
        message.setDirection(MessageDirection.INTERNAL);
        message.setChannel(conversation.getChannel());
        message.setAuthorName("CRM");
        message.setBody(body);
        message.setDescription(body);
        message.setSentAt(sentAt);
        message.setDeliveryStatus(DeliveryStatus.NOT_APPLICABLE);
        messages.save(message);
    }

    private static String glassAvatar(String seed) {
        return "https://api.dicebear.com/10.x/glass/svg?seed="
                + URLEncoder.encode(seed == null ? "unknown" : seed, StandardCharsets.UTF_8);
    }

    private static String stageLabel(CustomerStage stage) {
        return switch (stage) {
            case NEW_LEAD -> "New lead";
            case QUALIFIED -> "Qualified";
            case HOT_LEAD -> "Hot lead";
            case CUSTOMER -> "Customer";
            case CHURN_RISK -> "Churn risk";
        };
    }

    private record SeedMessage(
            MessageKind kind,
            MessageDirection direction,
            String author,
            String body,
            int minutesBefore
    ) {}
}
