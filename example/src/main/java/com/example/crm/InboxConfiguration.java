package com.example.crm;

import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.boot.ApplicationRunner;
import com.example.domain.catalogs.Customer;
import com.example.domain.catalogs.Employee;
import com.example.repositories.CustomerRepository;
import com.example.repositories.EmployeeRepository;
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
            ConversationRepository conversations) {
        return args -> {
            if(!conversations.findAllActive().isEmpty())return;
            var customer=new Customer();customer.setDescription("Book club coordinator");customer.setEmail("books@example.test");customers.save(customer);
            var inbox=new Inbox();inbox.setDescription("Bookstore support");inbox.setChannel(Channel.EMAIL);inbox.setAddress("support@example.test");inboxes.save(inbox);
            var conversation=new Conversation();conversation.setCustomer(customer.getId());conversation.setInbox(Ref.of(Inbox.class,inbox.getId()));
            conversation.setChannel(Channel.EMAIL);conversation.setSubject("Book club order enquiry");conversation.setDescription(conversation.getSubject());conversations.save(conversation);
        };
    }
}
