# Formal models

Example-based and property-based tests check the code on the inputs someone (or jqwik) thought of.
The models here check a *design* exhaustively: TLC enumerates every interleaving of a small but
complete instance and verifies invariants in every reachable state, plus termination under fairness.

| Model | What it covers | Java it abstracts |
| --- | --- | --- |
| `ProcessTokens.tla` | Execution tokens of one process instance across a parallel fork/join: human tasks completing in any order (with a rework loop), a durable timer, a subprocess that completes or is cancelled, cancellation at any point, and a v1 to v2 definition migration that re-maps every live token. | `su.onno.process.JdbcProcessEngine` (`start`, `complete`, `cancel`, `migrate`, `runPending`, `enter`, `arriveAtJoin`, `refreshInstance`) |

## Running

```bash
docs/formal/check.sh                     # the model of the engine as it is: must pass
docs/formal/check.sh --counterexamples   # also the configs that must FAIL (the model still has teeth)
./gradlew checkFormalModels              # the same as --counterexamples, from Gradle
```

`check.sh` downloads the pinned `tla2tools.jar` (TLA+ tools 1.7.4, SHA-256 checked) into
`build/tla/` on first use; set `TLA2TOOLS_JAR=/path/to/tla2tools.jar` to use your own copy. Logs
and TLC's state directory also go to `build/tla/`. `checkFormalModels` is **not** part of
`./gradlew check`, so offline builds are unaffected; run it when you change the process engine.
The whole run takes a few seconds (about 3.6k distinct states for the main configuration).

## The graph

```text
start -> FORK ==(A)==> HA  --APPROVE--> JOIN     HA --REWORK--> HA  (at most MaxRework times)
              ==(B)==> TB (timer) -> HB --DONE--> JOIN
              ==(C)==> SC (subprocess: one human task in the child)
                       SC --child completed | child cancelled--> JOIN
JOIN -> FINAL (human) --DONE--> END
```

Three branches of different kinds (human with a loop, timer then human, subprocess) are enough to
exercise every wait state a branch token can be in when the join fires, the instance is
cancelled, or a migration runs.

## Mapping spec variables to the implementation

| Spec | Java / table | Notes |
| --- | --- | --- |
| `inst` | `onno_process_instances._status` | `NONE` stands for "not started yet". |
| `tok[t].step`, `tok[t].st` | `onno_process_tokens._step_key`, `._status` (`ProcessTokenStatus`) | `root` is the instance's first token, which becomes the fork's coordinator; `A`/`B`/`C` are the branch tokens (`_parent_token_id` = root, `_branch_key` = branch). |
| `Coordinator` = `[FORK, WAITING_JOIN]` | the fork token after `enter(PARALLEL_FORK)` | `arriveAtJoin` requires exactly this, or throws "coordinator was already advanced" (`err`). |
| `open[t]` | count of `onno_process_work_items` rows of token `t` with `_status in ('OPEN','CLAIMED')` | Claiming is not modelled separately: it changes neither tokens nor the open count. |
| `child`, `childOpen` | the child `onno_process_instances` row of `SC` and its open work item | The child's own token is implied by `child = ACTIVE`. |
| `joinFired` | ghost (count of `JOIN` transitions in `onno_process_transitions`) | |
| `version` | `onno_process_instances._definition_version` | |
| `mig` | the body of the `migrate()` loop still to run | Present only between `MigStart` and the last `MigStep`; all other actions are disabled meanwhile, because `migrate()` is one transaction. |
| `err` | an `IllegalStateException` from `arriveAtJoin` | Would roll the transaction back; the model flags that it was reachable. |
| `Enter(w, t, s)` | `JdbcProcessEngine.enter` | One operator per node type of this graph. |
| `ArriveAtJoin` | `JdbcProcessEngine.arriveAtJoin` | Counts branch tokens `WAITING_JOIN` at the join step, exactly like the SQL `count(*)`. |
| `Refresh` | `JdbcProcessEngine.refreshInstance` | No live token left => `COMPLETED`. |
| `CompleteHuman`, `FireTimer`, `ResumeSubprocess`, `Cancel`, `MigStart`/`MigStep` | `complete`, `runPending` (timer / subprocess branch of `advancePending`), `cancel`, `migrate` | Each action is one transaction under the instance row lock (`select ... for update`), so actions are atomic and serialised per instance. |
| `ChildComplete`, `ChildCancel` | `complete` / `cancel` called on the child instance | |

## Properties checked

| Name | Statement |
| --- | --- |
| `JoinFiresOnce` | The join fires at most once, and the coordinator check in `arriveAtJoin` never trips. |
| `JoinAfterAllBranches` | Once the coordinator is past the join, every branch token completed *at the join*: the join fired only after all branches arrived. |
| `NoOrphanBranch` | Between transactions, a live branch token exists only while the coordinator waits at the fork. |
| `NoLostBranch` | Between transactions, a coordinator waiting at the fork of an active instance has every branch live. |
| `TerminalIsClean` | A completed or cancelled instance has no live token, no open work item, and no active child (cancellation leaves no open work). |
| `WorkMatchesTokens` | Exactly one open work item per token waiting at a human step and none otherwise: no orphaned or duplicated tasks. |
| `ActiveHasWork` | An active instance always has a live token (`refreshInstance` completes it exactly when the last one finishes). |
| `Termination` | Under weak fairness of users, timers and the job runner (cancellation and migration may never happen) every instance eventually completes or is cancelled. |

## Configurations

| Config | Expected | Models |
| --- | --- | --- |
| `ProcessTokens.cfg` | pass | The engine as it is. |
| `ProcessTokensSequentialMigration.cfg` | **violation** | The `migrate()` loop before the fix that accompanied this model (reset and re-enter one token at a time). |
| `ProcessTokensEscapingMigration.cfg` | **violation** | A `ProcessDefinitionMigration` that maps a branch token outside its branch. A known gap: the engine does not reject such a mapping today. |

### What the model found

1. **Migration could fire a join early and resurrect finished branches (fixed).** Trace from
   `ProcessTokensSequentialMigration.cfg`: A completes its review and waits at `JOIN`; B waits on
   its timer, C on its subprocess. A migration maps B and C to `JOIN` and sends A back to `HA`.
   `migrate()` visits tokens in `(_entered_at, _id)` order, so B and C come before A. When C
   re-enters `JOIN` the join counts A's v1 row, which still says "waiting at the join", and fires:
   all three branches complete and the coordinator moves to `FINAL`. The loop then resets A
   (already completed) and re-enters `HA` with a new work item: a live branch behind an advanced
   coordinator (`NoOrphanBranch`), which, once completed, waits at the join forever, so the instance
   never completes. Fix: `migrate()` now resets every live token before re-entering any, which is
   what `ProcessTokens.cfg` models. Regression test:
   `JdbcExtendedProcessEngineTest.migrationThatMovesABranchIntoTheJoinDoesNotCountSiblingsThatAreLeavingIt`.
2. **A migration may map a branch token outside its branch (open).** Trace from
   `ProcessTokensEscapingMigration.cfg`: right after start, a migration maps C to `END`. The
   coordinator stays at the fork waiting for three arrivals while C has completed without arriving
   (`NoLostBranch`); the instance can never finish except by cancellation. `JdbcProcessEngine`
   checks that a coordinator maps to a fork with the same branch keys but not that its branch
   tokens map inside that fork's branches. A fix needs the branch region of each fork from the
   graph (`ProcessGraph`), so it is reported rather than fixed here.

## Intentionally abstracted

- **One instance, one graph.** Process-definition validation (`ProcessGraph`), payloads, automatic
  steps and decisions (immediate nodes that run inside the same transaction as the step that
  reaches them) are not modelled: they do not create, wait on or remove tokens.
- **Assignment and access.** Candidates, claiming, delegation and the `ADMIN` superuser change who
  may act, not what a completion does to tokens.
- **Time.** A timer is simply "due at some point"; the job runner may fire it in any later state.
- **Concurrency.** The instance row lock serialises every mutation of one instance, so each Java
  transaction is one atomic TLA+ action; database isolation levels are not modelled.
- **Nested forks and several subprocess levels.** The engine's rules are recursive (a branch
  token can coordinate an inner fork; cancellation recurses into children). One level of each is
  modelled; deeper nesting reuses the same rules.
- **Migration paths.** One migration (v1 to v2 with the same step keys) per instance; the
  application's mapping is any function from live tokens to steps that the engine accepts (the
  coordinator to the fork, a branch token inside its branch or to the join).
- **Events.** `ProcessTasksChangedEvent` / SSE invalidations happen after commit and do not
  affect state.
