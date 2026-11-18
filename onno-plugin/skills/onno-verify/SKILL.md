---
name: onno-verify
description: >-
  Verify a change against a running onno-framework app with onno-drive, a dependency-free Node CLI
  bundled with this skill, instead of hand-written curl, cookie-jar and CSRF steps. Use when you
  need to confirm a change works in the real app: log in, print the feature map (every entity,
  command, register, process and setting by its route-shaped name), list/get/create/update records,
  run Commands, dry-run validation, preview or perform posting, read register balances, and attach
  the outputs to a PR as verification artifacts.
---

# onno Verify (onno-drive)

`onno-drive.mjs` lives next to this file. It needs Node 22+ and nothing else. It handles the
session for you: it logs in with JSON, stores the `JSESSIONID` and XSRF cookies, and sends a fresh
`X-XSRF-TOKEN` with every mutation. Every response is printed as JSON. An HTTP error exits with
code 1 and prints the server's message; a usage error exits with code 2.

```bash
D=onno-plugin/skills/onno-verify/onno-drive.mjs   # or ${CLAUDE_PLUGIN_ROOT}/skills/onno-verify/onno-drive.mjs
node $D help
```

## Workflow

1. **Start an isolated app.** Never point mutations at someone's shared dev database. For the
   example app, build `./gradlew :example:bootJar` and run it from a scratch directory. The H2 and
   media paths are relative (`./data/...`), so they land in that directory:
   `java -jar <repo>/example/build/libs/example-*.jar --server.port=18091`. Leave
   `example/data/bookstore` in the main checkout alone, and stop the server when you are done.
2. **Log in.** Run
   `node $D login --base http://localhost:18091 --user admin@onnobooks.local --password admin`.
   The session goes in `./.onno-drive/session.json` with mode 0600; this path is gitignored. You
   can also set `ONNO_BASE`, `ONNO_USER` and `ONNO_PASSWORD`, or pass `--token JWT` in
   resource-server mode.
3. **Run `features` first.** It reads `GET /api/ui/model` plus each writable entity's commands,
   the process definitions and the task inbox. It prints the app's vocabulary grouped by context,
   which is how the default navigation groups it:

   ```text
   Sales
     catalogs/customers  "Customers"  [write, history]
     documents/orders  "Orders"  [write, post, history]
       #cancel  "Cancel order"  (record; inputs: reason)
       #bulkReceive  "Quick receive"  (collection; inputs: supplier, note, lines)
     registers/book_sales  "Book sales"  [turnover]
   Inventory
     documents/stock_receipts  "Stock Receipts"  [write, post]
     registers/book_stock  "Book stock"  [balance]
   Processes
     processes/order-approval  "Order approval"  v1
   ```

   Use these ids (`documents/orders`, `documents/orders#cancel`, `registers/book_stock`) when you
   talk to the user and in PRs. `features --json` gives the same map with API paths for scripts.
   Authored React pages (`defineApp` pages and navigation) are client-side only and do not appear
   here. Read `src/main/ui/main.tsx` to find them.
4. **Drive the feature you changed.** Run reads before writes, and dry runs before mutations:

   ```bash
   node $D list documents orders --where '{"status":{"in":["CONFIRMED"]}}' --limit 5
   node $D get documents orders <id>                         # before
   node $D validate documents orders --data '{}'             # dry-run write lifecycle
   node $D preview-posting orders <id>                       # movements a post would write
   node $D command documents orders cancel --id <id> --inputs '{"reason":"test"}'
   node $D get documents orders <id>                         # after
   node $D balance "Book Stock" --limit 10
   node $D raw GET /api/ui/view/documents/orders             # anything else under /api
   ```

   In the `<name>` slot, use the model name from `features` (`orders`, `book_stock`) or the
   logical name (`"Book Stock"`). The server accepts either form.
5. **Check your negative paths too.** Examples: a command on a record where it is unavailable should
   answer 409; a missing required field should fail `validate`; a reader without write access
   should get 403. Treat exit code 1 plus the server message as the evidence.

## Verification artifacts for PRs

In the PR's "Verification artifacts" section, include:

- the feature ids you exercised, e.g. `documents/orders#cancel`;
- the exact `onno-drive` commands you ran;
- the JSON before and after a mutation, trimmed to the fields that changed;
- the `preview-posting` output for any change to posting or registers, and the `balance` or
  `movements` output before and after a real post;
- screenshots or a short video for UI changes, because `onno-drive` covers the API only.

Trim large payloads, but keep ids and the fields you changed. Never paste `session.json`.

## Commands

| Command | Endpoint |
| --- | --- |
| `login` / `logout` / `me` | `POST /api/auth/login`, `POST /api/auth/logout`, `GET /api/auth/me` (CSRF from `GET /api/auth/csrf`) |
| `features [--json] [--no-commands]` | `GET /api/ui/model`, `/api/branding`, `/api/commands/{kind}/{name}`, `/api/process-definitions`, `/api/tasks` |
| `list <kind> <name> [--where --limit --cursor --q --sort --dir --all]` | `GET /api/list/{kind}/{name}` (`--all` follows `nextCursor`) |
| `get` / `create` / `update` / `delete` | `GET/POST/PUT/DELETE /api/{kind}/{name}[/{id}]` |
| `validate <kind> <name> [<id>] --data` | `POST /api/{kind}/{name}[/{id}]/validate` |
| `post` / `unpost` / `preview-posting <name> <id>` | `POST /api/documents/{name}/{id}/post|unpost`, `GET …/posting-preview` |
| `commands <kind> <name> [--id]` | `GET /api/commands/{kind}/{name}` |
| `command <kind> <name> <key> [--id] [--inputs] [--ids]` | `POST /api/commands/{kind}/{name}/{key}[/batch]` with body `{inputs}` |
| `balance` / `movements` / `turnover <register>` | `GET /api/registers/{name}/balance|movements|turnover` |
| `info-register <name>` | `GET /api/information-registers/{name}/records` |
| `tasks` | `GET /api/tasks` |
| `raw <METHOD> <path> [--data]` | any path, with the session and CSRF applied |

`--data`, `--inputs` and `--where` take JSON or `@file.json`. `--compact` prints one-line JSON.
Error bodies leave out the server's Java `trace` unless you pass `--trace`.

## Notes

- If the app has demo auto-login enabled (the example sets `onno.auth.demo.auto-login-username`),
  anonymous requests act as that user. A 401 check therefore needs an instance with auto-login
  turned off.
- Commands require write access, so `features` lists commands only for entities you can write.
- Dynamic commands (`c.dynamic(...)`) appear with their live keys, for example
  `#assign-<employeeId>`.
- Tests: `node --test onno-plugin/skills/onno-verify/onno-drive.test.mjs`. They run against an in-process fake server
  and need no app or network.
