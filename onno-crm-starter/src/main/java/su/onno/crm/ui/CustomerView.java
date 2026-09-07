package su.onno.crm.ui;

import org.springframework.stereotype.Component;
import su.onno.crm.domain.Customer;
import su.onno.ui.EntityConfigBuilder;
import su.onno.ui.EntityView;
import su.onno.ui.ListSpec;

@Component
public class CustomerView implements EntityView<Customer> {

    @Override public Class<Customer> entity() { return Customer.class; }

    @Override
    public void list(ListSpec<Customer> list) {
        list.selectionCheckboxes(true).selectionWidget("crmContactMerge");
        list.columns(Customer::getDescription, Customer::getStage, Customer::getCompany,
                        Customer::getEmail, Customer::getPhone, Customer::getOwner)
                .label(Customer::getDescription, "Name")
                .sortBy(Customer::getDescription);
        list.filter(Customer::getStage).label("Lifecycle").multiOptions();
        list.filter(Customer::getCompany).label("Company").contains();
    }

    @Override
    public void fields(EntityConfigBuilder<Customer> fields) {
        fields.field(Customer::getDescription).order(0).label("Name").group("Contact").width("1/2")
                .field(Customer::getCompany).order(1).group("Contact").width("1/2")
                .field(Customer::getEmail).order(2).group("Contact").width("1/2")
                .field(Customer::getPhone).order(3).group("Contact").width("1/2")
                .field(Customer::getAvatarUrl).order(4).group("Contact").widget("avatar").label("Photo")
                .field(Customer::getStage).order(10).group("Relationship").width("1/2")
                .field(Customer::getOwner).order(11).group("Relationship").width("1/2")
                .field(Customer::getSource).order(12).group("Relationship").width("1/2")
                .field(Customer::getCity).order(13).group("Relationship").width("1/2")
                .field(Customer::getTags).order(14).group("Relationship").hideInForm().hideInDetail().hideInList();
    }

    @Override public void detail(su.onno.ui.DetailSpec<Customer> detail) {
        detail.widget("Contact workspace").type("crmContactDetails");
    }

    @Override public boolean comments() { return true; }
}
