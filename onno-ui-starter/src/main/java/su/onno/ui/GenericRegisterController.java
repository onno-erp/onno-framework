package su.onno.ui;

import su.onno.access.AccessSubject;
import su.onno.metadata.AccumulationRegisterDescriptor;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/registers")
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
                                                AccessSubject subject) {
        AccumulationRegisterDescriptor desc = query.require(name);
        access.requireRead(subject, desc);
        return query.movements(subject, desc, from, to);
    }

    @GetMapping("/{name}/balance")
    public List<Map<String, Object>> balance(@PathVariable String name,
                                             @RequestParam Map<String, String> filters,
                                             AccessSubject subject) {
        AccumulationRegisterDescriptor desc = query.require(name);
        access.requireRead(subject, desc);
        return query.balance(subject, desc, filters);
    }

    @GetMapping("/{name}/turnover")
    public List<Map<String, Object>> turnover(@PathVariable String name,
                                              @RequestParam String from,
                                              @RequestParam String to,
                                              @RequestParam Map<String, String> allParams,
                                              AccessSubject subject) {
        AccumulationRegisterDescriptor desc = query.require(name);
        access.requireRead(subject, desc);
        return query.turnover(subject, desc, from, to, allParams);
    }
}
