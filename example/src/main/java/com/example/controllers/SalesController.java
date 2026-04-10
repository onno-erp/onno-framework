package com.example.controllers;

import com.example.domain.registers.SalesRegister;
import com.onec.posting.RegisterQueryService;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sales")
public class SalesController {

    private final RegisterQueryService registerQueryService;

    public SalesController(RegisterQueryService registerQueryService) {
        this.registerQueryService = registerQueryService;
    }

    @GetMapping("/balance")
    public List<Map<String, Object>> getBalance(
            @RequestParam(required = false) String productName) {
        Map<String, Object> filters = productName != null
                ? Map.of("product_name", productName)
                : Map.of();
        return registerQueryService.getBalance(SalesRegister.class, filters);
    }
}
