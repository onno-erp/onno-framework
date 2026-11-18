// node --test onno-plugin/skills/onno-verify/
// Drives onno-drive in-process against a tiny fake onno server that mimics session login,
// cookie-bound CSRF, the UI model, commands and register reads.
import { after, before, beforeEach, describe, test } from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { mkdtemp, readFile, rm, stat } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { parseArgs, run } from "./onno-drive.mjs";

const SESSION = "sess-123";
const CSRF = "csrf-abc";

function fakeOnno() {
  const log = [];
  const server = createServer(async (req, res) => {
    let raw = "";
    for await (const chunk of req) raw += chunk;
    const url = new URL(req.url, "http://x");
    const cookies = Object.fromEntries(
      (req.headers.cookie ?? "").split(";").filter(Boolean).map((c) => c.trim().split("=")),
    );
    const authed = cookies.JSESSIONID === SESSION;
    log.push({ method: req.method, path: url.pathname, query: url.search, headers: req.headers, body: raw });
    const json = (status, body, headers = {}) => {
      res.writeHead(status, { "Content-Type": "application/json", ...headers });
      res.end(JSON.stringify(body));
    };
    const error = (status, message) =>
      json(status, { timestamp: "now", status, error: "Error", trace: "java.lang.RuntimeException\n\tat ...", message, path: url.pathname });
    const p = url.pathname;

    if (p === "/api/auth/csrf") {
      return json(200, { token: CSRF, headerName: "X-XSRF-TOKEN", parameterName: "_csrf" }, {
        "Set-Cookie": `XSRF-TOKEN=${CSRF}; Path=/`,
      });
    }
    if (p === "/api/auth/login" && req.method === "POST") {
      const { username, password } = JSON.parse(raw);
      if (username === "admin" && password === "admin") {
        return json(200, { authenticated: true, username, roles: ["ADMIN"], mode: "in-memory" }, {
          "Set-Cookie": `JSESSIONID=${SESSION}; Path=/; HttpOnly`,
        });
      }
      res.writeHead(401);
      return res.end();
    }
    if (p === "/api/branding") return json(200, { appName: "Fake Books" });
    if (!authed) return error(401, "Unauthorized");
    if (req.method !== "GET" && req.headers["x-xsrf-token"] !== CSRF) return error(403, "Invalid CSRF token");

    if (p === "/api/auth/me") return json(200, { authenticated: true, username: "admin", roles: ["ADMIN"] });
    if (p === "/api/auth/logout") {
      res.writeHead(204, { "Set-Cookie": "JSESSIONID=; Path=/; Max-Age=0" });
      return res.end();
    }
    if (p === "/api/ui/model") {
      return json(200, {
        user: { username: "admin", displayName: "Admin", roles: ["ADMIN"] },
        features: { comments: true, notifications: true, periodClose: false },
        entities: [
          { kind: "catalogs", name: "books", logicalName: "Books", title: "Books", context: "Catalog", route: "/catalogs/books", canWrite: true, hierarchical: false, history: false },
          { kind: "documents", name: "orders", logicalName: "Orders", title: "Orders", context: "Sales", route: "/documents/orders", canWrite: true, postable: true, canPost: true, history: true },
          { kind: "registers", name: "book_stock", logicalName: "Book Stock", title: "Book stock", context: "Inventory", route: "/registers/book_stock", canWrite: false, balance: true },
        ],
        informationRegisters: [{ logicalName: "Prices", title: "Price list", context: "Sales", periodic: true }],
        constants: [{ name: "CompanyName", type: "String" }],
      });
    }
    if (p === "/api/commands/catalogs/books") return json(200, { commands: [], state: {} });
    if (p === "/api/commands/documents/orders" && req.method === "GET") {
      return json(200, {
        commands: [
          { key: "cancel", title: "Cancel order", target: "record", form: [{ key: "reason", kind: "field" }] },
          { key: "bulkReceive", title: "Quick receive", target: "collection" },
        ],
        state: {},
      });
    }
    if (p === "/api/commands/documents/orders/cancel" && req.method === "POST") {
      const body = JSON.parse(raw);
      if (!body.inputs?.reason) return json(422, { message: "Reason is required" });
      return json(200, { refresh: true, feedback: { severity: "success", message: `Cancelled ${url.searchParams.get("id")}` } });
    }
    if (p === "/api/process-definitions") return error(404, "Not Found");
    if (p === "/api/tasks") return json(200, { rows: [], nextCursor: null, hasMore: false });
    if (p === "/api/list/documents/orders") {
      const cursor = url.searchParams.get("cursor");
      return cursor
        ? json(200, { rows: [{ id: "o2" }], nextCursor: null, hasMore: false })
        : json(200, { rows: [{ id: "o1" }], nextCursor: "c1", hasMore: true });
    }
    if (p === "/api/documents/orders/o1/posting-preview") {
      return json(200, { registers: [{ register: "Book Stock", movements: [{ quantity: -1 }] }] });
    }
    if (p === "/api/documents/orders/o1/post") return json(200, { id: "o1", posted: true });
    if (p === "/api/registers/Book%20Stock/balance") return json(200, { rows: [{ quantity: 5 }], nextCursor: null, hasMore: false });
    if (p === "/api/documents/orders/missing") return error(404, "Document not found: missing");
    return error(404, "No route");
  });
  return { server, log };
}

let fake;
let base;
let dir;

async function drive(...argv) {
  let stdout = "";
  let stderr = "";
  const code = await run([...argv, "--state", join(dir, "session.json")], {
    stdout: (s) => (stdout += s),
    stderr: (s) => (stderr += s),
    env: {},
    cwd: dir,
  });
  let json;
  try {
    json = JSON.parse(stdout);
  } catch {
    json = undefined;
  }
  return { code, stdout, stderr, json };
}

before(async () => {
  fake = fakeOnno();
  await new Promise((r) => fake.server.listen(0, "127.0.0.1", r));
  base = `http://127.0.0.1:${fake.server.address().port}`;
});

after(() => new Promise((r) => fake.server.close(r)));

beforeEach(async () => {
  if (dir) await rm(dir, { recursive: true, force: true });
  dir = await mkdtemp(join(tmpdir(), "onno-drive-"));
  fake.log.length = 0;
});

const login = () => drive("login", "--base", base, "--user", "admin", "--password", "admin");

describe("parseArgs", () => {
  test("splits positionals, valued options, boolean flags and --k=v", () => {
    const { positionals, options } = parseArgs(["list", "documents", "orders", "--limit", "5", "--all", "--where={\"a\":1}"]);
    assert.deepEqual(positionals, ["list", "documents", "orders"]);
    assert.deepEqual(options, { limit: "5", all: true, where: '{"a":1}' });
  });
});

describe("session", () => {
  test("login stores the session cookie and CSRF token in a private state file", async () => {
    const r = await login();
    assert.equal(r.code, 0, r.stderr);
    assert.equal(r.json.authenticated, true);
    const state = JSON.parse(await readFile(join(dir, "session.json"), "utf8"));
    assert.equal(state.base, base);
    assert.equal(state.cookies.JSESSIONID, SESSION);
    assert.equal(state.csrf.token, CSRF);
    assert.equal((await stat(join(dir, "session.json"))).mode & 0o777, 0o600);
  });

  test("bad credentials exit 1 without writing state", async () => {
    const r = await drive("login", "--base", base, "--user", "admin", "--password", "nope");
    assert.equal(r.code, 1);
    assert.match(r.stderr, /HTTP 401/);
    await assert.rejects(stat(join(dir, "session.json")));
  });

  test("commands without a session tell the agent to log in", async () => {
    const r = await drive("me");
    assert.equal(r.code, 2);
    assert.match(r.stderr, /login/);
  });

  test("logout clears the state file", async () => {
    await login();
    const r = await drive("logout");
    assert.equal(r.code, 0, r.stderr);
    await assert.rejects(stat(join(dir, "session.json")));
  });
});

describe("feature map", () => {
  test("renders sections by context with route-shaped ids and commands", async () => {
    await login();
    const r = await drive("features");
    assert.equal(r.code, 0, r.stderr);
    assert.match(r.stdout, /^Fake Books @ /);
    assert.match(r.stdout, /Sales\n {2}documents\/orders {2}"Orders" {2}\[write, post, history\]/);
    assert.match(r.stdout, /#cancel {2}"Cancel order" {2}\(record; inputs: reason\)/);
    assert.match(r.stdout, /registers\/book_stock {2}"Book stock" {2}\[balance\]/);
    assert.match(r.stdout, /information-registers\/Prices/);
    assert.match(r.stdout, /constants\/CompanyName {2}String/);
    assert.match(r.stdout, /Task inbox: \/api\/tasks/);
    assert.doesNotMatch(r.stdout, /Processes/, "an app without the process engine has no process section");
  });

  test("--json is machine-readable and stable", async () => {
    await login();
    const r = await drive("features", "--json");
    assert.equal(r.code, 0, r.stderr);
    assert.equal(r.json.app, "Fake Books");
    assert.deepEqual(r.json.sections.map((s) => s.name), ["Catalog", "Sales", "Inventory"]);
    const orders = r.json.entities.find((e) => e.id === "documents/orders");
    assert.deepEqual(orders.commands.map((c) => c.id), ["documents/orders#cancel", "documents/orders#bulkReceive"]);
    assert.equal(orders.api.list, "/api/list/documents/orders");
    assert.equal(r.json.processes, null);
  });

  test("--no-commands skips the per-entity command listing", async () => {
    await login();
    await drive("features", "--json", "--no-commands");
    assert.ok(!fake.log.some((l) => l.path.startsWith("/api/commands")));
  });
});

describe("REST wrappers", () => {
  test("list sends where as JSON and --all follows nextCursor", async () => {
    await login();
    const r = await drive("list", "documents", "orders", "--where", '{"status":{"in":["NEW"]}}', "--limit", "1", "--all");
    assert.equal(r.code, 0, r.stderr);
    assert.deepEqual(r.json.rows.map((x) => x.id), ["o1", "o2"]);
    const calls = fake.log.filter((l) => l.path === "/api/list/documents/orders");
    assert.equal(calls.length, 2);
    const q = new URLSearchParams(calls[0].query);
    assert.equal(q.get("where"), '{"status":{"in":["NEW"]}}');
    assert.equal(q.get("limit"), "1");
    assert.equal(new URLSearchParams(calls[1].query).get("cursor"), "c1");
  });

  test("mutations echo the CSRF token; reads do not need it", async () => {
    await login();
    const r = await drive("post", "orders", "o1");
    assert.equal(r.code, 0, r.stderr);
    assert.equal(r.json.posted, true);
    const postCall = fake.log.find((l) => l.path.endsWith("/post"));
    assert.equal(postCall.headers["x-xsrf-token"], CSRF);
    assert.match(postCall.headers.cookie, /JSESSIONID=sess-123/);
  });

  test("preview-posting returns the dry-run movements", async () => {
    await login();
    const r = await drive("preview-posting", "orders", "o1");
    assert.equal(r.code, 0, r.stderr);
    assert.equal(r.json.registers[0].register, "Book Stock");
  });

  test("command posts {inputs} with ?id=", async () => {
    await login();
    const r = await drive("command", "documents", "orders", "cancel", "--id", "o1", "--inputs", '{"reason":"dup"}');
    assert.equal(r.code, 0, r.stderr);
    assert.equal(r.json.feedback.message, "Cancelled o1");
    const call = fake.log.find((l) => l.path.endsWith("/cancel"));
    assert.deepEqual(JSON.parse(call.body), { inputs: { reason: "dup" } });
  });

  test("a rejected command exits 1 with the server's message and prints its body", async () => {
    await login();
    const r = await drive("command", "documents", "orders", "cancel", "--id", "o1");
    assert.equal(r.code, 1);
    assert.match(r.stderr, /HTTP 422: Reason is required/);
    assert.equal(r.json.message, "Reason is required");
  });

  test("HTTP 404 carries the Boot error message", async () => {
    await login();
    const r = await drive("get", "documents", "orders", "missing");
    assert.equal(r.code, 1);
    assert.match(r.stderr, /HTTP 404: Document not found: missing/);
    assert.equal(r.json.trace, undefined, "the Java stack trace is dropped unless --trace");
    const traced = await drive("get", "documents", "orders", "missing", "--trace");
    assert.match(traced.json.trace, /RuntimeException/);
  });

  test("register names with spaces are URL-encoded", async () => {
    await login();
    const r = await drive("balance", "Book Stock");
    assert.equal(r.code, 0, r.stderr);
    assert.equal(r.json.rows[0].quantity, 5);
  });

  test("usage errors exit 2", async () => {
    assert.equal((await drive("list", "widgets", "x")).code, 2);
    assert.equal((await drive("frobnicate")).code, 2);
    assert.equal((await drive("create", "catalogs", "books", "--data", "{oops")).code, 2);
  });

  test("an unreachable server exits 1 with a hint", async () => {
    const r = await drive("login", "--base", "http://127.0.0.1:9", "--user", "a", "--password", "b");
    assert.equal(r.code, 1);
    assert.match(r.stderr, /cannot reach/);
  });
});
