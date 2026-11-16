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
    public Map<String, Object> movements(@PathVariable String name,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "100") int limit,
            AccessSubject subject) {
        AccumulationRegisterDescriptor desc = query.require(name);
        access.requireRead(subject, desc);
        return KeysetWindow.envelope(query.movementsPage(subject, desc, from, to,
                ListFilter.parse(List.of(), List.of(), List.of(), query.movementFilterColumns(desc)),
                null, true, cursor, limit));
    }

    @GetMapping("/{name}/balance")
    public Map<String, Object> balance(@PathVariable String name, @RequestParam Map<String, String> filters,
            @RequestParam(required = false) String asOf,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "100") int limit,
            AccessSubject subject) {
        AccumulationRegisterDescriptor desc = query.require(name);
        access.requireRead(subject, desc);
        return KeysetWindow.envelope(query.balancePage(subject, desc, query.dimensionFilters(desc, filters),
                null, false, cursor, limit, asOf));
    }

    @GetMapping("/{name}/turnover")
    public Map<String, Object> turnover(@PathVariable String name,
                                              @RequestParam String from,
                                              @RequestParam String to,
                                              @RequestParam Map<String, String> allParams,
                                              @RequestParam(required = false) String cursor,
                                              @RequestParam(defaultValue = "100") int limit,
                                              AccessSubject subject) {
        AccumulationRegisterDescriptor desc = query.require(name);
        access.requireRead(subject, desc);
        return KeysetWindow.envelope(query.turnoverPage(subject, desc, from, to, allParams, cursor, limit));
    }
}
