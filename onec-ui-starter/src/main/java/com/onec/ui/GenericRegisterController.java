package com.onec.ui;

import com.onec.metadata.AccumulationRegisterDescriptor;

import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ui/registers")
public class GenericRegisterController {

    private final RegisterQueryService query;
    private final UiAccessService access;

    public GenericRegisterController(RegisterQueryService query, UiAccessService access) {
        this.query = query;
        this.access = access;
    }

    @GetMapping("/{name}/movements")
    public List<Map<String, Object>> movements(@PathVariable String name,
                                                @RequestParam(required = false) String from,
                                                @RequestParam(required = false) String to,
                                                Principal principal) {
        AccumulationRegisterDescriptor desc = query.require(name);
        access.requireRead(principal, desc);
        return query.movements(desc, from, to);
    }

    @GetMapping("/{name}/balance")
    public List<Map<String, Object>> balance(@PathVariable String name,
                                             @RequestParam Map<String, String> filters,
                                             Principal principal) {
        AccumulationRegisterDescriptor desc = query.require(name);
        access.requireRead(principal, desc);
        return query.balance(desc, filters);
    }

    @GetMapping("/{name}/turnover")
    public List<Map<String, Object>> turnover(@PathVariable String name,
                                              @RequestParam String from,
                                              @RequestParam String to,
                                              @RequestParam Map<String, String> allParams,
                                              Principal principal) {
        AccumulationRegisterDescriptor desc = query.require(name);
        access.requireRead(principal, desc);
        return query.turnover(desc, from, to, allParams);
    }
}
