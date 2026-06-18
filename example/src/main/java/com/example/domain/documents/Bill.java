package com.example.domain.documents;

import com.example.domain.catalogs.Client;
import com.example.domain.catalogs.Property;
import com.example.domain.registers.ReceivablesRegister;
import com.example.domain.registers.RevenueRegister;
import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Document;
import su.onno.lifecycle.BeforeWriteHandler;
import su.onno.lifecycle.Postable;
import su.onno.model.DocumentObject;
import su.onno.posting.PostingContext;
import su.onno.print.PrintFormat;
import su.onno.print.PrintTemplate;
import su.onno.rules.BusinessRule;
import su.onno.rules.Validated;
import su.onno.types.Ref;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Spanish VAT invoice mirroring the spreadsheet's "Bills" sheet.
 * Net + IVA = Gross. IVA percent defaults from the {@code DefaultIvaPercent} constant.
 */
@Document(name = "Bills", numberPrefix = "BILL-", numberLength = 14, context = "Rentals")
@AccessControl(readRoles = {"RENTALS", "FINANCE"}, writeRoles = {"FINANCE"})
@PrintTemplate(name = "bill", label = "Print Bill", format = PrintFormat.PDF)
@Getter
@Setter
public class Bill extends DocumentObject implements BeforeWriteHandler, Postable, Validated {

    @Attribute(required = true)
    private Ref<Client> client;

    @Attribute
    private Ref<Property> property;

    /** The booking this invoice bills for — a document → document reference, rendered as a
     *  searchable booking picker in the UI (resolves to the booking's number). */
    @Attribute(displayName = "Booking")
    private Ref<Booking> booking;

    @Attribute(displayName = "Net (excl. IVA)", precision = 14, scale = 2)
    private BigDecimal net;

    @Attribute(displayName = "IVA %", precision = 5, scale = 2)
    private BigDecimal ivaPercent;

    @Attribute(displayName = "IVA amount", precision = 14, scale = 2)
    private BigDecimal iva;

    @Attribute(displayName = "Total (incl. IVA)", precision = 14, scale = 2)
    private BigDecimal gross;

    @Attribute(length = 1000)
    private String comments;

    @Override
    public List<BusinessRule> rules() {
        return List.of(
                new BusinessRule("client-required", "Client is required", () -> client != null),
                new BusinessRule("gross-positive", "Gross must be positive",
                        () -> gross != null && gross.signum() > 0));
    }

    @Override
    public void beforeWrite() {
        BigDecimal n = net != null ? net : BigDecimal.ZERO;
        BigDecimal pct = ivaPercent != null ? ivaPercent : BigDecimal.ZERO;
        BigDecimal ivaAmount = n.multiply(pct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        this.iva = ivaAmount;
        this.gross = n.add(ivaAmount).setScale(2, RoundingMode.HALF_UP);
    }

    @Override
    public void handlePosting(PostingContext context) {
        var receivables = context.movements(ReceivablesRegister.class);
        receivables.addReceipt(r -> {
            r.setClient(client);
            r.setAmount(gross);
        });

        var revenue = context.movements(RevenueRegister.class);
        revenue.addReceipt(r -> {
            r.setProperty(property);
            r.setNetAmount(net);
            r.setIvaAmount(iva);
            r.setGrossAmount(gross);
        });
    }
}
