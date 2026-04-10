package com.example.controllers;

import com.example.domain.catalogs.Product;
import com.example.domain.registers.SalesRegister;
import com.example.repositories.SalesRepository;
import com.onec.types.Ref;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/sales")
public class SalesController {

    private final SalesRepository salesRepository;

    public SalesController(SalesRepository salesRepository) {
        this.salesRepository = salesRepository;
    }

    @GetMapping("/balance")
    public List<SalesRegister> getBalance(
            @RequestParam(required = false) UUID productId) {
        if (productId != null) {
            return salesRepository.getBalance(f -> f
                    .where(SalesRegister::getProduct, Ref.of(Product.class, productId)));
        }
        return salesRepository.getBalance();
    }
}
