package su.onno.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import su.onno.process.ProcessActor;
import su.onno.process.ProcessActorId;
import su.onno.process.ProcessIdentity;
import su.onno.process.ProcessDefinition;
import su.onno.process.ProcessDefinitions;
import su.onno.process.ProcessEngine;
import su.onno.process.ProcessSnapshot;
import su.onno.process.ProcessTransitionSnapshot;
import su.onno.process.ProcessWorkItem;
import su.onno.process.ProcessWorkItemEventSnapshot;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/** Authenticated HTTP boundary for the durable typed process runtime. */
@RestController
@RequestMapping("/api")
public final class ProcessController {

    private final ProcessEngine engine;
    private final ProcessDefinitions definitions;
    private final ObjectMapper json;
    private final UiAccessService access;
    private final TaskAssigneeDirectory assignees;
    private final CurrentUserResolver currentUser;

    public ProcessController(
            ProcessEngine engine,
            ProcessDefinitions definitions,
            ObjectMapper json,
            UiAccessService access,
            TaskAssigneeDirectory assignees,
            CurrentUserResolver currentUser
    ) {
        this.engine = engine;
        this.definitions = definitions;
        this.json = json;
        this.access = access;
        this.assignees = assignees;
        this.currentUser = currentUser;
    }

    ProcessController(
            ProcessEngine engine,
            ProcessDefinitions definitions,
            ObjectMapper json,
            UiAccessService access
    ) {
        this(engine, definitions, json, access, null, null);
    }

    @GetMapping("/process-definitions")
    public List<DefinitionView> definitions() {
        return definitions.all().stream()
                .map(definition -> new DefinitionView(
                        definition.key(), definition.title(), definition.payloadType().getName()))
                .toList();
    }

    @PostMapping("/processes/{definitionKey}")
    public ProcessSnapshot start(
            @PathVariable String definitionKey,
            @RequestBody Map<String, Object> body,
            Principal principal
    ) {
        try {
            ProcessDefinition<?, ?> definition = definitions.require(definitionKey);
            Object payload = json.convertValue(body, definition.payloadType());
            return startUnchecked(definition, payload, actor(principal));
        } catch (RuntimeException exception) {
            throw operationFailure(exception);
        }
    }

    @GetMapping("/processes/{instanceId}")
    public ProcessSnapshot get(@PathVariable UUID instanceId, Principal principal) {
        ProcessSnapshot snapshot = engine.find(instanceId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Unknown process instance: " + instanceId));
        ProcessActor actor = actor(principal);
        boolean allowed = actor.roles().contains("ADMIN")
                || actor.id().equals(snapshot.startedById())
                || engine.inbox(actor).stream().anyMatch(item -> item.instanceId().equals(instanceId))
                || engine.history(instanceId).stream()
                    .anyMatch(transition -> actor.id().equals(transition.actorId()));
        if (!allowed) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Current user cannot access process " + instanceId);
        }
        return snapshot;
    }

    @GetMapping("/processes/{instanceId}/history")
    public List<ProcessTransitionSnapshot> history(
            @PathVariable UUID instanceId,
            Principal principal
    ) {
        get(instanceId, principal);
        return engine.history(instanceId);
    }

    @GetMapping("/tasks")
    public List<ProcessWorkItem> inbox(Principal principal) {
        return engine.inbox(actor(principal));
    }

    @PostMapping("/tasks/{workItemId}/claim")
    public ProcessWorkItem claim(@PathVariable UUID workItemId, Principal principal) {
        try {
            return engine.claim(workItemId, actor(principal));
        } catch (RuntimeException exception) {
            throw operationFailure(exception);
        }
    }

    @GetMapping("/task-assignees")
    public List<TaskAssigneeDirectory.AssigneeOption> assignees(
            @org.springframework.web.bind.annotation.RequestParam(
                    name = "q", required = false) String query,
            Principal principal
    ) {
        actor(principal);
        return assignees == null ? List.of() : assignees.search(query, principal);
    }

    @GetMapping("/tasks/{workItemId}/history")
    public List<ProcessWorkItemEventSnapshot> workItemHistory(
            @PathVariable UUID workItemId,
            Principal principal
    ) {
        try {
            return engine.workItemHistory(workItemId, actor(principal));
        } catch (RuntimeException exception) {
            throw operationFailure(exception);
        }
    }

    @PostMapping("/tasks/{workItemId}/delegate")
    public ProcessWorkItem delegate(
            @PathVariable UUID workItemId,
            @RequestBody DelegateTaskRequest request,
            Principal principal
    ) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request is required");
        }
        try {
            return engine.delegate(
                    workItemId,
                    assignees == null
                            ? ProcessIdentity.unlinked(request.targetActorId())
                            : assignees.require(request.targetActorId(), principal),
                    request.reason(),
                    actor(principal));
        } catch (RuntimeException exception) {
            throw operationFailure(exception);
        }
    }

    @PostMapping("/tasks/{workItemId}/complete")
    public ProcessSnapshot complete(
            @PathVariable UUID workItemId,
            @RequestBody CompleteTaskRequest request,
            Principal principal
    ) {
        if (request == null || request.outcome() == null || request.outcome().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "outcome is required");
        }
        try {
            return engine.complete(workItemId, request.outcome(), actor(principal));
        } catch (RuntimeException exception) {
            throw operationFailure(exception);
        }
    }

    private ProcessActor actor(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required");
        }
        if (currentUser == null) {
            return new ProcessActor(principal.getName(), access.roles(principal));
        }
        CurrentUserResolver.CurrentUser resolved = currentUser.resolve(principal);
        String stableId = resolved.recordId() == null
                ? resolved.username() : resolved.recordId();
        return new ProcessActor(
                new ProcessIdentity(
                        ProcessActorId.of(stableId),
                        resolved.username(),
                        resolved.displayName()),
                access.roles(principal));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ProcessSnapshot startUnchecked(
            ProcessDefinition definition,
            Object payload,
            ProcessActor actor
    ) {
        if (!definition.startAssignment(payload).allows(actor)) {
            throw new SecurityException("Current user cannot start process " + definition.key());
        }
        return engine.start(definition, payload, actor);
    }

    private ResponseStatusException operationFailure(RuntimeException exception) {
        if (exception instanceof SecurityException) {
            return new ResponseStatusException(HttpStatus.FORBIDDEN, exception.getMessage(), exception);
        }
        if (exception instanceof NoSuchElementException) {
            return new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
        if (exception instanceof IllegalStateException) {
            return new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage(), exception);
        }
        if (exception instanceof IllegalArgumentException) {
            return badRequest(exception);
        }
        throw exception;
    }

    private ResponseStatusException badRequest(RuntimeException exception) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
    }

    public record CompleteTaskRequest(String outcome) {
    }

    public record DelegateTaskRequest(String targetActorId, String reason) {
    }

    public record DefinitionView(String key, String title, String payloadType) {
    }
}
