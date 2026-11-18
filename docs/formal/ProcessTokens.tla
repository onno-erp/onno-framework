--------------------------- MODULE ProcessTokens ---------------------------
(***************************************************************************)
(* Execution tokens of one onno process instance (JdbcProcessEngine) over  *)
(* a fixed graph with a structured parallel fork/join:                     *)
(*                                                                         *)
(*   start -> FORK ==(A)==> HA --APPROVE--> JOIN                           *)
(*                 |        HA --REWORK---> HA      (bounded self-loop)    *)
(*                 ==(B)==> TB (timer) -> HB --DONE--> JOIN                *)
(*                 ==(C)==> SC (subprocess: one human task in the child)   *)
(*                          SC --child completed|cancelled--> JOIN         *)
(*   JOIN -> FINAL (human) --DONE--> END                                   *)
(*                                                                         *)
(* Every action is one database transaction of the Java engine, which      *)
(* holds the instance row lock (select ... for update), so actions are     *)
(* atomic and serialised per instance. A definition migration is one       *)
(* transaction too, but its body is a loop whose intermediate states       *)
(* matter; it is modelled as MigStart + MigStep micro-steps during which   *)
(* every other action is disabled (mig /= <<>>).                           *)
(*                                                                         *)
(* See docs/formal/README.md for the variable -> table/class mapping.      *)
(***************************************************************************)
EXTENDS Naturals, Sequences, FiniteSets

CONSTANTS
    MaxRework,              \* bound on the REWORK self-loop of HA
    ResetAllBeforeEnter,    \* TRUE: migrate() resets every live token before re-entering any
                            \* (the current Java); FALSE: reset+enter one token at a time
                            \* (the original loop, kept to reproduce the bug it had)
    AllowEscapingMigration  \* TRUE: a migration may map a branch token outside its branch
                            \* (to FINAL/END); the Java does not validate this today

Root     == "root"
Branches == {"A", "B", "C"}
Tokens   == {Root} \cup Branches

Steps    == {"-", "FORK", "HA", "TB", "HB", "SC", "JOIN", "FINAL", "END"}
Status   == {"NONE", "READY", "WAITING_HUMAN", "WAITING_TIMER", "WAITING_JOIN",
             "WAITING_SUBPROCESS", "COMPLETED", "CANCELLED"}
Live     == {"READY", "WAITING_HUMAN", "WAITING_TIMER", "WAITING_JOIN", "WAITING_SUBPROCESS"}

FirstStep(b) == CASE b = "A" -> "HA" [] b = "B" -> "TB" [] b = "C" -> "SC"
Region(b)    == CASE b = "A" -> {"HA"} [] b = "B" -> {"TB", "HB"} [] b = "C" -> {"SC"}

Tok(s, st)  == [step |-> s, st |-> st]
Coordinator == Tok("FORK", "WAITING_JOIN")
AtJoin      == Tok("JOIN", "WAITING_JOIN")

VARIABLES
    inst,       \* onno_process_instances._status: NONE (not started) | ACTIVE | COMPLETED | CANCELLED
    tok,        \* onno_process_tokens: Tokens -> [step, st]
    open,       \* onno_process_work_items with _status in (OPEN, CLAIMED), counted per token
    child,      \* the subprocess instance of SC: NONE | ACTIVE | COMPLETED | CANCELLED
    childOpen,  \* open work items of the child instance
    rework,     \* REWORK outcomes taken so far (bounds the self-loop)
    joinFired,  \* ghost: how many times the parallel join has fired
    version,    \* _definition_version: 1, or 2 after a migration
    mig,        \* the migration loop still to run: <<[t, to, hadChild, coord]>>
    err         \* ghost: the Java would have thrown "coordinator was already advanced"

vars == <<inst, tok, open, child, childOpen, rework, joinFired, version, mig, err>>

(* The tables an `enter` may touch, as one record, so enter can be a pure operator. *)
W == [tok |-> tok, open |-> open, child |-> child, childOpen |-> childOpen,
      joinFired |-> joinFired, err |-> err]

SetW(w) == /\ tok' = w.tok /\ open' = w.open /\ child' = w.child
           /\ childOpen' = w.childOpen /\ joinFired' = w.joinFired /\ err' = w.err

(***************************************************************************)
(* arriveAtJoin: the branch token waits at JOIN; when the number of        *)
(* branch tokens waiting at JOIN reaches the fork's branch count, all of   *)
(* them complete and the coordinator (which must still wait at FORK)       *)
(* enters the join's target.                                               *)
(***************************************************************************)
ArriveAtJoin(w, t) ==
    LET w1      == [w EXCEPT !.tok[t] = AtJoin]
        arrived == {b \in Branches : w1.tok[b] = AtJoin}
    IN  IF t = Root THEN [w1 EXCEPT !.err = TRUE]   \* "has no coordinating fork token"
        ELSE IF Cardinality(arrived) < Cardinality(Branches) THEN w1
        ELSE IF w1.tok[Root] /= Coordinator THEN [w1 EXCEPT !.err = TRUE]
        ELSE [w1 EXCEPT !.tok = [x \in Tokens |->
                                    IF x \in arrived THEN Tok("JOIN", "COMPLETED")
                                    ELSE IF x = Root THEN Tok("FINAL", "WAITING_HUMAN")
                                    ELSE w1.tok[x]],
                        !.open[Root] = @ + 1,
                        !.joinFired  = @ + 1]

(* JdbcProcessEngine.enter for the node types of this graph (FORK only at start). *)
Enter(w, t, s) ==
    CASE s \in {"HA", "HB", "FINAL"} ->
              [w EXCEPT !.tok[t] = Tok(s, "WAITING_HUMAN"), !.open[t] = @ + 1]
      [] s = "TB"   -> [w EXCEPT !.tok[t] = Tok(s, "WAITING_TIMER")]
      [] s = "SC"   -> [w EXCEPT !.tok[t] = Tok(s, "WAITING_SUBPROCESS"),
                                 !.child = "ACTIVE", !.childOpen = 1]
      [] s = "END"  -> [w EXCEPT !.tok[t] = Tok(s, "COMPLETED")]
      [] s = "JOIN" -> ArriveAtJoin(w, t)

LiveTokens(tk) == {t \in Tokens : tk[t].st \in Live}

(* refreshInstance: no live token left => the instance is COMPLETED. *)
Refresh(tk, i) == IF i = "ACTIVE" /\ LiveTokens(tk) = {} THEN "COMPLETED" ELSE i

Quiet == inst = "ACTIVE" /\ mig = <<>>

-----------------------------------------------------------------------------
Init == /\ inst = "NONE"
        /\ tok = [t \in Tokens |-> Tok("-", "NONE")]
        /\ open = [t \in Tokens |-> 0]
        /\ child = "NONE" /\ childOpen = 0
        /\ rework = 0 /\ joinFired = 0 /\ version = 1
        /\ mig = <<>> /\ err = FALSE

(* start(): root enters FORK, becomes the coordinator, one token per branch enters its first step. *)
Start ==
    /\ inst = "NONE"
    /\ LET w0 == [W EXCEPT !.tok[Root] = Coordinator]
           w  == Enter(Enter(Enter(w0, "A", FirstStep("A")), "B", FirstStep("B")), "C", FirstStep("C"))
       IN SetW(w)
    /\ inst' = "ACTIVE"
    /\ UNCHANGED <<rework, version, mig>>

(* complete(workItem, outcome): the open item of a token waiting at a human step. *)
CompleteHuman(t, outcome) ==
    /\ Quiet
    /\ tok[t].st = "WAITING_HUMAN" /\ open[t] > 0
    /\ outcome = "REWORK" => (tok[t].step = "HA" /\ rework < MaxRework)
    /\ LET s      == tok[t].step
           target == CASE outcome = "REWORK" -> "HA"
                       [] s \in {"HA", "HB"} -> "JOIN"
                       [] s = "FINAL"        -> "END"
           w      == Enter([W EXCEPT !.open[t] = @ - 1], t, target)
       IN /\ SetW(w)
          /\ inst' = Refresh(w.tok, inst)
    /\ rework' = IF outcome = "REWORK" THEN rework + 1 ELSE rework
    /\ UNCHANGED <<version, mig>>

(* runPending(): a due timer advances its token. *)
FireTimer(t) ==
    /\ Quiet
    /\ tok[t].st = "WAITING_TIMER"
    /\ LET w == Enter(W, t, "HB") IN SetW(w) /\ inst' = Refresh(w.tok, inst)
    /\ UNCHANGED <<rework, version, mig>>

(* The child instance is its own process: its human task completes it, or someone cancels it. *)
ChildComplete ==
    /\ mig = <<>> /\ child = "ACTIVE" /\ childOpen = 1
    /\ child' = "COMPLETED" /\ childOpen' = 0
    /\ UNCHANGED <<inst, tok, open, rework, joinFired, version, mig, err>>

ChildCancel ==
    /\ mig = <<>> /\ child = "ACTIVE"
    /\ child' = "CANCELLED" /\ childOpen' = 0
    /\ UNCHANGED <<inst, tok, open, rework, joinFired, version, mig, err>>

(* runPending(): a finished child resumes the waiting subprocess token (both routes lead to JOIN). *)
ResumeSubprocess(t) ==
    /\ Quiet
    /\ tok[t].st = "WAITING_SUBPROCESS" /\ child \in {"COMPLETED", "CANCELLED"}
    /\ LET w == Enter(W, t, "JOIN") IN SetW(w) /\ inst' = Refresh(w.tok, inst)
    /\ UNCHANGED <<rework, version, mig>>

(* cancel(): open/claimed work, live tokens and active descendants are cancelled atomically. *)
Cancel ==
    /\ Quiet
    /\ inst' = "CANCELLED"
    /\ tok' = [t \in Tokens |-> IF tok[t].st \in Live THEN Tok(tok[t].step, "CANCELLED") ELSE tok[t]]
    /\ open' = [t \in Tokens |-> 0]
    /\ child' = IF child = "ACTIVE" THEN "CANCELLED" ELSE child
    /\ childOpen' = 0
    /\ UNCHANGED <<rework, joinFired, version, mig, err>>

(***************************************************************************)
(* migrate(): v1 -> v2 (same step keys). The application's migration maps *)
(* every live token to a target step; Java requires a coordinator of live *)
(* branches to map to a parallel fork with the same branch keys (FORK).   *)
(* Open work is cancelled, then tokens are processed parent-first, the    *)
(* branches in (_entered_at, _id) order, which can be any order.          *)
(***************************************************************************)
Perms(S) == {f \in [1..Cardinality(S) -> S] : \A i, j \in 1..Cardinality(S) : i /= j => f[i] /= f[j]}

ValidTarget(t, s, coordinates) ==
    IF t = Root
    THEN IF coordinates THEN s = "FORK" ELSE s = "FINAL"
    ELSE s \in Region(t) \cup {"JOIN"}
              \cup (IF AllowEscapingMigration THEN {"FINAL", "END"} ELSE {})

MigStart ==
    /\ Quiet /\ version = 1
    /\ LET live        == LiveTokens(tok)
           liveBranch  == live \cap Branches
           coordinates == Root \in live /\ liveBranch /= {}
       IN \E target \in [live -> Steps] :
          \E order \in Perms(liveBranch) :
            /\ \A t \in live : ValidTarget(t, target[t], coordinates)
            /\ LET queue == (IF Root \in live THEN <<Root>> ELSE <<>>) \o order
               IN mig' = [i \in 1..Len(queue) |->
                            [t        |-> queue[i],
                             to       |-> target[queue[i]],
                             hadChild |-> tok[queue[i]].st = "WAITING_SUBPROCESS",
                             coord    |-> queue[i] = Root /\ coordinates]]
            /\ tok' = IF ResetAllBeforeEnter
                      THEN [t \in Tokens |-> IF t \in live THEN Tok(target[t], "READY") ELSE tok[t]]
                      ELSE tok
    /\ open' = [t \in Tokens |-> 0]          \* cancelActiveWork
    /\ version' = 2
    /\ UNCHANGED <<inst, child, childOpen, rework, joinFired, err>>

MigStep ==
    /\ mig /= <<>>
    /\ LET m  == Head(mig)
           w1 == IF m.hadChild /\ child = "ACTIVE"
                 THEN [W EXCEPT !.child = "CANCELLED", !.childOpen = 0] ELSE W
           w2 == [w1 EXCEPT !.tok[m.t] = Tok(m.to, "READY")]   \* resetTokenForMigration
           w3 == IF m.coord
                 THEN [w2 EXCEPT !.tok[m.t] = Coordinator]      \* preserveParallelCoordinator
                 ELSE Enter(w2, m.t, m.to)
       IN /\ SetW(w3)
          /\ mig' = Tail(mig)
          /\ inst' = IF Len(mig) = 1 THEN Refresh(w3.tok, inst) ELSE inst
    /\ UNCHANGED <<rework, version>>

(* A finished instance stutters; lets TLC tell real deadlocks from termination. *)
Terminated == inst \in {"COMPLETED", "CANCELLED"} /\ UNCHANGED vars

Progress ==
    \/ Start
    \/ \E t \in Tokens, o \in {"APPROVE", "REWORK"} : CompleteHuman(t, o)
    \/ \E t \in Tokens : FireTimer(t) \/ ResumeSubprocess(t)
    \/ ChildComplete
    \/ MigStep

Next == Progress \/ ChildCancel \/ Cancel \/ MigStart \/ Terminated

(* Users, timers and the job runner keep acting (weak fairness); cancels and migrations may never happen. *)
Spec == Init /\ [][Next]_vars /\ WF_vars(Progress)

-----------------------------------------------------------------------------
TypeOK ==
    /\ inst \in {"NONE", "ACTIVE", "COMPLETED", "CANCELLED"}
    /\ tok \in [Tokens -> [step : Steps, st : Status]]
    /\ open \in [Tokens -> 0..3]
    /\ child \in {"NONE", "ACTIVE", "COMPLETED", "CANCELLED"} /\ childOpen \in 0..1
    /\ rework \in 0..MaxRework /\ joinFired \in 0..3 /\ version \in {1, 2}
    /\ err \in BOOLEAN

(* The join never fires twice, and the Java coordinator check never trips. *)
JoinFiresOnce == joinFired <= 1 /\ ~err

(* Fork/join nesting, between transactions (a migration is one transaction: mig /= <<>> is     *)
(* its inside): a live branch token exists only while the coordinator waits at the fork, and  *)
(* the waiting coordinator has every branch live: no orphaned and no lost branch.             *)
NoOrphanBranch   == mig = <<>> => \A b \in Branches : tok[b].st \in Live => tok[Root] = Coordinator
NoLostBranch     == (mig = <<>> /\ inst = "ACTIVE" /\ tok[Root] = Coordinator)
                        => \A b \in Branches : tok[b].st \in Live

(* The join fired only after every branch arrived: past the join, every branch completed AT the join. *)
JoinAfterAllBranches ==
    (inst /= "CANCELLED" /\ tok[Root].step \in {"FINAL", "END"} /\ mig = <<>>)
        => \A b \in Branches : tok[b] = Tok("JOIN", "COMPLETED")

(* A completed or cancelled instance has no live token and no open work, in itself or its child. *)
TerminalIsClean ==
    inst \in {"COMPLETED", "CANCELLED"} =>
        /\ LiveTokens(tok) = {}
        /\ \A t \in Tokens : open[t] = 0
        /\ child /= "ACTIVE" /\ childOpen = 0

(* Work items track tokens one-to-one: exactly one open item per token waiting at a human step. *)
WorkMatchesTokens ==
    mig = <<>> => \A t \in Tokens : open[t] = IF tok[t].st = "WAITING_HUMAN" THEN 1 ELSE 0

(* An active, quiescent instance always has a live token (refreshInstance is exact). *)
ActiveHasWork == Quiet => LiveTokens(tok) /= {}

Invariants == TypeOK /\ JoinFiresOnce /\ NoOrphanBranch /\ NoLostBranch
              /\ JoinAfterAllBranches /\ TerminalIsClean /\ WorkMatchesTokens /\ ActiveHasWork

(* Termination: under fairness every started instance completes or is cancelled. *)
Termination == <>(inst \in {"COMPLETED", "CANCELLED"})
=============================================================================
