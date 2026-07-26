package su.onno.process;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import su.onno.metadata.MetadataRegistry;
import su.onno.types.Ref;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** JDBI implementation of the durable typed process runtime. */
public final class JdbcProcessEngine implements ProcessEngine {

    private final Jdbi jdbi;
    private final ProcessDefinitions definitions;
    private final MetadataRegistry registry;
    private final ObjectMapper json;
    private final Clock clock;
    private final ProcessEventPublisher events;

    public JdbcProcessEngine(
            Jdbi jdbi,
            ProcessDefinitions definitions,
            MetadataRegistry registry,
            ObjectMapper json,
            ProcessEventPublisher events
    ) {
        this(jdbi, definitions, registry, json, Clock.systemUTC(), events);
    }

    JdbcProcessEngine(
            Jdbi jdbi,
            ProcessDefinitions definitions,
            MetadataRegistry registry,
            ObjectMapper json,
            Clock clock,
            ProcessEventPublisher events
    ) {
        this.jdbi = Objects.requireNonNull(jdbi, "jdbi");
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    public <P, S extends Enum<S> & ProcessStepKey> ProcessSnapshot start(
            ProcessDefinition<P, S> definition, P payload, ProcessActor actor) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(actor, "actor");
        ProcessDefinition<?, ?> registered = definitions.require(definition.key());
        if (registered != definition) {
            throw new IllegalArgumentException(
                    "Start the registered process definition bean for key " + definition.key());
        }
        ProcessGraph<P, S> graph = definition.graph();
        ProcessNode<P, S> first = graph.start().target();
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        ProcessStatus status = first instanceof EndNode<?, ?>
                ? ProcessStatus.COMPLETED : ProcessStatus.ACTIVE;
        String payloadJson = write(payload);

        Mutation<ProcessSnapshot> mutation = jdbi.inTransaction(handle -> {
            handle.createUpdate("""
                    insert into onno_process_instances
                        (_id, _definition_key, _payload, _current_step, _status,
                         _started_by, _started_by_display, _started_at, _updated_at, _version)
                    values (:id, :definition, :payload, :step, :status,
                            :actor, :actorDisplay, :now, :now, 0)
                    """)
                    .bind("id", id)
                    .bind("definition", definition.key())
                    .bind("payload", payloadJson)
                    .bind("step", first.step().key())
                    .bind("status", status.name())
                    .bind("actor", actor.id().value())
                    .bind("actorDisplay", actor.displayName())
                    .bind("now", now)
                    .execute();
            insertTransition(handle, id, null, first.step().key(), null, actor.identity(), now);
            TaskAudience audience = first instanceof HumanTaskNode<?, ?, ?> task
                    ? insertWorkItem(handle, id, task, payload, now)
                    : null;
            ProcessSnapshot snapshot = new ProcessSnapshot(
                    id, definition.key(), first.step().key(), status,
                    actor.id(), actor.displayName(), now, now, 0);
            return new Mutation<>(snapshot, id, audience);
        });
        publish(mutation);
        return mutation.value;
    }

    @Override
    public Optional<ProcessSnapshot> find(UUID instanceId) {
        return jdbi.withHandle(handle -> find(handle, instanceId, false));
    }

    @Override
    public List<ProcessTransitionSnapshot> history(UUID instanceId) {
        Objects.requireNonNull(instanceId, "instanceId");
        return jdbi.withHandle(handle -> handle.createQuery("""
                        select * from onno_process_transitions
                         where _instance_id = :instance
                         order by _sequence
                        """)
                .bind("instance", instanceId)
                .map((rs, ctx) -> new ProcessTransitionSnapshot(
                        rs.getObject("_id", UUID.class),
                        rs.getObject("_instance_id", UUID.class),
                        rs.getString("_from_step"),
                        rs.getString("_to_step"),
                        rs.getString("_outcome"),
                        actorId(rs.getString("_actor")),
                        rs.getString("_actor_display"),
                        instant(rs, "_occurred_at"),
                        rs.getInt("_sequence")))
                .list());
    }

    @Override
    public List<ProcessWorkItem> inbox(ProcessActor actor) {
        Objects.requireNonNull(actor, "actor");
        return jdbi.withHandle(handle -> {
            List<WorkRow> rows = handle.createQuery("""
                    select w.*, i._definition_key
                      from onno_process_work_items w
                      join onno_process_instances i on i._id = w._instance_id
                     where w._status in ('OPEN', 'CLAIMED')
                     order by w._created_at, w._id
                    """)
                    .map((rs, ctx) -> workRow(rs))
                    .list();
            List<ProcessWorkItem> visible = new ArrayList<>();
            for (WorkRow row : rows) {
                TaskAssignment assignment = new TaskAssignment(
                        readActorIds(row.candidateUsers), readSet(row.candidateRoles));
                boolean allowed = row.status == WorkItemStatus.CLAIMED
                        ? actor.roles().contains("ADMIN") || actor.id().value().equals(row.assignee)
                        : assignment.allows(actor);
                if (allowed) {
                    visible.add(toWorkItem(row));
                }
            }
            return List.copyOf(visible);
        });
    }

    @Override
    public ProcessWorkItem claim(UUID workItemId, ProcessActor actor) {
        Objects.requireNonNull(actor, "actor");
        Mutation<ProcessWorkItem> mutation = jdbi.inTransaction(handle -> {
            WorkRow row = requireWorkItem(handle, workItemId, true);
            if (row.status == WorkItemStatus.CLAIMED) {
                if (actor.id().value().equals(row.assignee) || actor.roles().contains("ADMIN")) {
                    return new Mutation<>(toWorkItem(row), row.instanceId, null);
                }
                throw new IllegalStateException("Work item is already claimed by " + row.assignee);
            }
            if (row.status != WorkItemStatus.OPEN) {
                throw new IllegalStateException("Work item is not open");
            }
            requireCandidate(row, actor);
            Instant now = Instant.now(clock);
            int changed = handle.createUpdate("""
                    update onno_process_work_items
                       set _status = 'CLAIMED', _assignee = :actor,
                           _assignee_display = :display, _claimed_at = :now,
                           _version = _version + 1
                     where _id = :id and _status = 'OPEN' and _version = :version
                    """)
                    .bind("actor", actor.id().value()).bind("display", actor.displayName())
                    .bind("now", now)
                    .bind("id", workItemId).bind("version", row.version)
                    .execute();
            if (changed != 1) {
                throw new IllegalStateException("Work item was changed by another user");
            }
            insertWorkItemEvent(handle, row.id, row.instanceId, WorkItemEventType.CLAIMED,
                    actor.identity(), null, actor.identity(), null, now);
            WorkRow claimed = row.claimed(actor.identity(), now);
            return new Mutation<>(
                    toWorkItem(claimed),
                    row.instanceId,
                    audience(row).withUser(actor.id().value()));
        });
        publish(mutation);
        return mutation.value;
    }

    @Override
    public ProcessWorkItem delegate(
            UUID workItemId,
            ProcessIdentity target,
            String reason,
            ProcessActor actor
    ) {
        Objects.requireNonNull(workItemId, "workItemId");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(target, "target");
        String explanation = reason == null ? "" : reason.trim();
        if (explanation.isEmpty()) {
            throw new IllegalArgumentException("reason is required");
        }
        Mutation<ProcessWorkItem> mutation = jdbi.inTransaction(handle -> {
            WorkRow row = requireWorkItem(handle, workItemId, true);
            if (row.status != WorkItemStatus.CLAIMED) {
                throw new IllegalStateException("Claim the work item before delegating it");
            }
            if (!actor.roles().contains("ADMIN") && !actor.id().value().equals(row.assignee)) {
                throw new SecurityException("Only the current assignee may delegate this work item");
            }
            if (target.id().value().equals(row.assignee)) {
                return new Mutation<>(toWorkItem(row), row.instanceId, null);
            }
            Instant now = Instant.now(clock);
            int changed = handle.createUpdate("""
                    update onno_process_work_items
                       set _assignee = :target, _assignee_display = :display,
                           _version = _version + 1
                     where _id = :id and _status = 'CLAIMED' and _version = :version
                    """)
                    .bind("target", target.id().value()).bind("display", target.displayName())
                    .bind("id", row.id).bind("version", row.version)
                    .execute();
            if (changed != 1) {
                throw new IllegalStateException("Work item was changed by another user");
            }
            insertWorkItemEvent(handle, row.id, row.instanceId, WorkItemEventType.DELEGATED,
                    actor.identity(), row.assigneeIdentity(), target, explanation, now);
            WorkRow delegated = row.delegated(target);
            return new Mutation<>(
                    toWorkItem(delegated),
                    row.instanceId,
                    audience(row).withUser(actor.id().value()).withUser(target.id().value()));
        });
        publish(mutation);
        return mutation.value;
    }

    @Override
    public List<ProcessWorkItemEventSnapshot> workItemHistory(
            UUID workItemId,
            ProcessActor actor
    ) {
        Objects.requireNonNull(workItemId, "workItemId");
        Objects.requireNonNull(actor, "actor");
        return jdbi.withHandle(handle -> {
            WorkRow row = requireWorkItem(handle, workItemId, false);
            boolean allowed = actor.roles().contains("ADMIN")
                    || actor.id().value().equals(row.assignee)
                    || new TaskAssignment(
                            readActorIds(row.candidateUsers), readSet(row.candidateRoles)).allows(actor)
                    || handle.createQuery("""
                            select count(*) from onno_process_work_item_events
                             where _work_item_id = :id
                               and (:actor = _actor
                                    or :actor = _from_assignee
                                    or :actor = _to_assignee)
                            """)
                        .bind("id", workItemId).bind("actor", actor.id().value())
                        .mapTo(Integer.class).one() > 0;
            if (!allowed) {
                throw new SecurityException("Current user cannot access this work item history");
            }
            return handle.createQuery("""
                            select * from onno_process_work_item_events
                             where _work_item_id = :id
                             order by _sequence
                            """)
                    .bind("id", workItemId)
                    .map((rs, ctx) -> new ProcessWorkItemEventSnapshot(
                            rs.getObject("_id", UUID.class),
                            rs.getObject("_work_item_id", UUID.class),
                            rs.getObject("_instance_id", UUID.class),
                            WorkItemEventType.valueOf(rs.getString("_event_type")),
                            actorId(rs.getString("_actor")),
                            rs.getString("_actor_display"),
                            actorId(rs.getString("_from_assignee")),
                            rs.getString("_from_assignee_display"),
                            actorId(rs.getString("_to_assignee")),
                            rs.getString("_to_assignee_display"),
                            rs.getString("_reason"),
                            instant(rs, "_occurred_at"),
                            rs.getInt("_sequence")))
                    .list();
        });
    }

    @Override
    public ProcessSnapshot complete(UUID workItemId, String outcome, ProcessActor actor) {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(actor, "actor");
        Mutation<ProcessSnapshot> mutation = jdbi.inTransaction(handle -> {
            WorkRow work = requireWorkItem(handle, workItemId, true);
            if (work.status == WorkItemStatus.COMPLETED) {
                throw new IllegalStateException("Work item is already completed");
            }
            if (work.status == WorkItemStatus.CLAIMED) {
                if (!actor.roles().contains("ADMIN") && !actor.id().value().equals(work.assignee)) {
                    throw new IllegalStateException("Work item is claimed by " + work.assigneeDisplay);
                }
            } else {
                requireCandidate(work, actor);
            }

            InstanceRow instance = requireInstance(handle, work.instanceId, true);
            if (instance.status != ProcessStatus.ACTIVE) {
                throw new IllegalStateException("Process instance is not active");
            }
            ProcessDefinition<?, ?> definition = definitions.require(instance.definitionKey);
            TransitionTarget target = transition(definition, instance, work, outcome);
            Instant now = Instant.now(clock);

            int workChanged = handle.createUpdate("""
                    update onno_process_work_items
                       set _status = 'COMPLETED', _assignee = coalesce(_assignee, :actor),
                           _assignee_display = coalesce(_assignee_display, :display),
                           _claimed_at = coalesce(_claimed_at, :now),
                           _completed_at = :now, _outcome = :outcome, _version = _version + 1
                     where _id = :id and _version = :version and _status in ('OPEN', 'CLAIMED')
                    """)
                    .bind("actor", actor.id().value()).bind("display", actor.displayName())
                    .bind("now", now).bind("outcome", outcome)
                    .bind("id", work.id).bind("version", work.version)
                    .execute();
            if (workChanged != 1) {
                throw new IllegalStateException("Work item was changed by another user");
            }
            insertWorkItemEvent(handle, work.id, work.instanceId, WorkItemEventType.COMPLETED,
                    actor.identity(), null, null, null, now);

            ProcessStatus nextStatus = target.node instanceof EndNode<?, ?>
                    ? ProcessStatus.COMPLETED : ProcessStatus.ACTIVE;
            int instanceChanged = handle.createUpdate("""
                    update onno_process_instances
                       set _current_step = :step, _status = :status,
                           _updated_at = :now, _version = _version + 1
                     where _id = :id and _version = :version and _current_step = :current
                    """)
                    .bind("step", target.node.step().key()).bind("status", nextStatus.name())
                    .bind("now", now).bind("id", instance.id).bind("version", instance.version)
                    .bind("current", work.stepKey)
                    .execute();
            if (instanceChanged != 1) {
                throw new IllegalStateException("Process instance was changed by another user");
            }
            insertTransition(handle, instance.id, work.stepKey, target.node.step().key(),
                    outcome, actor.identity(), now);
            TaskAudience audience = audience(work).withUser(actor.id().value());
            if (target.node instanceof HumanTaskNode<?, ?, ?> nextTask) {
                audience = audience.merge(insertWorkItem(
                        handle, instance.id, nextTask, target.payload, now));
            }
            ProcessSnapshot snapshot = new ProcessSnapshot(
                    instance.id, instance.definitionKey, target.node.step().key(), nextStatus,
                    actorId(instance.startedBy), instance.startedByDisplay,
                    instance.startedAt, now, instance.version + 1);
            return new Mutation<>(snapshot, instance.id, audience);
        });
        publish(mutation);
        return mutation.value;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private TransitionTarget transition(
            ProcessDefinition definition, InstanceRow instance, WorkRow work, String outcomeName) {
        Object payload = read(instance.payload, definition.payloadType());
        ProcessNode node = definition.graph().nodeByKey(work.stepKey);
        if (!(node instanceof HumanTaskNode task)) {
            throw new IllegalStateException("Persisted step is not a human task: " + work.stepKey);
        }
        Class<? extends Enum> outcomeType = task.task().outcomeType();
        Enum outcome;
        try {
            outcome = Enum.valueOf(outcomeType, outcomeName);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Unknown outcome " + outcomeName + "; expected "
                            + Arrays.toString(outcomeType.getEnumConstants()));
        }
        ProcessNode target = task.target(outcome);
        if (target == null) {
            throw new IllegalStateException(
                    "No transition for " + outcomeName + " from " + work.stepKey);
        }
        return new TransitionTarget(target, payload);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private TaskAudience insertWorkItem(
            Handle handle, UUID instanceId, HumanTaskNode task, Object payload, Instant now) {
        HumanTask humanTask = task.task();
        TaskAssignment assignment = Objects.requireNonNull(
                humanTask.assignment(payload), "task assignment");
        ProcessDomainLink subject = resolveSubject(
                humanTask.subject(payload), humanTask.subjectLabel(payload));
        UUID workItemId = UUID.randomUUID();
        handle.createUpdate("""
                insert into onno_process_work_items
                    (_id, _instance_id, _step_key, _title, _status,
                     _candidate_users, _candidate_roles,
                     _subject_kind, _subject_entity, _subject_id, _subject_label,
                     _created_at, _version)
                values (:id, :instance, :step, :title, 'OPEN',
                        :users, :roles, :subjectKind, :subjectEntity, :subjectId, :subjectLabel,
                        :now, 0)
                """)
                .bind("id", workItemId).bind("instance", instanceId)
                .bind("step", ((ProcessStepKey) task.step()).key())
                .bind("title", humanTask.title(payload))
                .bind("users", write(assignment.actors().stream()
                        .map(ProcessActorId::value).toList()))
                .bind("roles", write(assignment.roles()))
                .bind("subjectKind", subject == null ? null : subject.kind())
                .bind("subjectEntity", subject == null ? null : subject.entityName())
                .bind("subjectId", subject == null ? null : subject.id())
                .bind("subjectLabel", subject == null ? null : subject.label())
                .bind("now", now)
                .execute();
        insertWorkItemEvent(handle, workItemId, instanceId, WorkItemEventType.CREATED,
                null, null, null, null, now);
        return new TaskAudience(
                assignment.actors().stream().map(ProcessActorId::value)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                assignment.roles());
    }

    private void insertWorkItemEvent(
            Handle handle,
            UUID workItemId,
            UUID instanceId,
            WorkItemEventType type,
            ProcessIdentity actor,
            ProcessIdentity fromAssignee,
            ProcessIdentity toAssignee,
            String reason,
            Instant now
    ) {
        handle.createUpdate("""
                insert into onno_process_work_item_events
                    (_id, _work_item_id, _instance_id, _event_type, _actor,
                     _actor_display, _from_assignee, _from_assignee_display,
                     _to_assignee, _to_assignee_display,
                     _reason, _occurred_at, _sequence)
                values (:id, :workItem, :instance, :type, :actor, :actorDisplay,
                        :fromAssignee, :fromDisplay, :toAssignee, :toDisplay, :reason, :now,
                        (select coalesce(max(e._sequence), 0) + 1
                           from onno_process_work_item_events e
                          where e._work_item_id = :workItem))
                """)
                .bind("id", UUID.randomUUID()).bind("workItem", workItemId)
                .bind("instance", instanceId).bind("type", type.name())
                .bind("actor", id(actor)).bind("actorDisplay", display(actor))
                .bind("fromAssignee", id(fromAssignee)).bind("fromDisplay", display(fromAssignee))
                .bind("toAssignee", id(toAssignee)).bind("toDisplay", display(toAssignee))
                .bind("reason", reason).bind("now", now)
                .execute();
    }

    private void insertTransition(
            Handle handle, UUID instanceId, String from, String to,
            String outcome, ProcessIdentity actor, Instant now) {
        handle.createUpdate("""
                insert into onno_process_transitions
                    (_id, _instance_id, _from_step, _to_step, _outcome, _actor,
                     _actor_display, _occurred_at, _sequence)
                values (:id, :instance, :from, :to, :outcome, :actor, :actorDisplay, :now,
                        (select coalesce(max(t._sequence), 0) + 1
                           from onno_process_transitions t
                          where t._instance_id = :instance))
                """)
                .bind("id", UUID.randomUUID()).bind("instance", instanceId)
                .bind("from", from).bind("to", to).bind("outcome", outcome)
                .bind("actor", id(actor)).bind("actorDisplay", display(actor)).bind("now", now)
                .execute();
    }

    private Optional<ProcessSnapshot> find(Handle handle, UUID id, boolean lock) {
        String sql = """
                select * from onno_process_instances where _id = :id
                """ + (lock ? " for update" : "");
        return handle.createQuery(sql).bind("id", id)
                .map((rs, ctx) -> snapshot(instanceRow(rs))).findOne();
    }

    private InstanceRow requireInstance(Handle handle, UUID id, boolean lock) {
        String sql = "select * from onno_process_instances where _id = :id"
                + (lock ? " for update" : "");
        return handle.createQuery(sql).bind("id", id)
                .map((rs, ctx) -> instanceRow(rs)).findOne()
                .orElseThrow(() -> new IllegalArgumentException("Unknown process instance: " + id));
    }

    private WorkRow requireWorkItem(Handle handle, UUID id, boolean lock) {
        String sql = """
                select w.*, i._definition_key
                  from onno_process_work_items w
                  join onno_process_instances i on i._id = w._instance_id
                 where w._id = :id
                """ + (lock ? " for update" : "");
        return handle.createQuery(sql).bind("id", id)
                .map((rs, ctx) -> workRow(rs)).findOne()
                .orElseThrow(() -> new IllegalArgumentException("Unknown work item: " + id));
    }

    private void requireCandidate(WorkRow row, ProcessActor actor) {
        TaskAssignment assignment = new TaskAssignment(
                readActorIds(row.candidateUsers), readSet(row.candidateRoles));
        if (!assignment.allows(actor)) {
            throw new SecurityException("User is not a candidate for this work item");
        }
    }

    private ProcessWorkItem toWorkItem(WorkRow row) {
        ProcessDefinition<?, ?> definition = definitions.require(row.definitionKey);
        ProcessNode<?, ?> node = definition.graph().nodeByKey(row.stepKey);
        List<String> outcomes = node instanceof HumanTaskNode<?, ?, ?> task
                ? Arrays.stream(task.task().outcomeType().getEnumConstants())
                    .map(Enum::name).toList()
                : List.of();
        return new ProcessWorkItem(
                row.id, row.instanceId, row.definitionKey, row.stepKey, row.title, row.status,
                actorId(row.assignee), row.assigneeDisplay, row.subject(),
                row.createdAt, row.claimedAt, row.completedAt, row.outcome, outcomes);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Process value is not JSON serializable", e);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored process payload cannot be read as " + type.getName(), e);
        }
    }

    private Set<String> readSet(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        try {
            return Set.copyOf(json.readValue(
                    value, json.getTypeFactory().constructCollectionType(LinkedHashSet.class, String.class)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored task assignment is invalid", e);
        }
    }

    private Set<ProcessActorId> readActorIds(String value) {
        return readSet(value).stream().map(ProcessActorId::of)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private ProcessDomainLink resolveSubject(Ref<?> ref, String label) {
        if (ref == null) {
            return null;
        }
        return registry.allCatalogs().stream()
                .filter(descriptor -> descriptor.javaClass().equals(ref.type()))
                .findFirst()
                .map(descriptor -> new ProcessDomainLink(
                        "catalogs", descriptor.logicalName(), ref.id(), label))
                .or(() -> registry.allDocuments().stream()
                        .filter(descriptor -> descriptor.javaClass().equals(ref.type()))
                        .findFirst()
                        .map(descriptor -> new ProcessDomainLink(
                                "documents", descriptor.logicalName(), ref.id(), label)))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Task subject must reference a registered catalog or document: "
                                + ref.type().getName()));
    }

    private static ProcessActorId actorId(String value) {
        return value == null || value.isBlank() ? null : ProcessActorId.of(value);
    }

    private static String id(ProcessIdentity identity) {
        return identity == null ? null : identity.id().value();
    }

    private static String display(ProcessIdentity identity) {
        return identity == null ? null : identity.displayName();
    }

    private TaskAudience audience(WorkRow row) {
        TaskAudience audience = new TaskAudience(
                readSet(row.candidateUsers), readSet(row.candidateRoles));
        return row.assignee == null ? audience : audience.withUser(row.assignee);
    }

    private void publish(Mutation<?> mutation) {
        if (mutation.audience != null) {
            events.publish(new ProcessTasksChangedEvent(
                    mutation.instanceId, mutation.audience.users, mutation.audience.roles));
        }
    }

    private static ProcessSnapshot snapshot(InstanceRow row) {
        return new ProcessSnapshot(
                row.id, row.definitionKey, row.currentStep, row.status,
                actorId(row.startedBy), row.startedByDisplay,
                row.startedAt, row.updatedAt, row.version);
    }

    private static InstanceRow instanceRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new InstanceRow(
                rs.getObject("_id", UUID.class), rs.getString("_definition_key"),
                rs.getString("_payload"), rs.getString("_current_step"),
                ProcessStatus.valueOf(rs.getString("_status")), rs.getString("_started_by"),
                rs.getString("_started_by_display"),
                instant(rs, "_started_at"), instant(rs, "_updated_at"), rs.getInt("_version"));
    }

    private static WorkRow workRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new WorkRow(
                rs.getObject("_id", UUID.class), rs.getObject("_instance_id", UUID.class),
                rs.getString("_definition_key"), rs.getString("_step_key"), rs.getString("_title"),
                WorkItemStatus.valueOf(rs.getString("_status")),
                rs.getString("_candidate_users"), rs.getString("_candidate_roles"),
                rs.getString("_assignee"), rs.getString("_assignee_display"),
                rs.getString("_subject_kind"), rs.getString("_subject_entity"),
                rs.getObject("_subject_id", UUID.class), rs.getString("_subject_label"),
                instant(rs, "_created_at"), instant(rs, "_claimed_at"),
                instant(rs, "_completed_at"), rs.getString("_outcome"), rs.getInt("_version"));
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private record InstanceRow(
            UUID id, String definitionKey, String payload, String currentStep, ProcessStatus status,
            String startedBy, String startedByDisplay,
            Instant startedAt, Instant updatedAt, int version) {
    }

    private record WorkRow(
            UUID id, UUID instanceId, String definitionKey, String stepKey, String title,
            WorkItemStatus status, String candidateUsers, String candidateRoles, String assignee,
            String assigneeDisplay, String subjectKind, String subjectEntity, UUID subjectId,
            String subjectLabel,
            Instant createdAt, Instant claimedAt, Instant completedAt, String outcome, int version) {
        WorkRow claimed(ProcessIdentity identity, Instant at) {
            return new WorkRow(
                    id, instanceId, definitionKey, stepKey, title, WorkItemStatus.CLAIMED,
                    candidateUsers, candidateRoles, identity.id().value(), identity.displayName(),
                    subjectKind, subjectEntity, subjectId, subjectLabel,
                    createdAt, at, completedAt, outcome, version + 1);
        }

        WorkRow delegated(ProcessIdentity identity) {
            return new WorkRow(
                    id, instanceId, definitionKey, stepKey, title, WorkItemStatus.CLAIMED,
                    candidateUsers, candidateRoles, identity.id().value(), identity.displayName(),
                    subjectKind, subjectEntity, subjectId, subjectLabel, createdAt, claimedAt,
                    completedAt, outcome, version + 1);
        }

        ProcessIdentity assigneeIdentity() {
            return assignee == null ? null : new ProcessIdentity(
                    ProcessActorId.of(assignee), assigneeDisplay, assigneeDisplay);
        }

        ProcessDomainLink subject() {
            return subjectId == null ? null
                    : new ProcessDomainLink(subjectKind, subjectEntity, subjectId, subjectLabel);
        }
    }

    private record TransitionTarget(ProcessNode<?, ?> node, Object payload) {
    }

    private record Mutation<T>(T value, UUID instanceId, TaskAudience audience) {
    }

    private record TaskAudience(Set<String> users, Set<String> roles) {
        TaskAudience {
            users = Set.copyOf(users);
            roles = Set.copyOf(roles);
        }

        TaskAudience withUser(String user) {
            if (user == null || user.isBlank() || users.contains(user)) {
                return this;
            }
            LinkedHashSet<String> merged = new LinkedHashSet<>(users);
            merged.add(user);
            return new TaskAudience(merged, roles);
        }

        TaskAudience merge(TaskAudience other) {
            LinkedHashSet<String> mergedUsers = new LinkedHashSet<>(users);
            mergedUsers.addAll(other.users);
            LinkedHashSet<String> mergedRoles = new LinkedHashSet<>(roles);
            mergedRoles.addAll(other.roles);
            return new TaskAudience(mergedUsers, mergedRoles);
        }
    }
}
