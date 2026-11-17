---
name: onno-commands
description: >-
  Declare onno-framework server behaviour beyond create/update/delete in Java: Commands<E>
  (record and collection commands with roles, per-record availableWhen/enabledWhen and titles,
  semantic groups, inputs and repeatable input rows, server-computed defaults, dynamic commands
  from live data, CommandResult feedback and CommandRejectedException), Checks<E> (live
  as-you-type advisory form feedback with dependsOn and debounce), PickerOptions<E> (badges,
  disabled reasons and hidden candidates in one reference field's picker) and IdentityLink (which
  catalog record a login is). Use when adding a button that changes data, a bulk/wizard action,
  a "Change status" or "Assign" menu, schedule-conflict warnings, availability badges, task
  routing to employees, or exposing an action to AI agents — every command is also a REST
  endpoint and an MCP tool.
---

# onno commands, checks and picker options

Behaviour lives in Java beans in `su.onno.command`, `su.onno.validation`, `su.onno.picker` and
`su.onno.identity`. They say **what** can be done and who may do it; how the UI offers it is the
entity's `commands` customization in React (`onno-ui-entities`). One declaration serves every
surface with the same rules:

| Surface | How a command appears |
| --- | --- |
| UI | Record header menu, list row menu/inline button, toolbar, batch over a selection; inputs in a dialog |
| React code | `useCommand("Orders", "advance").mutate({ id, inputs })` |
| REST | `GET /api/commands/{kind}/{name}[?id=]`, `POST /api/commands/{kind}/{name}/{key}` (`{id?, inputs}`), `GET …/{key}/defaults`, `POST …/{key}/batch` (`{ids, inputs}`) |
| MCP | A tool `<entity>_<key>` per static command (see below) |

Everywhere the server enforces entity write access, the command's `roles`, read-only mode
(`onno.web.read-only`), record scope (`RecordAccessPolicy`) and the per-record
`availableWhen`/`enabledWhen`.

## Commands

```java
@Component
public class OrderCommands implements Commands<Order> {

    private final OrderRepository orders;

    public OrderCommands(OrderRepository orders) {
        this.orders = orders;
    }

    @Override
    public Class<Order> entity() {
        return Order.class;
    }

    @Override
    public void define(CommandSpec<Order> c) {
        c.command("cancel").title("Cancel order")
                .description("Cancels the order and records the reason in its note.")
                .availableWhen(row -> !terminal(row.enumValue(Order::getStatus, OrderStatus.class)))
                .input("reason", i -> i.title("Reason").longText().required())
                .run(ctx -> cancel(ctx.id(), ctx.input("reason")));
    }
    // cancel(...) and terminal(...) are ordinary private methods returning CommandResult / boolean.
}
```

`CommandSpec<E>`:

- `command(key)` — a **record** command; runs on one record (`ctx.id()`). Title, availability and
  enabled state may vary per record.
- `collectionCommand(key)` — runs on the entity as a whole (an import, a "receive shipment"
  wizard); no id; no per-record functions (startup fails if you add them).
- `dynamic(live -> …)` — commands whose keys come from live data (one "Assign to X" per employee),
  re-evaluated whenever the command list is requested or one of them runs. Keep it read-only and
  fast.

Several beans for one entity merge; the first declaring a key wins.

`CommandBuilder<E>`:

| Method | Meaning |
| --- | --- |
| `title(String)` | Fixed title (default: the key). Used wherever no single record is in hand (batch, MCP). Always set it. |
| `title(row -> …)` | Per-record title (`"→ Shipped"`, `"Resume"`/`"Suspend"`). |
| `description(String)` | One or two sentences: the MCP tool description and UI tooltip. Write it for an AI agent. |
| `group(String)` | Semantic grouping; the UI shows commands sharing a group as one submenu (`"Change status"`). |
| `roles(String...)` | Any-of role gate on top of entity write access (`ADMIN` always passes). |
| `availableWhen(row -> …)` | Offered and runnable only where true; elsewhere not listed and rejected. |
| `enabledWhen(row -> …)` | Listed but disabled where false. |
| `input(key, i -> …)` | A scalar input: `text()` (default), `longText()`, `date()` (ISO), `number()`, `oneOf("A", "B")`, `reference(Supplier.class)` (a record id), `defaultValue(…)`, `required()`, `title(…)`. |
| `inputRows(key, g -> …)` | A repeatable row group: `g.title(…).required().column(col, c -> …)` with the same input builder per column. |
| `defaults(ctx -> CommandDefaults)` | Opening values computed per call (`CommandDefaults.ofValues(map)`, `.ofRows(group, rows)`); read-only. |
| `run(ctx -> CommandResult)` | Required. The handler is a method of your bean, so it uses whatever you injected. |

`RecordRow<E>` (the `row` of per-record functions) reads the resolved row without another query:
`row.enumValue(Order::getStatus, OrderStatus.class)`, `row.text(Order::getCustomer)` (display
label), `row.bool(...)`, `row.get(...)`, `row.id()`. Getter references are checked against `E`.

`CommandContext`: `id()`, `input(key)` (`""` when absent), `inputRows(key)` (list of
`column → value` maps; values are strings), `user()`, `subject()`. Typed repositories are not
record-scoped: when the handler reads or changes *other* records for the caller, check with
`RecordAccess` using `ctx.subject()`.

### Results and rejections

```java
return CommandResult.success("Order cancelled").andRefresh();   // data changed: refresh the surface
return CommandResult.reload();                                  // refresh, nothing to say
return CommandResult.ok();                                      // nothing observable
return CommandResult.info("124 records imported").title("Import completed").detail("3 rows skipped");
return CommandResult.warning("Two order lines need attention.");
return CommandResult.error("The carrier rejected the hand-off.").details(List.of("Check the address"));
```

Feedback is meaning only; the client picks a toast or dialog. An expected business refusal is an
exception, answered as HTTP 422; its field/form errors keep an open input dialog open beside the
inputs to fix:

```java
throw CommandRejectedException.builder()
        .title("Cannot cancel")
        .message("The order has already shipped.")
        .fieldError("reason", "Explain why the shipped order must be cancelled.")
        .build();
```

Use `andRefresh()` (or `reload()`) whenever the handler changed data the user is looking at.

## Checks: live form feedback

Advisory feedback while a record is edited, computed on the server from the unsaved values
(`POST /api/checks/{kind}/{name}/{key}`, a dry run). Rules that must hold on save stay in
`Validated`/`BeforeWriteHandler` (`onno-rules-lifecycle`).

```java
@Component
public class ScheduleEventChecks implements Checks<ScheduleEvent> {

    private final ScheduleEventRepository schedules;

    public ScheduleEventChecks(ScheduleEventRepository schedules) {
        this.schedules = schedules;
    }

    @Override
    public Class<ScheduleEvent> entity() {
        return ScheduleEvent.class;
    }

    @Override
    public void define(CheckSpec<ScheduleEvent> v) {
        v.check("schedule-conflicts", this::conflicts)
                .dependsOn(ScheduleEvent::getStartsAt, ScheduleEvent::getEndsAt)
                .andDependsOn(ScheduleEvent::getParticipants, ScheduleParticipant::getEmployee)
                .debounce(Duration.ofMillis(200));
    }

    private List<Feedback> conflicts(CheckContext context) {
        // context.values(): current values by Java field name (tabular sections as row lists);
        // context.id(): the record being edited, null for a new one.
        return List.of(Feedback.warning("startsAt", "Time overlaps with 2 scheduled events"));
    }
}
```

`dependsOn` names header getters, or a section getter + row getter for any row's column
(`"participants.employee"`); string paths are an escape hatch. `Feedback.error|warning|info(field,
message)` places the message beside a field (`""` or `Feedback.formError(message)` for the whole
record). Values arrive as JSON-ish objects: parse temporals defensively
(`LocalDateTime.parse(value.toString())`).

## PickerOptions: decorate one reference picker

```java
@Component
public class EmployeeAvailability implements PickerOptions<ScheduleEvent> {

    @Override
    public Class<ScheduleEvent> entity() {
        return ScheduleEvent.class;
    }

    @Override
    public String field() {
        return "participants.employee";      // a top-level field, or section.field
    }

    @Override
    public Map<UUID, PickerOption> decorate(PickerContext context, List<PickerCandidate> options) {
        Map<UUID, PickerOption> result = new LinkedHashMap<>();
        for (PickerCandidate option : options) {
            result.put(option.id(), PickerOption.badge("Available", PickerTone.SUCCESS));
        }
        return result;
    }
}
```

Called once per result page (search and the "Show all…" dialog) with the live form
(`context.formValues()`, `section()`, `rowValues()`, `recordId()`) and the capped candidates
(`id()`, `values()`). Return `PickerOption.badge(label, tone)`, `PickerOption.disabled(label, tone,
reason)` or `PickerOption.filteredOut()`; no entry leaves a candidate undecorated. Tones:
`NEUTRAL`, `SUCCESS`, `WARNING`, `DANGER`. One bean per field — a second fails startup. Read
everything you need in one query for the whole page, not per candidate.

## IdentityLink: which record a login is

```java
@Configuration
public class IdentityConfiguration {

    @Bean
    IdentityLink identity() {
        return IdentityLink.of(Employee.class, "email");   // login matched against Employee.email
    }
}
```

With it the framework knows "the current person": task inbox routing and delegation pickers,
comment authors and avatars, notifications, and `Subject.recordId()` in record access policies.
Declare one.

## MCP exposure

`onno-mcp-starter` publishes every **static** command as a tool named `<entity>_<key>` (characters
outside `[A-Za-z0-9_.-]` become `_`, max 64, de-duplicated). Its description is the command's
`description` (else the title); its input schema has one property per input (number, ISO date,
enum of options, record UUID by type), an array per row group, and a required `id` for record
commands. Calls run as the authenticated user through the same checks. `dynamic(...)` commands are
not published (they change at runtime). Tools exist only while `onno.mcp.writes-enabled` is on.

So: give every command an agent-readable `description`, a fixed `title`, typed inputs (`number()`,
`date()`, `oneOf`, `reference`) rather than free text, and make rejections explicit.

## Rules

- A command is behaviour, not a button. Never add a bespoke controller for something a command
  can do.
- Keep `availableWhen`/`title(row -> …)` cheap and pure — they run per listed row.
- Do the work through repositories/services and return `andRefresh()`; posting documents goes
  through the posting service, not by flipping `posted`.
- Inputs arrive as strings (`ctx.input`); parse numbers/dates/ids defensively.
- Presentation (placement, icon, inline row button, primary button) is React:
  `commands: { cancel: { icon: "ban" }, advance: { placement: "row", icon: "arrow-right" } }`.

Read [references/examples.md](references/examples.md) for the example app's complete
`OrderCommands`.
