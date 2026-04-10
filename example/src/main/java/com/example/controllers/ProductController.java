package com.example.controllers;

import com.example.domain.catalogs.Product;
import com.example.repositories.ProductRepository;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/products")
public class ProductController {
    private final ProductRepository products;

    public ProductController(ProductRepository products) {
        this.products = products;
    }

    @GetMapping
    public List<Product> findAll() {
        return products.findAll();
    }

    @GetMapping("/{id}")
    public Product findById(@PathVariable UUID id) {
        return products.findById(id).orElseThrow();
    }

    @PostMapping
    public Product create(@RequestBody Product product) {
        return products.save(product);
    }
}
