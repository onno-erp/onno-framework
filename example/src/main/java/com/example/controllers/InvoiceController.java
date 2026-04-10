package com.example.controllers;

import com.example.domain.documents.Invoice;
import com.example.repositories.InvoiceRepository;
import com.onec.posting.PostingService;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/invoices")
public class InvoiceController {

    private final InvoiceRepository invoices;
    private final PostingService postingService;

    public InvoiceController(InvoiceRepository invoices, PostingService postingService) {
        this.invoices = invoices;
        this.postingService = postingService;
    }

    @GetMapping
    public List<Invoice> findAll() {
        return invoices.findAll();
    }

    @GetMapping("/{id}")
    public Invoice findById(@PathVariable UUID id) {
        return invoices.findById(id).orElseThrow();
    }

    @GetMapping("/by-number/{number}")
    public Invoice findByNumber(@PathVariable String number) {
        return invoices.findByNumber(number).orElseThrow();
    }

    @PostMapping
    public Invoice create(@RequestBody Invoice invoice) {
        return invoices.save(invoice);
    }

    @PostMapping("/{id}/post")
    public Invoice post(@PathVariable UUID id) {
        Invoice invoice = invoices.findById(id).orElseThrow();
        postingService.post(invoice);
        return invoice;
    }

    @PostMapping("/{id}/unpost")
    public Invoice unpost(@PathVariable UUID id) {
        Invoice invoice = invoices.findById(id).orElseThrow();
        postingService.unpost(invoice);
        return invoice;
    }
}
