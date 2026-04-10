package com.example;

import com.example.domain.catalogs.Product;
import com.example.domain.catalogs.Warehouse;
import com.example.domain.documents.GoodsReceipt;
import com.example.domain.documents.Invoice;
import com.example.domain.documents.Sale;
import com.example.domain.registers.PriceRegister;
import com.example.domain.registers.SalesRegister;
import com.example.domain.registers.StockRegister;
import com.onec.ui.OneCUiConfigurer;
import com.onec.ui.UiLayoutBuilder;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UiConfig implements OneCUiConfigurer {

    @Override
    public void configure(UiLayoutBuilder layout) {
        // Sidebar sections
        layout.section("Sales")
                .order(0)
                .icon("file-text")
                .document(Invoice.class)
                .document(Sale.class)
                .register(SalesRegister.class);

        layout.section("Inventory")
                .order(1)
                .icon("bar-chart")
                .catalog(Product.class)
                .catalog(Warehouse.class)
                .document(GoodsReceipt.class)
                .register(StockRegister.class)
                .register(PriceRegister.class);

        // Dashboard widgets
        layout.widget("Recent Sales")
                .type("list").order(1)
                .document(Sale.class)
                .maxItems(5);

        layout.widget("Recent Inbound")
                .type("list").order(2)
                .document(GoodsReceipt.class)
                .maxItems(5);

        layout.widget("Products")
                .type("count").order(4)
                .catalog(Product.class);
    }
}
