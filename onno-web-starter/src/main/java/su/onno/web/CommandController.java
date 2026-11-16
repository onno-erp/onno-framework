package su.onno.web;

import su.onno.service.CommandService;
import su.onno.access.AccessSubject;
import su.onno.command.CommandDefaults;
import su.onno.command.CommandResult;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * The REST surface of {@link su.onno.command.Commands commands}. Every call goes through
 * {@link CommandService}, which enforces write access, roles, read-only mode, record scope and
 * per-record availability.
 *
 * <pre>
 * GET  /api/commands/{kind}/{name}[?id=]          → {commands: [descriptor…], state: {key: {visible, enabled, title?}}}
 * POST /api/commands/{kind}/{name}/{key}          body {id?, inputs: {k: v | rows[]}} → {refresh, feedback}
 * GET  /api/commands/{kind}/{name}/{key}/defaults[?id=] → {values, rows}
 * POST /api/commands/{kind}/{name}/{key}/batch    body {ids: [...], inputs} → {ok, failed, total[, feedback]}
 * </pre>
 *
 * <p>{@code kind} is {@code catalogs} or {@code documents}. The record id of a record command may
 * travel as the {@code id} query parameter or the body's {@code id}.</p>
 */
@RestController
@RequestMapping("/api/commands")
public class CommandController {

    private final CommandService commands;

    public CommandController(CommandService commands) {
        this.commands = commands;
    }

    @GetMapping("/{kind}/{name}")
    public Map<String, Object> list(@PathVariable String kind, @PathVariable String name,
                                    @RequestParam(required = false) UUID id, AccessSubject subject) {
        return commands.listing(kind, name, id, subject);
    }

    @PostMapping("/{kind}/{name}/{key}")
    public CommandResult run(@PathVariable String kind, @PathVariable String name, @PathVariable String key,
                             @RequestParam(required = false) UUID id,
                             @RequestBody(required = false) Map<String, Object> body,
                             AccessSubject subject) {
        return commands.run(kind, name, key, id != null ? id : bodyId(body), body, subject);
    }

    @GetMapping("/{kind}/{name}/{key}/defaults")
    public Map<String, Object> defaults(@PathVariable String kind, @PathVariable String name,
                                        @PathVariable String key, @RequestParam(required = false) UUID id,
                                        AccessSubject subject) {
        CommandDefaults defaults = commands.defaults(kind, name, key, id, subject);
        return Map.of("values", defaults.values(), "rows", defaults.rows());
    }

    @PostMapping("/{kind}/{name}/{key}/batch")
    public Map<String, Object> runBatch(@PathVariable String kind, @PathVariable String name,
                                        @PathVariable String key, @RequestBody Map<String, Object> body,
                                        AccessSubject subject) {
        return commands.runBatch(kind, name, key, CommandService.idList(body), body, subject);
    }

    private static UUID bodyId(Map<String, Object> body) {
        Object raw = body == null ? null : body.get("id");
        if (raw == null || raw.toString().isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.toString());
        } catch (IllegalArgumentException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Bad id: " + raw);
        }
    }
}
