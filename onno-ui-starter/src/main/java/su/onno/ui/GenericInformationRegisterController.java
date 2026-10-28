package su.onno.ui;

import su.onno.access.AccessSubject;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Generated read API for stored information-register records (including periodic history). */
@RestController
@RequestMapping("/api/information-registers")
public class GenericInformationRegisterController {
    private final InformationRegisterQueryService query;
    private final UiAccessService access;

    public GenericInformationRegisterController(InformationRegisterQueryService query, UiAccessService access) {
        this.query = query;
        this.access = access;
    }

    @GetMapping("/{name}/records")
    public List<Map<String, Object>> records(@PathVariable String name,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam Map<String, String> filters, AccessSubject subject) {
        var desc = query.require(name);
        access.requireRead(subject, desc);
        return EntityJsonRepresentation.informationRegisters(desc,
                query.records(subject, desc, from, to, filters, limit, offset));
    }
}
