package su.onno.process;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import su.onno.metadata.MetadataRegistry;
import su.onno.types.Ref;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Core JDBI runtime for durable, versioned typed process definitions.
 *
 * <p>Execution tokens, rather than {@code onno_process_instances._current_step}, are authoritative.
 * The scalar current step is retained as a compatibility projection for sequential consumers.</p>
 */
public final class JdbcProcessEngine implements ProcessEngine {

    private static final ProcessIdentity SYSTEM = ProcessIdentity.unlinked("onno-system");
    private static final int MAX_IMMEDIATE_STEPS = 10_000;

    private final Jdbi jdbi;
    private final ProcessDefinitions definitions;
    private final MetadataRegistry registry;
    private final ProcessPayloadCodec payloadCodec;
    private final Clock clock;
    private final ProcessEventPublisher events;

    public JdbcProcessEngine(
            Jdbi jdbi,
            ProcessDefinitions definitions,
            MetadataRegistry registry,
            ProcessPayloadCodec payloadCodec,
            ProcessEventPublisher events
    ) {
        this(jdbi, definitions, registry, payloadCodec, Clock.systemUTC(), events);
    }

    JdbcProcessEngine(
            Jdbi jdbi,
            ProcessDefinitions definitions,
            MetadataRegistry registry,
            ProcessPayloadCodec payloadCodec,
            Clock clock,
            ProcessEventPublisher events
    ) {
        this.jdbi = Objects.requireNonNull(jdbi, "jdbi");
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.payloadCodec = Objects.requireNonNull(payloadCodec, "payloadCodec");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    public <P, S extends Enum<S> & ProcessStepKey> ProcessSnapshot start(
            ProcessDefinition<P, S> definition,
            P payload,
            ProcessActor actor
    ) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(actor, "actor");
        if (definitions.require(definition.key()) != definition) {
            throw new IllegalArgumentException(
                    "Start the latest registered process definition bean for key "
                            + definition.key());
        }
        Instant now = Instant.now(clock);
        Mutation<ProcessSnapshot> mutation = jdbi.inTransaction(handle -> {
            Audience audience = new Audience();
            UUID instanceId = startInternal(
                    handle, definition, payload, actor.identity(),
                    null, null, null, now, audience);
            return new Mutation<>(snapshot(handle, requireInstance(handle, instanceId, false)),
                    instanceId, audience);
        });
        publish(mutation);
        runPending(100);
        return find(mutation.instanceId).orElse(mutation.value);
    }

    @Override
    public Optional<ProcessSnapshot> find(UUID instanceId) {
        Objects.requireNonNull(instanceId, "instanceId");
        return jdbi.withHandle(handle -> findInstance(handle, instanceId, false)
                .map(row -> snapshot(handle, row)));
    }

    @Override
    public List<ProcessSnapshot> instances(ProcessActor actor) {
        Objects.requireNonNull(actor, "actor");
        return jdbi.withHandle(handle -> {
            List<InstanceRow> rows = handle.createQuery("""
                    select * from onno_process_instances order by _started_at desc, _id
                    """).map((rs, ctx) -> instanceRow(rs)).list();
            List<ProcessSnapshot> visible = new ArrayList<>();
            for (InstanceRow row : rows) {
                if (canAccess(handle, row, actor)) {
                    visible.add(snapshot(handle, row));
                }
            }
            return List.copyOf(visible);
        });
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
                        rs.getObject("_token_id", UUID.class),
                        rs.getInt("_definition_version"),
                        ProcessTransitionType.valueOf(rs.getString("_transition_type")),
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
    public List<ProcessTokenSnapshot> tokens(UUID instanceId) {
        Objects.requireNonNull(instanceId, "instanceId");
        return jdbi.withHandle(handle -> tokenRows(handle, instanceId, false).stream()
                .map(JdbcProcessEngine::toTokenSnapshot)
                .toList());
    }

    @Override
    public List<ProcessWorkItem> inbox(ProcessActor actor) {
        Objects.requireNonNull(actor, "actor");
        return jdbi.withHandle(handle -> {
            List<WorkRow> rows = handle.createQuery("""
                    select w.*, i._definition_key, i._definition_version
                      from onno_process_work_items w
                      join onno_process_instances i on i._id = w._instance_id
                     where w._status in ('OPEN', 'CLAIMED')
                     order by w._created_at, w._id
                    """).map((rs, ctx) -> workRow(rs)).list();
            List<ProcessWorkItem> visible = new ArrayList<>();
            for (WorkRow row : rows) {
                TaskAssignment assignment = assignment(row);
                boolean allowed = row.status == WorkItemStatus.CLAIMED
                        ? isAdmin(actor) || actor.id().value().equals(row.assignee)
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
        Objects.requireNonNull(workItemId, "workItemId");
        Objects.requireNonNull(actor, "actor");
        Mutation<ProcessWorkItem> mutation = jdbi.inTransaction(handle -> {
            WorkRow initial = requireWorkItem(handle, workItemId, false);
            requireInstance(handle, initial.instanceId, true);
            WorkRow row = requireWorkItem(handle, workItemId, true);
            if (row.status == WorkItemStatus.CLAIMED) {
                if (actor.id().value().equals(row.assignee) || isAdmin(actor)) {
                    return new Mutation<>(toWorkItem(row), row.instanceId, new Audience());
                }
                throw new IllegalStateException(
                        "Work item is already claimed by " + row.assigneeDisplay);
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
                    .bind("actor", actor.id().value())
                    .bind("display", actor.displayName())
                    .bind("now", now)
                    .bind("id", workItemId)
                    .bind("version", row.version)
                    .execute();
            if (changed != 1) {
                throw new IllegalStateException("Work item was changed by another user");
            }
            insertWorkItemEvent(handle, row, WorkItemEventType.CLAIMED,
                    actor.identity(), null, actor.identity(), null, now);
            Audience audience = audience(row).withUser(actor.id().value());
            return new Mutation<>(toWorkItem(row.claimed(actor.identity(), now)),
                    row.instanceId, audience);
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
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(actor, "actor");
        String explanation = reason == null ? "" : reason.trim();
        if (explanation.isEmpty()) {
            throw new IllegalArgumentException("reason is required");
        }
        Mutation<ProcessWorkItem> mutation = jdbi.inTransaction(handle -> {
            WorkRow initial = requireWorkItem(handle, workItemId, false);
            requireInstance(handle, initial.instanceId, true);
            WorkRow row = requireWorkItem(handle, workItemId, true);
            if (row.status != WorkItemStatus.CLAIMED) {
                throw new IllegalStateException("Claim the work item before delegating it");
            }
            if (!isAdmin(actor) && !actor.id().value().equals(row.assignee)) {
                throw new SecurityException(
                        "Only the current assignee may delegate this work item");
            }
            if (target.id().value().equals(row.assignee)) {
                return new Mutation<>(toWorkItem(row), row.instanceId, new Audience());
            }
            Instant now = Instant.now(clock);
            int changed = handle.createUpdate("""
                    update onno_process_work_items
                       set _assignee = :target, _assignee_display = :display,
                           _version = _version + 1
                     where _id = :id and _status = 'CLAIMED' and _version = :version
                    """)
                    .bind("target", target.id().value())
                    .bind("display", target.displayName())
                    .bind("id", row.id)
                    .bind("version", row.version)
                    .execute();
            if (changed != 1) {
                throw new IllegalStateException("Work item was changed by another user");
            }
            insertWorkItemEvent(handle, row, WorkItemEventType.DELEGATED,
                    actor.identity(), row.assigneeIdentity(), target, explanation, now);
            Audience audience = audience(row)
                    .withUser(actor.id().value())
                    .withUser(target.id().value());
            return new Mutation<>(toWorkItem(row.delegated(target)), row.instanceId, audience);
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
            boolean allowed = isAdmin(actor)
                    || actor.id().value().equals(row.assignee)
                    || assignment(row).allows(actor)
                    || handle.createQuery("""
                            select count(*) from onno_process_work_item_events
                             where _work_item_id = :id
                               and (:actor = _actor
                                    or :actor = _from_assignee
                                    or :actor = _to_assignee)
                            """)
                    .bind("id", workItemId)
                    .bind("actor", actor.id().value())
                    .mapTo(Integer.class)
                    .one() > 0;
            if (!allowed) {
                throw new SecurityException(
                        "Current user cannot access this work item history");
            }
            return handle.createQuery("""
                            select * from onno_process_work_item_events
                             where _work_item_id = :id order by _sequence
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
    public ProcessSnapshot complete(
            UUID workItemId,
            String outcomeName,
            ProcessActor actor
    ) {
        Objects.requireNonNull(workItemId, "workItemId");
        Objects.requireNonNull(outcomeName, "outcome");
        Objects.requireNonNull(actor, "actor");
        Mutation<ProcessSnapshot> mutation = jdbi.inTransaction(handle -> {
            WorkRow initial = requireWorkItem(handle, workItemId, false);
            InstanceRow instance = requireInstance(handle, initial.instanceId, true);
            WorkRow work = requireWorkItem(handle, workItemId, true);
            if (work.status == WorkItemStatus.COMPLETED) {
                throw new IllegalStateException("Work item is already completed");
            }
            if (work.status == WorkItemStatus.CLAIMED) {
                if (!isAdmin(actor) && !actor.id().value().equals(work.assignee)) {
                    throw new IllegalStateException(
                            "Work item is claimed by " + work.assigneeDisplay);
                }
            } else {
                requireCandidate(work, actor);
            }
            if (instance.status != ProcessStatus.ACTIVE) {
                throw new IllegalStateException("Process instance is not active");
            }

            ProcessDefinition<?, ?> definition = exactDefinition(handle, instance);
            TransitionTarget target = humanTransition(
                    definition, work.stepKey, outcomeName);
            TokenRow token = requireToken(handle,
                    ensureWorkToken(handle, instance, work), true);
            if (token.status != ProcessTokenStatus.WAITING_HUMAN
                    || !token.stepKey.equals(work.stepKey)) {
                throw new IllegalStateException(
                        "The work item's execution token is not waiting at " + work.stepKey);
            }

            Instant now = Instant.now(clock);
            int changed = handle.createUpdate("""
                    update onno_process_work_items
                       set _status = 'COMPLETED', _assignee = coalesce(_assignee, :actor),
                           _assignee_display = coalesce(_assignee_display, :display),
                           _claimed_at = coalesce(_claimed_at, :now),
                           _completed_at = :now, _outcome = :outcome,
                           _version = _version + 1
                     where _id = :id and _version = :version
                       and _status in ('OPEN', 'CLAIMED')
                    """)
                    .bind("actor", actor.id().value())
                    .bind("display", actor.displayName())
                    .bind("now", now)
                    .bind("outcome", outcomeName)
                    .bind("id", work.id)
                    .bind("version", work.version)
                    .execute();
            if (changed != 1) {
                throw new IllegalStateException("Work item was changed by another user");
            }
            insertWorkItemEvent(handle, work, WorkItemEventType.COMPLETED,
                    actor.identity(), null, null, null, now);

            RuntimeState state = new RuntimeState(
                    instance, definition,
                    read(instance.payload, definition.payloadType()),
                    actor.identity(), now, audience(work).withUser(actor.id().value()));
            enter(handle, state, token, target.node,
                    work.stepKey, outcomeName, ProcessTransitionType.HUMAN_TASK);
            refreshInstance(handle, state);
            ProcessSnapshot value = snapshot(
                    handle, requireInstance(handle, instance.id, false));
            return new Mutation<>(value, instance.id, state.audience);
        });
        publish(mutation);
        runPending(100);
        return find(mutation.instanceId).orElse(mutation.value);
    }

    @Override
    public ProcessSnapshot cancel(
            UUID instanceId,
            String reason,
            ProcessActor actor
    ) {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(actor, "actor");
        String explanation = reason == null ? "" : reason.trim();
        if (explanation.isEmpty()) {
            throw new IllegalArgumentException("reason is required");
        }
        Mutation<ProcessSnapshot> mutation = jdbi.inTransaction(handle -> {
            InstanceRow instance = requireInstance(handle, instanceId, true);
            if (instance.status == ProcessStatus.CANCELLED) {
                return new Mutation<>(snapshot(handle, instance), instanceId, new Audience());
            }
            if (instance.status == ProcessStatus.COMPLETED) {
                throw new IllegalStateException("Completed process instances cannot be cancelled");
            }
            ProcessDefinition<?, ?> definition = exactDefinition(handle, instance);
            Object payload = read(instance.payload, definition.payloadType());
            if (!isAdmin(actor)
                    && !actor.id().value().equals(instance.startedBy)
                    && !cancellationAllows(definition, payload, actor)) {
                throw new SecurityException(
                        "Current user cannot cancel process " + instance.definitionKey);
            }
            Audience audience = new Audience();
            cancelInternal(handle, instance, explanation, actor.identity(),
                    Instant.now(clock), audience);
            return new Mutation<>(
                    snapshot(handle, requireInstance(handle, instanceId, false)),
                    instanceId, audience);
        });
        publish(mutation);
        runPending(100);
        return find(mutation.instanceId).orElse(mutation.value);
    }

    @Override
    public ProcessSnapshot migrate(UUID instanceId, ProcessActor actor) {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(actor, "actor");
        Mutation<ProcessSnapshot> mutation = jdbi.inTransaction(handle -> {
            InstanceRow instance = requireInstance(handle, instanceId, true);
            if (instance.status != ProcessStatus.ACTIVE) {
                throw new IllegalStateException("Only active process instances can be migrated");
            }
            ProcessDefinition<?, ?> source = exactDefinition(handle, instance);
            ProcessDefinition<?, ?> latest = definitions.require(instance.definitionKey);
            if (source == latest) {
                return new Mutation<>(snapshot(handle, instance), instanceId, new Audience());
            }
            Object sourcePayload = read(instance.payload, source.payloadType());
            if (!isAdmin(actor)
                    && !actor.id().value().equals(instance.startedBy)
                    && !cancellationAllows(source, sourcePayload, actor)) {
                throw new SecurityException(
                        "Current user cannot migrate process " + instance.definitionKey);
            }

            List<TokenRow> live = liveTokenRows(handle, instance.id, true);
            MigrationData migrated = applyMigrationPath(
                    instance, sourcePayload, live,
                    definitions.migrationPath(instance.definitionKey, instance.definitionVersion));
            Audience audience = cancelActiveWork(handle, instance.id, actor.identity(),
                    "Definition migrated", Instant.now(clock));

            Instant now = Instant.now(clock);
            handle.createUpdate("""
                    update onno_process_instances
                       set _definition_version = :definitionVersion,
                           _definition_fingerprint = :fingerprint,
                           _payload = :payload, _updated_at = :now,
                           _version = _version + 1
                     where _id = :id
                    """)
                    .bind("definitionVersion", latest.version())
                    .bind("fingerprint", latest.fingerprint())
                    .bind("payload", write(migrated.payload))
                    .bind("now", now)
                    .bind("id", instance.id)
                    .execute();
            InstanceRow updated = requireInstance(handle, instance.id, false);
            RuntimeState state = new RuntimeState(
                    updated, latest, migrated.payload, actor.identity(), now, audience);
            for (TokenRow old : parentFirst(live)) {
                ProcessNode<?, ?> target = migrated.targets.get(old.id);
                if (target == null) {
                    throw new IllegalArgumentException(
                            "Process migration did not map token " + old.id);
                }
                if (old.childInstanceId != null) {
                    findInstance(handle, old.childInstanceId, true)
                            .filter(child -> child.status == ProcessStatus.ACTIVE)
                            .ifPresent(child -> cancelInternal(
                                    handle, child, "Parent definition migrated",
                                    actor.identity(), now, audience));
                }
                TokenRow ready = resetTokenForMigration(handle, old, target, now);
                if (live.stream().anyMatch(
                        token -> old.id.equals(token.parentTokenId))) {
                    preserveParallelCoordinator(
                            handle, state, ready, target, old, live,
                            instance.definitionVersion);
                } else {
                    enter(handle, state, ready, target, old.stepKey,
                            "v" + instance.definitionVersion + "->v" + latest.version(),
                            ProcessTransitionType.MIGRATION);
                }
            }
            refreshInstance(handle, state);
            return new Mutation<>(
                    snapshot(handle, requireInstance(handle, instance.id, false)),
                    instance.id, audience);
        });
        publish(mutation);
        runPending(100);
        return find(mutation.instanceId).orElse(mutation.value);
    }

    @Override
    public int runPending(int limit) {
        if (limit < 1) {
            return 0;
        }
        Instant now = Instant.now(clock);
        List<UUID> tokenIds = jdbi.withHandle(handle -> handle.createQuery("""
                select t._id
                  from onno_process_tokens t
                  left join onno_process_instances c on c._id = t._child_instance_id
                 where (t._status = 'WAITING_TIMER' and t._due_at <= :now)
                    or (t._status = 'WAITING_SUBPROCESS'
                        and c._status in ('COMPLETED', 'CANCELLED'))
                 order by coalesce(t._due_at, t._entered_at), t._id
                 limit :limit
                """)
                .bind("now", now)
                .bind("limit", limit)
                .mapTo(UUID.class)
                .list());
        int advanced = 0;
        for (UUID tokenId : tokenIds) {
            Mutation<ProcessSnapshot> mutation = advancePending(tokenId, now);
            if (mutation != null) {
                advanced++;
                publish(mutation);
            }
        }
        return advanced;
    }

    private Mutation<ProcessSnapshot> advancePending(UUID tokenId, Instant now) {
        return jdbi.inTransaction(handle -> {
            TokenRow initial = findToken(handle, tokenId, false).orElse(null);
            if (initial == null) {
                return null;
            }
            InstanceRow instance = requireInstance(handle, initial.instanceId, true);
            TokenRow token = requireToken(handle, tokenId, true);
            if (instance.status != ProcessStatus.ACTIVE) {
                return null;
            }
            ProcessDefinition<?, ?> definition = exactDefinition(handle, instance);
            RuntimeState state = new RuntimeState(
                    instance, definition,
                    read(instance.payload, definition.payloadType()),
                    SYSTEM, now, new Audience());
            ProcessNode<?, ?> node = requireNode(definition, token.stepKey);

            if (token.status == ProcessTokenStatus.WAITING_TIMER
                    && node instanceof TimerNode<?, ?> rawTimer) {
                if (token.dueAt == null || token.dueAt.isAfter(now)) {
                    return null;
                }
                @SuppressWarnings("rawtypes")
                TimerNode timer = rawTimer;
                enter(handle, state, token, timer.target(),
                        token.stepKey, null, ProcessTransitionType.TIMER);
            } else if (token.status == ProcessTokenStatus.WAITING_SUBPROCESS
                    && node instanceof SubprocessNode<?, ?, ?, ?> subprocess) {
                InstanceRow child = requireInstance(
                        handle, Objects.requireNonNull(token.childInstanceId), false);
                if (child.status == ProcessStatus.ACTIVE) {
                    return null;
                }
                ProcessNode<?, ?> target;
                if (child.status == ProcessStatus.CANCELLED) {
                    target = subprocess.cancellationTarget();
                } else {
                    ProcessDefinition<?, ?> childDefinition =
                            definitions.require(child.definitionKey, child.definitionVersion);
                    ProcessNode<?, ?> childEnd =
                            requireNode(childDefinition, child.currentStep);
                    target = subprocessTarget(subprocess, childEnd.step());
                    Object childPayload = read(child.payload, childDefinition.payloadType());
                    state.payload = mergeSubprocess(
                            subprocess, state.payload, childPayload);
                    persistPayload(handle, instance.id, state.payload, now);
                }
                enter(handle, state, token, target,
                        token.stepKey, child.currentStep, ProcessTransitionType.SUBPROCESS);
            } else {
                return null;
            }
            refreshInstance(handle, state);
            return new Mutation<>(
                    snapshot(handle, requireInstance(handle, instance.id, false)),
                    instance.id, state.audience);
        });
    }

    private <P, S extends Enum<S> & ProcessStepKey> UUID startInternal(
            Handle handle,
            ProcessDefinition<P, S> definition,
            P payload,
            ProcessIdentity actor,
            UUID rootInstanceId,
            UUID parentInstanceId,
            UUID parentTokenId,
            Instant now,
            Audience audience
    ) {
        if (definitions.require(definition.key()) != definition) {
            throw new IllegalArgumentException(
                    "Subprocess must use the latest registered definition bean for key "
                            + definition.key());
        }
        ProcessNode<P, S> first = definition.graph().start().target();
        UUID id = UUID.randomUUID();
        UUID root = rootInstanceId == null ? id : rootInstanceId;
        handle.createUpdate("""
                insert into onno_process_instances
                    (_id, _definition_key, _definition_version, _definition_fingerprint,
                     _payload, _current_step, _status,
                     _root_instance_id, _parent_instance_id, _parent_token_id,
                     _started_by, _started_by_display, _started_at, _updated_at, _version)
                values (:id, :definition, :definitionVersion, :fingerprint,
                        :payload, :step, 'ACTIVE',
                        :root, :parentInstance, :parentToken,
                        :actor, :actorDisplay, :now, :now, 0)
                """)
                .bind("id", id)
                .bind("definition", definition.key())
                .bind("definitionVersion", definition.version())
                .bind("fingerprint", definition.fingerprint())
                .bind("payload", write(payload))
                .bind("step", first.step().key())
                .bind("root", root)
                .bind("parentInstance", parentInstanceId)
                .bind("parentToken", parentTokenId)
                .bind("actor", id(actor))
                .bind("actorDisplay", display(actor))
                .bind("now", now)
                .execute();
        TokenRow token = insertToken(
                handle, id, null, null, first, ProcessTokenStatus.READY, now);
        InstanceRow instance = requireInstance(handle, id, false);
        RuntimeState state =
                new RuntimeState(instance, definition, payload, actor, now, audience);
        enter(handle, state, token, first, null, null, ProcessTransitionType.START);
        refreshInstance(handle, state);
        return id;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void enter(
            Handle handle,
            RuntimeState state,
            TokenRow token,
            ProcessNode target,
            String fromStep,
            String outcome,
            ProcessTransitionType transitionType
    ) {
        if (++state.immediateSteps > MAX_IMMEDIATE_STEPS) {
            throw new IllegalStateException(
                    "Process exceeded " + MAX_IMMEDIATE_STEPS
                            + " immediate transitions; check the graph for a dynamic cycle");
        }
        Instant now = state.now;
        updateTokenEntry(handle, token.id, target, now);
        insertTransition(
                handle, state.instance.id, token.id, state.definition.version(),
                transitionType, fromStep, stepKey(target), outcome, state.actor, now);
        token = requireToken(handle, token.id, false);

        switch (target.type()) {
            case HUMAN_TASK -> {
                setTokenWait(handle, token.id, ProcessTokenStatus.WAITING_HUMAN,
                        null, null, now);
                state.audience.merge(insertWorkItem(
                        handle, state.instance.id, token.id,
                        state.definition.version(), (HumanTaskNode) target,
                        state.payload, now));
            }
            case AUTOMATIC -> {
                AutomaticNode automatic = (AutomaticNode) target;
                ProcessExecutionContext execution = new ProcessExecutionContext(
                        state.instance.id, token.id, state.instance.definitionKey,
                        state.definition.version(), token.attempt + 1, now);
                Object nextPayload = Objects.requireNonNull(
                        automatic.action().execute(state.payload, execution),
                        "Automatic step " + stepKey(target) + " returned null payload");
                state.payload = nextPayload;
                handle.createUpdate("""
                        update onno_process_tokens
                           set _attempt = _attempt + 1, _version = _version + 1,
                               _updated_at = :now
                         where _id = :id
                        """).bind("now", now).bind("id", token.id).execute();
                persistPayload(handle, state.instance.id, state.payload, now);
                enter(handle, state, requireToken(handle, token.id, false),
                        automatic.target(), stepKey(target), null,
                        ProcessTransitionType.AUTOMATIC);
            }
            case DECISION -> {
                DecisionNode decision = (DecisionNode) target;
                Enum selected = (Enum) Objects.requireNonNull(
                        decision.decision().decide(state.payload),
                        "Decision " + stepKey(target) + " returned null");
                ProcessNode next = decision.target(selected);
                if (next == null) {
                    throw new IllegalStateException(
                            "Decision " + stepKey(target)
                                    + " returned undeclared outcome " + selected.name());
                }
                enter(handle, state, token, next, stepKey(target), selected.name(),
                        ProcessTransitionType.DECISION);
            }
            case TIMER -> {
                TimerNode timer = (TimerNode) target;
                Instant dueAt = Objects.requireNonNull(
                        timer.timer().dueAt(state.payload, now),
                        "Timer " + stepKey(target) + " returned null due time");
                setTokenWait(handle, token.id, ProcessTokenStatus.WAITING_TIMER,
                        dueAt, null, now);
            }
            case PARALLEL_FORK -> {
                ParallelForkNode fork = (ParallelForkNode) target;
                setTokenWait(handle, token.id, ProcessTokenStatus.WAITING_JOIN,
                        null, null, now);
                for (Object rawBranch : fork.branchType().getEnumConstants()) {
                    Enum branch = (Enum) rawBranch;
                    ProcessNode branchTarget = fork.target(branch);
                    TokenRow child = insertToken(
                            handle, state.instance.id, token.id, branch.name(),
                            branchTarget, ProcessTokenStatus.READY, now);
                    enter(handle, state, child, branchTarget,
                            stepKey(target), branch.name(), ProcessTransitionType.FORK);
                }
            }
            case PARALLEL_JOIN ->
                    arriveAtJoin(handle, state, token, (ParallelJoinNode) target);
            case SUBPROCESS -> {
                SubprocessNode subprocess = (SubprocessNode) target;
                ProcessDefinition childDefinition = subprocess.call().definition();
                Object childPayload = Objects.requireNonNull(
                        subprocess.call().payload(state.payload),
                        "Subprocess " + stepKey(target) + " returned null child payload");
                setTokenWait(handle, token.id, ProcessTokenStatus.WAITING_SUBPROCESS,
                        null, null, now);
                UUID childId = startInternal(
                        handle, childDefinition, childPayload, state.actor,
                        state.instance.rootInstanceId, state.instance.id, token.id,
                        now, state.audience);
                handle.createUpdate("""
                        update onno_process_tokens
                           set _child_instance_id = :child, _version = _version + 1,
                               _updated_at = :now
                         where _id = :id
                        """)
                        .bind("child", childId)
                        .bind("now", now)
                        .bind("id", token.id)
                        .execute();
            }
            case END -> setTokenWait(
                    handle, token.id, ProcessTokenStatus.COMPLETED, null, null, now);
            case START -> throw new IllegalStateException(
                    "Synthetic process start cannot be entered as a route node");
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void arriveAtJoin(
            Handle handle,
            RuntimeState state,
            TokenRow branchToken,
            ParallelJoinNode join
    ) {
        if (branchToken.parentTokenId == null) {
            throw new IllegalStateException(
                    "Parallel join " + stepKey(join) + " has no coordinating fork token");
        }
        setTokenWait(handle, branchToken.id, ProcessTokenStatus.WAITING_JOIN,
                null, null, state.now);
        ParallelForkNode fork = join.fork();
        int arrivals = handle.createQuery("""
                select count(*) from onno_process_tokens
                 where _parent_token_id = :parent
                   and _step_key = :joinStep
                   and _status = 'WAITING_JOIN'
                """)
                .bind("parent", branchToken.parentTokenId)
                .bind("joinStep", stepKey(join))
                .mapTo(Integer.class)
                .one();
        if (arrivals < fork.branchType().getEnumConstants().length) {
            return;
        }
        handle.createUpdate("""
                update onno_process_tokens
                   set _status = 'COMPLETED', _updated_at = :now, _version = _version + 1
                 where _parent_token_id = :parent
                   and _step_key = :joinStep
                   and _status = 'WAITING_JOIN'
                """)
                .bind("now", state.now)
                .bind("parent", branchToken.parentTokenId)
                .bind("joinStep", stepKey(join))
                .execute();
        TokenRow coordinator = requireToken(handle, branchToken.parentTokenId, true);
        if (!coordinator.stepKey.equals(stepKey(fork))
                || coordinator.status != ProcessTokenStatus.WAITING_JOIN) {
            throw new IllegalStateException(
                    "Parallel fork coordinator was already advanced");
        }
        enter(handle, state, coordinator, join.target(),
                stepKey(join), null, ProcessTransitionType.JOIN);
    }

    private void refreshInstance(Handle handle, RuntimeState state) {
        List<TokenRow> live = liveTokenRows(handle, state.instance.id, false);
        List<String> activeSteps = visibleActiveSteps(live);
        if (live.isEmpty()) {
            String terminal = handle.createQuery("""
                    select _to_step from onno_process_transitions
                     where _instance_id = :instance
                     order by _sequence desc limit 1
                    """)
                    .bind("instance", state.instance.id)
                    .mapTo(String.class)
                    .one();
            handle.createUpdate("""
                    update onno_process_instances
                       set _current_step = :step, _status = 'COMPLETED',
                           _payload = :payload, _updated_at = :now,
                           _completed_at = :now, _version = _version + 1
                     where _id = :id and _status = 'ACTIVE'
                    """)
                    .bind("step", terminal)
                    .bind("payload", write(state.payload))
                    .bind("now", state.now)
                    .bind("id", state.instance.id)
                    .execute();
            return;
        }
        String projection = activeSteps.isEmpty()
                ? live.getFirst().stepKey : activeSteps.getFirst();
        handle.createUpdate("""
                update onno_process_instances
                   set _current_step = :step, _payload = :payload,
                       _updated_at = :now, _version = _version + 1
                 where _id = :id and _status = 'ACTIVE'
                """)
                .bind("step", projection)
                .bind("payload", write(state.payload))
                .bind("now", state.now)
                .bind("id", state.instance.id)
                .execute();
    }

    private UUID ensureWorkToken(
            Handle handle,
            InstanceRow instance,
            WorkRow work
    ) {
        if (work.tokenId != null) {
            return work.tokenId;
        }
        ProcessDefinition<?, ?> definition =
                definitions.require(instance.definitionKey, instance.definitionVersion);
        ProcessNode<?, ?> node = requireNode(definition, work.stepKey);
        TokenRow token = insertToken(
                handle, instance.id, null, null, node,
                ProcessTokenStatus.WAITING_HUMAN,
                instance.updatedAt == null ? instance.startedAt : instance.updatedAt);
        handle.createUpdate("""
                update onno_process_work_items set _token_id = :token where _id = :id
                """).bind("token", token.id).bind("id", work.id).execute();
        return token.id;
    }

    private void cancelInternal(
            Handle handle,
            InstanceRow instance,
            String reason,
            ProcessIdentity actor,
            Instant now,
            Audience audience
    ) {
        if (instance.status != ProcessStatus.ACTIVE) {
            return;
        }
        audience.merge(cancelActiveWork(handle, instance.id, actor, reason, now));
        List<InstanceRow> children = handle.createQuery("""
                select * from onno_process_instances
                 where _parent_instance_id = :parent and _status = 'ACTIVE'
                 order by _id
                """)
                .bind("parent", instance.id)
                .map((rs, ctx) -> instanceRow(rs))
                .list();
        for (InstanceRow child : children) {
            cancelInternal(
                    handle, requireInstance(handle, child.id, true),
                    "Parent process cancelled: " + reason, actor, now, audience);
        }
        for (TokenRow token : liveTokenRows(handle, instance.id, true)) {
            insertTransition(
                    handle, instance.id, token.id, instance.definitionVersion,
                    ProcessTransitionType.CANCELLATION,
                    token.stepKey, token.stepKey, reason, actor, now);
        }
        handle.createUpdate("""
                update onno_process_tokens
                   set _status = 'CANCELLED', _updated_at = :now, _version = _version + 1
                 where _instance_id = :instance
                   and _status not in ('COMPLETED', 'CANCELLED')
                """).bind("now", now).bind("instance", instance.id).execute();
        handle.createUpdate("""
                update onno_process_instances
                   set _status = 'CANCELLED', _cancel_reason = :reason,
                       _cancelled_at = :now, _updated_at = :now,
                       _version = _version + 1
                 where _id = :id and _status = 'ACTIVE'
                """)
                .bind("reason", reason)
                .bind("now", now)
                .bind("id", instance.id)
                .execute();
    }

    private Audience cancelActiveWork(
            Handle handle,
            UUID instanceId,
            ProcessIdentity actor,
            String reason,
            Instant now
    ) {
        Audience audience = new Audience();
        List<WorkRow> work = handle.createQuery("""
                select w.*, i._definition_key, i._definition_version
                  from onno_process_work_items w
                  join onno_process_instances i on i._id = w._instance_id
                 where w._instance_id = :instance
                   and w._status in ('OPEN', 'CLAIMED')
                 order by w._id
                """)
                .bind("instance", instanceId)
                .map((rs, ctx) -> workRow(rs))
                .list();
        for (WorkRow row : work) {
            audience.merge(audience(row));
            handle.createUpdate("""
                    update onno_process_work_items
                       set _status = 'CANCELLED', _completed_at = :now,
                           _version = _version + 1
                     where _id = :id and _status in ('OPEN', 'CLAIMED')
                    """).bind("now", now).bind("id", row.id).execute();
            insertWorkItemEvent(
                    handle, row, WorkItemEventType.CANCELLED,
                    actor, null, null, reason, now);
        }
        return audience;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private MigrationData applyMigrationPath(
            InstanceRow instance,
            Object payload,
            List<TokenRow> tokens,
            List<ProcessDefinitionMigration<?, ?, ?, ?>> path
    ) {
        Object currentPayload = payload;
        Map<UUID, Enum<?>> currentSteps = new LinkedHashMap<>();
        ProcessDefinition currentDefinition =
                definitions.require(instance.definitionKey, instance.definitionVersion);
        for (TokenRow token : tokens) {
            currentSteps.put(token.id,
                    (Enum<?>) requireNode(currentDefinition, token.stepKey).step());
        }
        for (ProcessDefinitionMigration raw : path) {
            List<ProcessMigrationToken> migrationTokens = new ArrayList<>();
            for (TokenRow token : tokens) {
                Enum<?> step = currentSteps.get(token.id);
                migrationTokens.add(new ProcessMigrationToken(
                        token.id, step, token.parentTokenId, token.branchKey));
            }
            ProcessMigrationResult result = Objects.requireNonNull(
                    raw.migrate(new ProcessMigrationState(
                            currentPayload, migrationTokens)),
                    "Process migration returned null");
            if (!result.tokenSteps().keySet().equals(currentSteps.keySet())) {
                throw new IllegalArgumentException(
                        "Process migration from v" + raw.from().version()
                                + " must map every active token exactly once; expected "
                                + currentSteps.keySet() + " but got "
                                + result.tokenSteps().keySet());
            }
            currentPayload = result.payload();
            currentSteps = new LinkedHashMap<>(result.tokenSteps());
            currentDefinition = raw.to();
        }
        Map<UUID, ProcessNode<?, ?>> targets = new LinkedHashMap<>();
        for (Map.Entry<UUID, Enum<?>> entry : currentSteps.entrySet()) {
            ProcessNode<?, ?> target =
                    currentDefinition.graph().node((Enum) entry.getValue());
            if (target == null) {
                throw new IllegalArgumentException(
                        "Migration target step is not in definition v"
                                + currentDefinition.version() + ": " + entry.getValue());
            }
            targets.put(entry.getKey(), target);
        }
        return new MigrationData(currentPayload, targets);
    }

    private static List<TokenRow> parentFirst(List<TokenRow> tokens) {
        Map<UUID, TokenRow> byId = new LinkedHashMap<>();
        tokens.forEach(token -> byId.put(token.id, token));
        List<TokenRow> ordered = new ArrayList<>(tokens.size());
        Set<UUID> emitted = new LinkedHashSet<>();
        while (ordered.size() < tokens.size()) {
            boolean progressed = false;
            for (TokenRow token : tokens) {
                if (emitted.contains(token.id)) {
                    continue;
                }
                if (token.parentTokenId == null
                        || !byId.containsKey(token.parentTokenId)
                        || emitted.contains(token.parentTokenId)) {
                    ordered.add(token);
                    emitted.add(token.id);
                    progressed = true;
                }
            }
            if (!progressed) {
                throw new IllegalStateException(
                        "Process token ancestry contains a cycle");
            }
        }
        return List.copyOf(ordered);
    }

    private void preserveParallelCoordinator(
            Handle handle,
            RuntimeState state,
            TokenRow coordinator,
            ProcessNode<?, ?> target,
            TokenRow old,
            List<TokenRow> live,
            int sourceDefinitionVersion
    ) {
        if (!(target instanceof ParallelForkNode<?, ?, ?> fork)) {
            throw new IllegalArgumentException(
                    "Migration token " + old.id
                            + " coordinates active parallel branches and must map to a parallel fork");
        }
        Set<String> expectedBranches = Arrays.stream(
                        fork.branchType().getEnumConstants())
                .map(Enum::name)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> activeBranches = live.stream()
                .filter(token -> old.id.equals(token.parentTokenId))
                .map(token -> token.branchKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!expectedBranches.equals(activeBranches)) {
            throw new IllegalArgumentException(
                    "Migration of active parallel fork " + old.stepKey
                            + " must preserve branch keys; expected "
                            + expectedBranches + " but found " + activeBranches);
        }
        insertTransition(
                handle, state.instance.id, coordinator.id, state.definition.version(),
                ProcessTransitionType.MIGRATION, old.stepKey, stepKey(target),
                "v" + sourceDefinitionVersion + "->v" + state.definition.version(),
                state.actor, state.now);
        setTokenWait(
                handle, coordinator.id, ProcessTokenStatus.WAITING_JOIN,
                null, null, state.now);
    }

    private TokenRow resetTokenForMigration(
            Handle handle,
            TokenRow old,
            ProcessNode<?, ?> target,
            Instant now
    ) {
        handle.createUpdate("""
                update onno_process_tokens
                   set _step_key = :step, _node_type = :nodeType, _status = 'READY',
                       _due_at = null, _child_instance_id = null,
                       _entered_at = :now, _updated_at = :now,
                       _version = _version + 1
                 where _id = :id
                """)
                .bind("step", target.step().key())
                .bind("nodeType", target.type().name())
                .bind("now", now)
                .bind("id", old.id)
                .execute();
        return requireToken(handle, old.id, false);
    }

    private InstanceRow exactDefinitionRow(Handle handle, InstanceRow instance) {
        exactDefinition(handle, instance);
        return instance;
    }

    private ProcessDefinition<?, ?> exactDefinition(
            Handle handle,
            InstanceRow instance
    ) {
        ProcessDefinition<?, ?> definition =
                definitions.require(instance.definitionKey, instance.definitionVersion);
        if (instance.definitionFingerprint == null
                || instance.definitionFingerprint.isBlank()) {
            handle.createUpdate("""
                    update onno_process_instances
                       set _definition_fingerprint = :fingerprint
                     where _id = :id and _definition_fingerprint is null
                    """)
                    .bind("fingerprint", definition.fingerprint())
                    .bind("id", instance.id)
                    .execute();
        } else if (!instance.definitionFingerprint.equals(definition.fingerprint())) {
            throw new IllegalStateException(
                    "Process definition " + instance.definitionKey + " v"
                            + instance.definitionVersion
                            + " changed without a version bump; register a new version and migration");
        }
        return definition;
    }

    private boolean canAccess(Handle handle, InstanceRow instance, ProcessActor actor) {
        if (isAdmin(actor) || actor.id().value().equals(instance.startedBy)) {
            return true;
        }
        List<WorkRow> work = handle.createQuery("""
                select w.*, i._definition_key, i._definition_version
                  from onno_process_work_items w
                  join onno_process_instances i on i._id = w._instance_id
                 where w._instance_id = :instance
                """)
                .bind("instance", instance.id)
                .map((rs, ctx) -> workRow(rs))
                .list();
        for (WorkRow row : work) {
            if (actor.id().value().equals(row.assignee) || assignment(row).allows(actor)) {
                return true;
            }
        }
        return handle.createQuery("""
                select count(*) from onno_process_transitions
                 where _instance_id = :instance and _actor = :actor
                """)
                .bind("instance", instance.id)
                .bind("actor", actor.id().value())
                .mapTo(Integer.class)
                .one() > 0;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean cancellationAllows(
            ProcessDefinition definition,
            Object payload,
            ProcessActor actor
    ) {
        return definition.cancellationAssignment(payload).allows(actor);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static TransitionTarget humanTransition(
            ProcessDefinition definition,
            String stepKey,
            String outcomeName
    ) {
        ProcessNode node = requireNode(definition, stepKey);
        if (!(node instanceof HumanTaskNode task)) {
            throw new IllegalStateException(
                    "Persisted step is not a human task: " + stepKey);
        }
        Class<? extends Enum> outcomeType = task.task().outcomeType();
        Enum outcome;
        try {
            outcome = Enum.valueOf(outcomeType, outcomeName);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Unknown outcome " + outcomeName + "; expected "
                            + Arrays.toString(outcomeType.getEnumConstants()));
        }
        ProcessNode target = task.target(outcome);
        if (target == null) {
            throw new IllegalStateException(
                    "No transition for " + outcomeName + " from " + stepKey);
        }
        return new TransitionTarget(target);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ProcessNode<?, ?> subprocessTarget(
            SubprocessNode subprocess,
            Object childStep
    ) {
        ProcessNode<?, ?> target = subprocess.target((Enum) childStep);
        if (target == null) {
            throw new IllegalStateException(
                    "Subprocess has no route for child ending "
                            + ((ProcessStepKey) childStep).key());
        }
        return target;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object mergeSubprocess(
            SubprocessNode subprocess,
            Object parentPayload,
            Object childPayload
    ) {
        return Objects.requireNonNull(
                subprocess.call().merge(parentPayload, childPayload),
                "Subprocess merge returned null parent payload");
    }

    private static ProcessNode<?, ?> requireNode(
            ProcessDefinition<?, ?> definition,
            String stepKey
    ) {
        ProcessNode<?, ?> node = definition.graph().nodeByKey(stepKey);
        if (node == null) {
            throw new IllegalStateException(
                    "Definition " + definition.key() + " v" + definition.version()
                            + " has no persisted step " + stepKey);
        }
        return node;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Audience insertWorkItem(
            Handle handle,
            UUID instanceId,
            UUID tokenId,
            int definitionVersion,
            HumanTaskNode task,
            Object payload,
            Instant now
    ) {
        HumanTask humanTask = task.task();
        TaskAssignment assignment = Objects.requireNonNull(
                humanTask.assignment(payload), "task assignment");
        ProcessDomainLink subject = resolveSubject(
                humanTask.subject(payload), humanTask.subjectLabel(payload));
        UUID workItemId = UUID.randomUUID();
        handle.createUpdate("""
                insert into onno_process_work_items
                    (_id, _instance_id, _token_id, _step_key, _title, _status,
                     _candidate_users, _candidate_roles,
                     _subject_kind, _subject_entity, _subject_id, _subject_label,
                     _created_at, _version)
                values (:id, :instance, :token, :step, :title, 'OPEN',
                        :users, :roles, :subjectKind, :subjectEntity, :subjectId, :subjectLabel,
                        :now, 0)
                """)
                .bind("id", workItemId)
                .bind("instance", instanceId)
                .bind("token", tokenId)
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
        WorkRow row = requireWorkItem(handle, workItemId, false);
        insertWorkItemEvent(
                handle, row, WorkItemEventType.CREATED,
                null, null, null, null, now);
        return new Audience(
                assignment.actors().stream().map(ProcessActorId::value).toList(),
                assignment.roles());
    }

    private void insertWorkItemEvent(
            Handle handle,
            WorkRow work,
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
                .bind("id", UUID.randomUUID())
                .bind("workItem", work.id)
                .bind("instance", work.instanceId)
                .bind("type", type.name())
                .bind("actor", id(actor))
                .bind("actorDisplay", display(actor))
                .bind("fromAssignee", id(fromAssignee))
                .bind("fromDisplay", display(fromAssignee))
                .bind("toAssignee", id(toAssignee))
                .bind("toDisplay", display(toAssignee))
                .bind("reason", reason)
                .bind("now", now)
                .execute();
    }

    private void insertTransition(
            Handle handle,
            UUID instanceId,
            UUID tokenId,
            int definitionVersion,
            ProcessTransitionType type,
            String from,
            String to,
            String outcome,
            ProcessIdentity actor,
            Instant now
    ) {
        handle.createUpdate("""
                insert into onno_process_transitions
                    (_id, _instance_id, _token_id, _definition_version, _transition_type,
                     _from_step, _to_step, _outcome, _actor,
                     _actor_display, _occurred_at, _sequence)
                values (:id, :instance, :token, :definitionVersion, :transitionType,
                        :from, :to, :outcome, :actor, :actorDisplay, :now,
                        (select coalesce(max(t._sequence), 0) + 1
                           from onno_process_transitions t
                          where t._instance_id = :instance))
                """)
                .bind("id", UUID.randomUUID())
                .bind("instance", instanceId)
                .bind("token", tokenId)
                .bind("definitionVersion", definitionVersion)
                .bind("transitionType", type.name())
                .bind("from", from)
                .bind("to", to)
                .bind("outcome", outcome)
                .bind("actor", id(actor))
                .bind("actorDisplay", display(actor))
                .bind("now", now)
                .execute();
    }

    private TokenRow insertToken(
            Handle handle,
            UUID instanceId,
            UUID parentTokenId,
            String branchKey,
            ProcessNode<?, ?> node,
            ProcessTokenStatus status,
            Instant now
    ) {
        UUID id = UUID.randomUUID();
        handle.createUpdate("""
                insert into onno_process_tokens
                    (_id, _instance_id, _parent_token_id, _branch_key,
                     _step_key, _node_type, _status,
                     _entered_at, _updated_at, _attempt, _version)
                values (:id, :instance, :parent, :branch,
                        :step, :nodeType, :status, :now, :now, 0, 0)
                """)
                .bind("id", id)
                .bind("instance", instanceId)
                .bind("parent", parentTokenId)
                .bind("branch", branchKey)
                .bind("step", node.step().key())
                .bind("nodeType", node.type().name())
                .bind("status", status.name())
                .bind("now", now)
                .execute();
        return requireToken(handle, id, false);
    }

    private void updateTokenEntry(
            Handle handle,
            UUID tokenId,
            ProcessNode<?, ?> node,
            Instant now
    ) {
        handle.createUpdate("""
                update onno_process_tokens
                   set _step_key = :step, _node_type = :nodeType, _status = 'READY',
                       _due_at = null, _child_instance_id = null,
                       _entered_at = :now, _updated_at = :now,
                       _version = _version + 1
                 where _id = :id
                """)
                .bind("step", node.step().key())
                .bind("nodeType", node.type().name())
                .bind("now", now)
                .bind("id", tokenId)
                .execute();
    }

    private void setTokenWait(
            Handle handle,
            UUID tokenId,
            ProcessTokenStatus status,
            Instant dueAt,
            UUID childInstanceId,
            Instant now
    ) {
        handle.createUpdate("""
                update onno_process_tokens
                   set _status = :status, _due_at = :dueAt,
                       _child_instance_id = coalesce(:child, _child_instance_id),
                       _updated_at = :now, _version = _version + 1
                 where _id = :id
                """)
                .bind("status", status.name())
                .bind("dueAt", dueAt)
                .bind("child", childInstanceId)
                .bind("now", now)
                .bind("id", tokenId)
                .execute();
    }

    private void persistPayload(
            Handle handle,
            UUID instanceId,
            Object payload,
            Instant now
    ) {
        handle.createUpdate("""
                update onno_process_instances
                   set _payload = :payload, _updated_at = :now
                 where _id = :id
                """)
                .bind("payload", write(payload))
                .bind("now", now)
                .bind("id", instanceId)
                .execute();
    }

    private Optional<InstanceRow> findInstance(Handle handle, UUID id, boolean lock) {
        String sql = "select * from onno_process_instances where _id = :id"
                + (lock ? " for update" : "");
        return handle.createQuery(sql)
                .bind("id", id)
                .map((rs, ctx) -> instanceRow(rs))
                .findOne();
    }

    private InstanceRow requireInstance(Handle handle, UUID id, boolean lock) {
        return findInstance(handle, id, lock)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown process instance: " + id));
    }

    private Optional<TokenRow> findToken(Handle handle, UUID id, boolean lock) {
        String sql = "select * from onno_process_tokens where _id = :id"
                + (lock ? " for update" : "");
        return handle.createQuery(sql)
                .bind("id", id)
                .map((rs, ctx) -> tokenRow(rs))
                .findOne();
    }

    private TokenRow requireToken(Handle handle, UUID id, boolean lock) {
        return findToken(handle, id, lock)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown process token: " + id));
    }

    private List<TokenRow> tokenRows(Handle handle, UUID instanceId, boolean lock) {
        String sql = """
                select * from onno_process_tokens
                 where _instance_id = :instance
                 order by _entered_at, _id
                """ + (lock ? " for update" : "");
        return handle.createQuery(sql)
                .bind("instance", instanceId)
                .map((rs, ctx) -> tokenRow(rs))
                .list();
    }

    private List<TokenRow> liveTokenRows(
            Handle handle,
            UUID instanceId,
            boolean lock
    ) {
        String sql = """
                select * from onno_process_tokens
                 where _instance_id = :instance
                   and _status not in ('COMPLETED', 'CANCELLED')
                 order by _entered_at, _id
                """ + (lock ? " for update" : "");
        return handle.createQuery(sql)
                .bind("instance", instanceId)
                .map((rs, ctx) -> tokenRow(rs))
                .list();
    }

    private WorkRow requireWorkItem(Handle handle, UUID id, boolean lock) {
        String sql = """
                select w.*, i._definition_key, i._definition_version
                  from onno_process_work_items w
                  join onno_process_instances i on i._id = w._instance_id
                 where w._id = :id
                """ + (lock ? " for update" : "");
        return handle.createQuery(sql)
                .bind("id", id)
                .map((rs, ctx) -> workRow(rs))
                .findOne()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown work item: " + id));
    }

    private ProcessSnapshot snapshot(Handle handle, InstanceRow row) {
        List<String> activeSteps = row.status == ProcessStatus.ACTIVE
                ? visibleActiveSteps(liveTokenRows(handle, row.id, false))
                : List.of();
        String current = activeSteps.size() == 1 ? activeSteps.getFirst()
                : activeSteps.size() > 1 ? null : row.currentStep;
        return new ProcessSnapshot(
                row.id, row.definitionKey, row.definitionVersion,
                current, activeSteps, row.status,
                row.rootInstanceId == null ? row.id : row.rootInstanceId,
                row.parentInstanceId, row.parentTokenId,
                actorId(row.startedBy), row.startedByDisplay,
                row.startedAt, row.updatedAt, row.completedAt,
                row.cancelledAt, row.cancelReason, row.version);
    }

    private static List<String> visibleActiveSteps(List<TokenRow> live) {
        Set<UUID> activeParents = new LinkedHashSet<>();
        for (TokenRow token : live) {
            if (token.parentTokenId != null) {
                activeParents.add(token.parentTokenId);
            }
        }
        LinkedHashSet<String> steps = new LinkedHashSet<>();
        for (TokenRow token : live) {
            if (token.nodeType == ProcessNodeType.PARALLEL_FORK
                    && activeParents.contains(token.id)) {
                continue;
            }
            steps.add(token.stepKey);
        }
        return List.copyOf(steps);
    }

    private ProcessWorkItem toWorkItem(WorkRow row) {
        ProcessDefinition<?, ?> definition =
                definitions.require(row.definitionKey, row.definitionVersion);
        ProcessNode<?, ?> node = definition.graph().nodeByKey(row.stepKey);
        List<String> outcomes = node instanceof HumanTaskNode<?, ?, ?> task
                ? Arrays.stream(task.task().outcomeType().getEnumConstants())
                .map(Enum::name)
                .toList()
                : List.of();
        return new ProcessWorkItem(
                row.id, row.instanceId, row.tokenId,
                row.definitionKey, row.definitionVersion,
                row.stepKey, row.title, row.status,
                actorId(row.assignee), row.assigneeDisplay, row.subject(),
                row.createdAt, row.claimedAt, row.completedAt,
                row.outcome, outcomes);
    }

    private void requireCandidate(WorkRow row, ProcessActor actor) {
        if (!assignment(row).allows(actor)) {
            throw new SecurityException("User is not a candidate for this work item");
        }
    }

    private TaskAssignment assignment(WorkRow row) {
        return new TaskAssignment(
                readSet(row.candidateUsers).stream()
                        .map(ProcessActorId::of)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                readSet(row.candidateRoles));
    }

    private Audience audience(WorkRow row) {
        Audience audience =
                new Audience(readSet(row.candidateUsers), readSet(row.candidateRoles));
        return row.assignee == null ? audience : audience.withUser(row.assignee);
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

    private String write(Object value) {
        return payloadCodec.write(value);
    }

    private <T> T read(String value, Class<T> type) {
        return payloadCodec.read(value, type);
    }

    private Set<String> readSet(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        String[] values = payloadCodec.read(value, String[].class);
        return Set.copyOf(Arrays.asList(values));
    }

    private void publish(Mutation<?> mutation) {
        if (mutation != null && !mutation.audience.isEmpty()) {
            events.publish(new ProcessTasksChangedEvent(
                    mutation.instanceId,
                    Set.copyOf(mutation.audience.users),
                    Set.copyOf(mutation.audience.roles)));
        }
    }

    private static boolean isAdmin(ProcessActor actor) {
        return actor.roles().contains("ADMIN");
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

    private static Instant instant(
            java.sql.ResultSet rs,
            String column
    ) throws java.sql.SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static InstanceRow instanceRow(
            java.sql.ResultSet rs
    ) throws java.sql.SQLException {
        return new InstanceRow(
                rs.getObject("_id", UUID.class),
                rs.getString("_definition_key"),
                rs.getInt("_definition_version"),
                rs.getString("_definition_fingerprint"),
                rs.getString("_payload"),
                rs.getString("_current_step"),
                ProcessStatus.valueOf(rs.getString("_status")),
                rs.getObject("_root_instance_id", UUID.class),
                rs.getObject("_parent_instance_id", UUID.class),
                rs.getObject("_parent_token_id", UUID.class),
                rs.getString("_started_by"),
                rs.getString("_started_by_display"),
                instant(rs, "_started_at"),
                instant(rs, "_updated_at"),
                instant(rs, "_completed_at"),
                instant(rs, "_cancelled_at"),
                rs.getString("_cancel_reason"),
                rs.getInt("_version"));
    }

    private static TokenRow tokenRow(
            java.sql.ResultSet rs
    ) throws java.sql.SQLException {
        return new TokenRow(
                rs.getObject("_id", UUID.class),
                rs.getObject("_instance_id", UUID.class),
                rs.getObject("_parent_token_id", UUID.class),
                rs.getString("_branch_key"),
                rs.getString("_step_key"),
                ProcessNodeType.valueOf(rs.getString("_node_type")),
                ProcessTokenStatus.valueOf(rs.getString("_status")),
                instant(rs, "_due_at"),
                rs.getObject("_child_instance_id", UUID.class),
                instant(rs, "_entered_at"),
                instant(rs, "_updated_at"),
                rs.getInt("_attempt"),
                rs.getInt("_version"));
    }

    private static WorkRow workRow(
            java.sql.ResultSet rs
    ) throws java.sql.SQLException {
        return new WorkRow(
                rs.getObject("_id", UUID.class),
                rs.getObject("_instance_id", UUID.class),
                rs.getObject("_token_id", UUID.class),
                rs.getString("_definition_key"),
                rs.getInt("_definition_version"),
                rs.getString("_step_key"),
                rs.getString("_title"),
                WorkItemStatus.valueOf(rs.getString("_status")),
                rs.getString("_candidate_users"),
                rs.getString("_candidate_roles"),
                rs.getString("_assignee"),
                rs.getString("_assignee_display"),
                rs.getString("_subject_kind"),
                rs.getString("_subject_entity"),
                rs.getObject("_subject_id", UUID.class),
                rs.getString("_subject_label"),
                instant(rs, "_created_at"),
                instant(rs, "_claimed_at"),
                instant(rs, "_completed_at"),
                rs.getString("_outcome"),
                rs.getInt("_version"));
    }

    private static ProcessTokenSnapshot toTokenSnapshot(TokenRow row) {
        return new ProcessTokenSnapshot(
                row.id, row.instanceId, row.parentTokenId, row.branchKey,
                row.stepKey, row.nodeType, row.status, row.dueAt,
                row.childInstanceId, row.enteredAt, row.updatedAt,
                row.attempt, row.version);
    }

    private static String stepKey(ProcessNode<?, ?> node) {
        return node.step().key();
    }

    private record InstanceRow(
            UUID id,
            String definitionKey,
            int definitionVersion,
            String definitionFingerprint,
            String payload,
            String currentStep,
            ProcessStatus status,
            UUID rootInstanceId,
            UUID parentInstanceId,
            UUID parentTokenId,
            String startedBy,
            String startedByDisplay,
            Instant startedAt,
            Instant updatedAt,
            Instant completedAt,
            Instant cancelledAt,
            String cancelReason,
            int version
    ) {
    }

    private record TokenRow(
            UUID id,
            UUID instanceId,
            UUID parentTokenId,
            String branchKey,
            String stepKey,
            ProcessNodeType nodeType,
            ProcessTokenStatus status,
            Instant dueAt,
            UUID childInstanceId,
            Instant enteredAt,
            Instant updatedAt,
            int attempt,
            int version
    ) {
    }

    private record WorkRow(
            UUID id,
            UUID instanceId,
            UUID tokenId,
            String definitionKey,
            int definitionVersion,
            String stepKey,
            String title,
            WorkItemStatus status,
            String candidateUsers,
            String candidateRoles,
            String assignee,
            String assigneeDisplay,
            String subjectKind,
            String subjectEntity,
            UUID subjectId,
            String subjectLabel,
            Instant createdAt,
            Instant claimedAt,
            Instant completedAt,
            String outcome,
            int version
    ) {
        WorkRow claimed(ProcessIdentity identity, Instant at) {
            return new WorkRow(
                    id, instanceId, tokenId, definitionKey, definitionVersion,
                    stepKey, title, WorkItemStatus.CLAIMED,
                    candidateUsers, candidateRoles,
                    identity.id().value(), identity.displayName(),
                    subjectKind, subjectEntity, subjectId, subjectLabel,
                    createdAt, at, completedAt, outcome, version + 1);
        }

        WorkRow delegated(ProcessIdentity identity) {
            return new WorkRow(
                    id, instanceId, tokenId, definitionKey, definitionVersion,
                    stepKey, title, WorkItemStatus.CLAIMED,
                    candidateUsers, candidateRoles,
                    identity.id().value(), identity.displayName(),
                    subjectKind, subjectEntity, subjectId, subjectLabel,
                    createdAt, claimedAt, completedAt, outcome, version + 1);
        }

        ProcessIdentity assigneeIdentity() {
            return assignee == null ? null : new ProcessIdentity(
                    ProcessActorId.of(assignee), assigneeDisplay, assigneeDisplay);
        }

        ProcessDomainLink subject() {
            return subjectId == null ? null : new ProcessDomainLink(
                    subjectKind, subjectEntity, subjectId, subjectLabel);
        }
    }

    private static final class RuntimeState {
        private final InstanceRow instance;
        private final ProcessDefinition<?, ?> definition;
        private Object payload;
        private final ProcessIdentity actor;
        private final Instant now;
        private final Audience audience;
        private int immediateSteps;

        private RuntimeState(
                InstanceRow instance,
                ProcessDefinition<?, ?> definition,
                Object payload,
                ProcessIdentity actor,
                Instant now,
                Audience audience
        ) {
            this.instance = instance;
            this.definition = definition;
            this.payload = payload;
            this.actor = actor;
            this.now = now;
            this.audience = audience;
        }
    }

    private record TransitionTarget(ProcessNode<?, ?> node) {
    }

    private record MigrationData(
            Object payload,
            Map<UUID, ProcessNode<?, ?>> targets
    ) {
    }

    private record Mutation<T>(T value, UUID instanceId, Audience audience) {
    }

    private static final class Audience {
        private final LinkedHashSet<String> users = new LinkedHashSet<>();
        private final LinkedHashSet<String> roles = new LinkedHashSet<>();

        private Audience() {
        }

        private Audience(Iterable<String> users, Iterable<String> roles) {
            users.forEach(this.users::add);
            roles.forEach(this.roles::add);
        }

        private Audience withUser(String user) {
            if (user != null && !user.isBlank()) {
                users.add(user);
            }
            return this;
        }

        private Audience merge(Audience other) {
            if (other != null) {
                users.addAll(other.users);
                roles.addAll(other.roles);
            }
            return this;
        }

        private boolean isEmpty() {
            return users.isEmpty() && roles.isEmpty();
        }
    }
}
