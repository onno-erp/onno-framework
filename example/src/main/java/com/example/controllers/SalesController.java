package com.example.controllers;

import com.example.domain.registers.SalesRegister;
import com.onec.repository.RegisterRepository;
import com.onec.spring.RegisterRepositoryProvider;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sales")
public class SalesController {

    private final RegisterRepository<SalesRegister> salesRepository;

    public SalesController(RegisterRepositoryProvider repositoryProvider) {
        this.salesRepository = repositoryProvider.forRegister(SalesRegister.class);
    }

    @GetMapping("/balance")
    public List<SalesRegister> getBalance(
            @RequestParam(required = false) String productName) {
        Map<String, Object> filters = productName != null
                ? Map.of("product_name", productName)
                : Map.of();
        return salesRepository.getBalance(filters);
    }
}
