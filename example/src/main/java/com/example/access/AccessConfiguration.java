package com.example.access;

import com.example.domain.catalogs.Book;
import com.example.domain.catalogs.BookCategory;
import com.example.domain.catalogs.Customer;
import com.example.domain.catalogs.Employee;
import com.example.domain.catalogs.Supplier;
import com.example.domain.catalogs.SupplierOffer;
import com.example.domain.documents.Order;
import com.example.domain.documents.ScheduleEvent;
import com.example.domain.documents.StockReceipt;
import com.example.domain.registers.BookSales;
import com.example.domain.registers.BookStock;
import su.onno.access.CorePermissions;
import su.onno.access.RoleGrants;
import su.onno.auth.AuthPermissions;
import su.onno.crm.CrmPermissions;
import su.onno.print.PrintPermissions;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Who may do what, in one place. Declaring this bean switches the app to deny by default: a role
 * may do exactly what is granted here (post and unpost imply write, write implies read).
 */
@Configuration
public class AccessConfiguration {

    @Bean
    RoleGrants roleGrants() {
        return RoleGrants.of(AppRole.class)
                .role(AppRole.MANAGER, r -> r
                        .write(Book.class, BookCategory.class, Customer.class, Supplier.class,
                                SupplierOffer.class, ScheduleEvent.class)
                        .post(Order.class, StockReceipt.class)
                        .unpost(Order.class, StockReceipt.class)
                        .read(Employee.class, BookSales.class, BookStock.class))
                .role(AppRole.ADMIN, r -> r
                        .writeAll()
                        .postAll()
                        .unpostAll()
                        .permit(CorePermissions.EDIT_SETTINGS,
                                CorePermissions.MODERATE_COMMENTS,
                                CorePermissions.MANAGE_PERIOD_CLOSE,
                                CorePermissions.ADMINISTER_PROCESSES,
                                AuthPermissions.MANAGE_USERS,
                                PrintPermissions.MANAGE_TEMPLATES,
                                CrmPermissions.ADMINISTER));
    }
}
