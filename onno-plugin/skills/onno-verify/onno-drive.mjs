#!/usr/bin/env node
// onno-drive — a dependency-free runtime-verification driver for a running onno app.
//
// It owns the session dance (JSON login → JSESSIONID cookie → CSRF token echoed on mutations),
// prints every response as JSON, exits non-zero with the server's message on HTTP errors, and
// derives a "feature map" (stable names for every entity, command, register, process and setting)
// from the authenticated UI model endpoint GET /api/ui/model.
//
// Node >= 22 (global fetch, Headers#getSetCookie). Run `node onno-drive.mjs help`.

import { mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { pathToFileURL } from "node:url";

const DEFAULT_BASE = "http://localhost:8091";
const STATE_FILE = ".onno-drive/session.json";

const USAGE = `onno-drive — drive a running onno app from the command line

Usage: node onno-drive.mjs <command> [args] [--options]

Session
  login --base URL --user U --password P   JSON login; stores cookies + CSRF in the state file
  login --base URL --token JWT             resource-server mode: store a bearer token instead
  logout                                   POST /api/auth/logout and delete the state file
  me                                       GET /api/auth/me

Feature map
  features [--json] [--no-commands]        every user-facing feature with its stable name

Catalogs and documents (<kind> is catalogs|documents, <name> the model name, e.g. orders)
  list <kind> <name> [--where JSON] [--limit N] [--cursor C] [--q TEXT] [--sort F] [--dir asc|desc] [--all]
  get <kind> <name> <id>
  create <kind> <name> --data JSON|@file
  update <kind> <name> <id> --data JSON|@file
  delete <kind> <name> <id>
  validate <kind> <name> [<id>] --data JSON|@file     dry-run the write lifecycle, nothing persists
  post <name> <id>  |  unpost <name> <id>              documents only
  preview-posting <name> <id>                          movements a post would write (dry run)

Commands (Commands<E> beans)
  commands <kind> <name> [--id ID]                     list commands (with record state when --id)
  command <kind> <name> <key> [--id ID] [--inputs JSON] [--ids ID,ID]   run one (batch with --ids)

Registers
  balance <register> [--where JSON] [--as-of DATETIME] [--limit N] [--all]
  movements <register> [--from D] [--to D] [--where JSON] [--limit N] [--all]
  turnover <register> --from D --to D [--where JSON]
  info-register <name> [--from D] [--to D] [--where JSON]

Processes
  tasks                                    the caller's task inbox (GET /api/tasks)

Escape hatch
  raw <METHOD> <path> [--data JSON|@file]  any /api path with the session (CSRF added on mutations)

Global options
  --base URL      override the stored base URL (env ONNO_BASE, default ${DEFAULT_BASE})
  --state FILE    state file (env ONNO_DRIVE_STATE, default ./${STATE_FILE})
  --compact       one-line JSON
  --trace         keep the server's Java stack trace in error bodies (dropped by default)

Exit codes: 0 ok, 1 HTTP/server error (message on stderr, body on stdout), 2 usage error.`;

/** A failure carrying an exit code; HTTP failures also carry the parsed response body. */
export class DriveError extends Error {
  constructor(message, { code = 1, status, body } = {}) {
    super(message);
    this.code = code;
    this.status = status;
    this.body = body;
  }
}

const usage = (message) => new DriveError(message, { code: 2 });

// ----- argv -----

const BOOLEAN_FLAGS = new Set(["json", "all", "compact", "no-commands", "help", "trace"]);

/** Splits argv into positionals and `--key value` / `--flag` options. */
export function parseArgs(argv) {
  const positionals = [];
  const options = {};
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg.startsWith("--")) {
      const eq = arg.indexOf("=");
      if (eq > 0) {
        options[arg.slice(2, eq)] = arg.slice(eq + 1);
        continue;
      }
      const key = arg.slice(2);
      if (BOOLEAN_FLAGS.has(key)) {
        options[key] = true;
      } else {
        if (i + 1 >= argv.length) throw usage(`--${key} needs a value`);
        options[key] = argv[++i];
      }
    } else {
      positionals.push(arg);
    }
  }
  return { positionals, options };
}

// ----- state -----

function statePath(options, env, cwd) {
  return resolve(cwd, options.state ?? env.ONNO_DRIVE_STATE ?? STATE_FILE);
}

async function loadState(path) {
  try {
    return JSON.parse(await readFile(path, "utf8"));
  } catch (e) {
    if (e.code === "ENOENT") return null;
    throw new DriveError(`Cannot read state file ${path}: ${e.message}`);
  }
}

async function saveState(path, state) {
  await mkdir(dirname(path), { recursive: true });
  await writeFile(path, JSON.stringify(state, null, 2) + "\n", { mode: 0o600 });
}

// ----- HTTP -----

/** A session-bound HTTP client: carries cookies, echoes CSRF on mutations, decodes errors. */
export class Client {
  constructor(state, fetchImpl = globalThis.fetch) {
    this.state = state;
    this.fetch = fetchImpl;
    this.dirty = false;
  }

  get base() {
    return this.state.base.replace(/\/+$/, "");
  }

  cookieHeader() {
    return Object.entries(this.state.cookies ?? {})
      .map(([k, v]) => `${k}=${v}`)
      .join("; ");
  }

  absorbCookies(res) {
    const lines = typeof res.headers.getSetCookie === "function" ? res.headers.getSetCookie() : [];
    for (const line of lines) {
      const [pair, ...attrs] = line.split(";");
      const eq = pair.indexOf("=");
      if (eq < 0) continue;
      const name = pair.slice(0, eq).trim();
      const value = pair.slice(eq + 1).trim();
      const expired = attrs.some((a) => /^\s*max-age\s*=\s*0\s*$/i.test(a));
      this.state.cookies ??= {};
      if (expired || value === "") delete this.state.cookies[name];
      else this.state.cookies[name] = value;
      this.dirty = true;
    }
  }

  /** Fetches the CSRF token (cookie modes); null in resource-server mode. */
  async csrf() {
    const body = await this.request("GET", "/api/auth/csrf");
    this.state.csrf = body && body.token ? { token: body.token, header: body.headerName || "X-XSRF-TOKEN" } : null;
    this.dirty = true;
    return this.state.csrf;
  }

  async request(method, path, { body, query, csrf } = {}) {
    const url = new URL(this.base + path);
    for (const [k, v] of Object.entries(query ?? {})) {
      if (v === undefined || v === null || v === false) continue;
      for (const one of Array.isArray(v) ? v : [v]) url.searchParams.append(k, String(one));
    }
    const headers = { Accept: "application/json" };
    const cookie = this.cookieHeader();
    if (cookie) headers.Cookie = cookie;
    if (this.state.token) headers.Authorization = `Bearer ${this.state.token}`;
    const mutating = !["GET", "HEAD"].includes(method);
    if (mutating && csrf !== false && !this.state.token) {
      // Refresh per mutation: the token is bound to the session and rotates on login.
      const token = await this.csrf();
      if (token) headers[token.header] = token.token;
      const again = this.cookieHeader();
      if (again) headers.Cookie = again;
    }
    let payload;
    if (body !== undefined) {
      headers["Content-Type"] = "application/json";
      payload = JSON.stringify(body);
    }
    let res;
    try {
      res = await this.fetch(url, { method, headers, body: payload, redirect: "manual" });
    } catch (e) {
      throw new DriveError(`${method} ${url.pathname}: cannot reach ${this.base} (${e.cause?.code ?? e.message})`);
    }
    this.absorbCookies(res);
    const text = await res.text();
    const type = res.headers.get("content-type") ?? "";
    let parsed = text;
    if (text && type.includes("json")) {
      try {
        parsed = JSON.parse(text);
      } catch {
        parsed = text;
      }
    } else if (!text) {
      parsed = null;
    }
    if (res.status >= 300) {
      throw new DriveError(`${method} ${url.pathname}${url.search} → HTTP ${res.status}: ${errorMessage(res, parsed)}`, {
        status: res.status,
        body: parsed,
      });
    }
    if (type.includes("text/html") && path.startsWith("/api/")) {
      // The SPA fallback is navigation-only, but a proxy or a mistyped base can still answer HTML.
      throw new DriveError(`${method} ${url.pathname} answered HTML, not JSON — check --base and the path`, {
        status: res.status,
      });
    }
    return parsed;
  }
}

function errorMessage(res, body) {
  if (body && typeof body === "object") {
    const parts = [body.message, body.error, body.detail, body.title].filter((s) => typeof s === "string" && s);
    let msg = parts[0] ?? res.statusText ?? "error";
    if (body.fieldErrors && Object.keys(body.fieldErrors).length) msg += ` ${JSON.stringify(body.fieldErrors)}`;
    if (Array.isArray(body.formErrors) && body.formErrors.length) msg += ` ${JSON.stringify(body.formErrors)}`;
    return msg;
  }
  if (typeof body === "string" && body.trim()) return body.trim().slice(0, 300);
  if (res.status === 401) return "unauthenticated — run `onno-drive login` first";
  return res.statusText || "error";
}

// ----- helpers -----

const enc = encodeURIComponent;

function kindOf(raw) {
  const k = String(raw ?? "").toLowerCase();
  if (["catalog", "catalogs"].includes(k)) return "catalogs";
  if (["document", "documents"].includes(k)) return "documents";
  throw usage(`<kind> must be catalogs or documents, got "${raw ?? ""}"`);
}

function need(positionals, count, shape) {
  if (positionals.length < count) throw usage(`usage: ${shape}`);
  return positionals;
}

async function jsonOption(raw, name, cwd) {
  if (raw === undefined) return undefined;
  let text = String(raw);
  if (text.startsWith("@")) text = await readFile(resolve(cwd, text.slice(1)), "utf8");
  try {
    return JSON.parse(text);
  } catch (e) {
    throw usage(`--${name} is not valid JSON: ${e.message}`);
  }
}

async function whereOption(options, cwd) {
  const where = await jsonOption(options.where, "where", cwd);
  return where === undefined ? undefined : JSON.stringify(where);
}

/** Follows nextCursor until hasMore is false (with --all), else returns the first window. */
async function windowed(client, path, query, all) {
  const first = await client.request("GET", path, { query });
  if (!all || !first || !Array.isArray(first.rows)) return first;
  const rows = [...first.rows];
  let page = first;
  while (page.hasMore && page.nextCursor) {
    page = await client.request("GET", path, { query: { ...query, cursor: page.nextCursor } });
    rows.push(...(page.rows ?? []));
  }
  return { rows, nextCursor: null, hasMore: false };
}

// ----- feature map -----

/**
 * Builds the feature map from the authenticated UI model. Feature ids are route-shaped
 * (`documents/orders`, `documents/orders#cancel`, `registers/book_stock`) so humans, agents
 * and PR descriptions share one vocabulary.
 */
export async function featureMap(client, { commands = true } = {}) {
  const model = await client.request("GET", "/api/ui/model");
  const branding = await client.request("GET", "/api/branding").catch(() => null);
  const optional = async (path) => {
    try {
      return { available: true, value: await client.request("GET", path) };
    } catch (e) {
      if (e instanceof DriveError && [401, 403, 404, 405].includes(e.status)) {
        return { available: false, status: e.status };
      }
      throw e;
    }
  };

  const entities = [];
  for (const e of model.entities ?? []) {
    const id = `${e.kind}/${e.name}`;
    const flags = [];
    if (e.canWrite) flags.push("write");
    if (e.postable) flags.push(e.canPost ? "post" : "postable");
    if (e.hierarchical) flags.push("hierarchical");
    if (e.history) flags.push("history");
    if (e.kind === "registers") flags.push(e.balance ? "balance" : "turnover");
    const entry = {
      id,
      kind: e.kind,
      name: e.name,
      logicalName: e.logicalName,
      title: e.title,
      context: e.context || "",
      route: e.route,
      flags,
      api: apiFor(e),
    };
    if (commands && (e.kind === "catalogs" || e.kind === "documents") && e.canWrite) {
      const listing = await optional(`/api/commands/${e.kind}/${enc(e.name)}`);
      entry.commands = listing.available
        ? (listing.value?.commands ?? []).map((c) => ({
            id: `${id}#${c.key}`,
            key: c.key,
            title: c.title,
            target: c.target,
            ...(c.group ? { group: c.group } : {}),
            ...(c.form ? { inputs: c.form.map((f) => f.key ?? f.name).filter(Boolean) } : {}),
          }))
        : [];
    }
    entities.push(entry);
  }

  const sections = [];
  for (const e of entities) {
    const name = e.context || "General";
    let section = sections.find((s) => s.name === name);
    if (!section) sections.push((section = { name, items: [] }));
    section.items.push(e.id);
  }

  const processes = await optional("/api/process-definitions");
  const tasks = await optional("/api/tasks?limit=1");

  return {
    app: branding?.appName ?? null,
    base: client.base,
    user: model.user ?? null,
    features: model.features ?? {},
    sections,
    entities,
    informationRegisters: (model.informationRegisters ?? []).map((r) => ({
      id: `information-registers/${r.logicalName}`,
      logicalName: r.logicalName,
      title: r.title,
      context: r.context || "",
      periodic: !!r.periodic,
      api: `/api/information-registers/${enc(r.logicalName)}/records`,
    })),
    constants: (model.constants ?? []).map((c) => ({ id: `constants/${c.name}`, name: c.name, type: c.type })),
    processes: processes.available
      ? (processes.value ?? []).map((p) => ({ id: `processes/${p.key}`, key: p.key, title: p.title, version: p.version }))
      : null,
    taskInbox: tasks.available ? { api: "/api/tasks", hasTasks: (tasks.value?.rows ?? []).length > 0 } : null,
  };
}

function apiFor(e) {
  if (e.kind === "registers") {
    return e.balance
      ? { balance: `/api/registers/${enc(e.name)}/balance`, movements: `/api/registers/${enc(e.name)}/movements` }
      : { turnover: `/api/registers/${enc(e.name)}/turnover`, movements: `/api/registers/${enc(e.name)}/movements` };
  }
  return { list: `/api/list/${e.kind}/${enc(e.name)}`, record: `/api/${e.kind}/${enc(e.name)}/{id}` };
}

/** Renders the feature map as an indented tree. */
export function renderFeatures(map) {
  const out = [];
  const on = (v) => (v ? "on" : "off");
  out.push(`${map.app ?? "onno app"} @ ${map.base}`);
  if (map.user) out.push(`user: ${map.user.username} [${(map.user.roles ?? []).join(", ")}]`);
  const feats = Object.entries(map.features ?? {}).map(([k, v]) => `${k}=${on(v)}`);
  if (feats.length) out.push(`features: ${feats.join(" ")}`);
  out.push("");
  const byId = new Map(map.entities.map((e) => [e.id, e]));
  for (const section of map.sections) {
    out.push(`${section.name}`);
    for (const id of section.items) {
      const e = byId.get(id);
      const flags = e.flags.length ? `  [${e.flags.join(", ")}]` : "";
      out.push(`  ${e.id}  "${e.title}"${flags}`);
      for (const c of e.commands ?? []) {
        const extra = [c.target, c.group && `group: ${c.group}`, c.inputs?.length && `inputs: ${c.inputs.join(", ")}`]
          .filter(Boolean)
          .join("; ");
        out.push(`    #${c.key}  "${c.title}"${extra ? `  (${extra})` : ""}`);
      }
    }
    out.push("");
  }
  if (map.informationRegisters.length) {
    out.push("Information registers");
    for (const r of map.informationRegisters) out.push(`  ${r.id}  "${r.title}"${r.periodic ? "  [periodic]" : ""}`);
    out.push("");
  }
  if (map.constants.length) {
    out.push("Settings (constants)");
    for (const c of map.constants) out.push(`  ${c.id}  ${c.type}`);
    out.push("");
  }
  if (map.processes) {
    out.push("Processes");
    if (!map.processes.length) out.push("  (none defined)");
    for (const p of map.processes) out.push(`  ${p.id}  "${p.title ?? p.key}"  v${p.version}`);
    out.push("");
  }
  if (map.taskInbox) out.push(`Task inbox: ${map.taskInbox.api}${map.taskInbox.hasTasks ? "  (has open tasks)" : ""}`);
  return out.join("\n").replace(/\n+$/, "") + "\n";
}

// ----- commands -----

async function sessionClient(ctx) {
  const state = await loadState(ctx.statePath);
  const base = ctx.options.base ?? state?.base ?? ctx.env.ONNO_BASE;
  if (!state && !base) throw usage("no session — run `onno-drive login --base URL --user U --password P` first");
  return new Client({ ...(state ?? { cookies: {} }), base: base ?? DEFAULT_BASE }, ctx.fetch);
}

const COMMANDS = {
  async login(ctx) {
    const { options } = ctx;
    const base = options.base ?? ctx.env.ONNO_BASE ?? DEFAULT_BASE;
    const client = new Client({ base, cookies: {} }, ctx.fetch);
    if (options.token ?? ctx.env.ONNO_TOKEN) {
      client.state.token = options.token ?? ctx.env.ONNO_TOKEN;
      const me = await client.request("GET", "/api/auth/me");
      if (!me?.authenticated) throw new DriveError("bearer token was not accepted (GET /api/auth/me is anonymous)");
      await saveState(ctx.statePath, client.state);
      return me;
    }
    const user = options.user ?? ctx.env.ONNO_USER;
    const password = options.password ?? ctx.env.ONNO_PASSWORD;
    if (!user || password === undefined) throw usage("usage: login --base URL --user U --password P (or ONNO_USER / ONNO_PASSWORD)");
    await client.csrf().catch(() => null); // seeds the XSRF cookie; login itself is CSRF-exempt
    const me = await client.request("POST", "/api/auth/login", {
      body: { username: user, password, remember: false },
      csrf: false,
    });
    if (!me?.authenticated) throw new DriveError("login answered but the session is not authenticated", { body: me });
    await client.csrf();
    await saveState(ctx.statePath, client.state);
    return me;
  },

  async logout(ctx) {
    const client = await sessionClient(ctx);
    let result = null;
    try {
      result = await client.request("POST", "/api/auth/logout");
    } finally {
      await rm(ctx.statePath, { force: true });
    }
    return result ?? { loggedOut: true };
  },

  async me(ctx) {
    return (await sessionClient(ctx)).request("GET", "/api/auth/me");
  },

  async features(ctx) {
    const client = await sessionClient(ctx);
    const map = await featureMap(client, { commands: !ctx.options["no-commands"] });
    ctx.client = client;
    if (ctx.options.json) return map;
    ctx.stdout(renderFeatures(map));
    return undefined;
  },

  async list(ctx) {
    const [kind, name] = need(ctx.positionals, 2, "list <kind> <name> [--where JSON] [--limit N]");
    const client = await sessionClient(ctx);
    ctx.client = client;
    const o = ctx.options;
    return windowed(client, `/api/list/${kindOf(kind)}/${enc(name)}`, {
      limit: o.limit,
      cursor: o.cursor,
      q: o.q,
      sort: o.sort,
      dir: o.dir,
      where: await whereOption(o, ctx.cwd),
    }, o.all);
  },

  async get(ctx) {
    const [kind, name, id] = need(ctx.positionals, 3, "get <kind> <name> <id>");
    return (ctx.client = await sessionClient(ctx)).request("GET", `/api/${kindOf(kind)}/${enc(name)}/${enc(id)}`);
  },

  async create(ctx) {
    const [kind, name] = need(ctx.positionals, 2, "create <kind> <name> --data JSON");
    const data = await jsonOption(ctx.options.data, "data", ctx.cwd);
    if (data === undefined) throw usage("create needs --data JSON|@file");
    return (ctx.client = await sessionClient(ctx)).request("POST", `/api/${kindOf(kind)}/${enc(name)}`, { body: data });
  },

  async update(ctx) {
    const [kind, name, id] = need(ctx.positionals, 3, "update <kind> <name> <id> --data JSON");
    const data = await jsonOption(ctx.options.data, "data", ctx.cwd);
    if (data === undefined) throw usage("update needs --data JSON|@file");
    return (ctx.client = await sessionClient(ctx)).request("PUT", `/api/${kindOf(kind)}/${enc(name)}/${enc(id)}`, { body: data });
  },

  async delete(ctx) {
    const [kind, name, id] = need(ctx.positionals, 3, "delete <kind> <name> <id>");
    await (ctx.client = await sessionClient(ctx)).request("DELETE", `/api/${kindOf(kind)}/${enc(name)}/${enc(id)}`);
    return { deleted: id };
  },

  async validate(ctx) {
    const [kind, name, id] = need(ctx.positionals, 2, "validate <kind> <name> [<id>] --data JSON");
    const data = (await jsonOption(ctx.options.data, "data", ctx.cwd)) ?? {};
    const path = `/api/${kindOf(kind)}/${enc(name)}${id ? `/${enc(id)}` : ""}/validate`;
    return (ctx.client = await sessionClient(ctx)).request("POST", path, { body: data });
  },

  async post(ctx) {
    const [name, id] = need(ctx.positionals, 2, "post <document> <id>");
    return (ctx.client = await sessionClient(ctx)).request("POST", `/api/documents/${enc(name)}/${enc(id)}/post`);
  },

  async unpost(ctx) {
    const [name, id] = need(ctx.positionals, 2, "unpost <document> <id>");
    return (ctx.client = await sessionClient(ctx)).request("POST", `/api/documents/${enc(name)}/${enc(id)}/unpost`);
  },

  async "preview-posting"(ctx) {
    const [name, id] = need(ctx.positionals, 2, "preview-posting <document> <id>");
    return (ctx.client = await sessionClient(ctx)).request("GET", `/api/documents/${enc(name)}/${enc(id)}/posting-preview`);
  },

  async commands(ctx) {
    const [kind, name] = need(ctx.positionals, 2, "commands <kind> <name> [--id ID]");
    return (ctx.client = await sessionClient(ctx)).request("GET", `/api/commands/${kindOf(kind)}/${enc(name)}`, {
      query: { id: ctx.options.id },
    });
  },

  async command(ctx) {
    const [kind, name, key] = need(ctx.positionals, 3, "command <kind> <name> <key> [--id ID] [--inputs JSON] [--ids A,B]");
    const inputs = (await jsonOption(ctx.options.inputs, "inputs", ctx.cwd)) ?? {};
    const client = (ctx.client = await sessionClient(ctx));
    const base = `/api/commands/${kindOf(kind)}/${enc(name)}/${enc(key)}`;
    if (ctx.options.ids) {
      const ids = String(ctx.options.ids).split(",").map((s) => s.trim()).filter(Boolean);
      return client.request("POST", `${base}/batch`, { body: { ids, inputs } });
    }
    return client.request("POST", base, { query: { id: ctx.options.id }, body: { inputs } });
  },

  async balance(ctx) {
    const [name] = need(ctx.positionals, 1, "balance <register> [--where JSON] [--as-of T]");
    const client = (ctx.client = await sessionClient(ctx));
    const o = ctx.options;
    return windowed(client, `/api/registers/${enc(name)}/balance`, {
      asOf: o["as-of"],
      where: await whereOption(o, ctx.cwd),
      limit: o.limit,
      cursor: o.cursor,
    }, o.all);
  },

  async movements(ctx) {
    const [name] = need(ctx.positionals, 1, "movements <register> [--from D] [--to D]");
    const client = (ctx.client = await sessionClient(ctx));
    const o = ctx.options;
    return windowed(client, `/api/registers/${enc(name)}/movements`, {
      from: o.from,
      to: o.to,
      where: await whereOption(o, ctx.cwd),
      limit: o.limit,
      cursor: o.cursor,
    }, o.all);
  },

  async turnover(ctx) {
    const [name] = need(ctx.positionals, 1, "turnover <register> --from D --to D");
    const o = ctx.options;
    if (!o.from || !o.to) throw usage("turnover needs --from and --to");
    const client = (ctx.client = await sessionClient(ctx));
    return windowed(client, `/api/registers/${enc(name)}/turnover`, {
      from: o.from,
      to: o.to,
      where: await whereOption(o, ctx.cwd),
      limit: o.limit,
      cursor: o.cursor,
    }, o.all);
  },

  async "info-register"(ctx) {
    const [name] = need(ctx.positionals, 1, "info-register <name> [--from D] [--to D]");
    const client = (ctx.client = await sessionClient(ctx));
    const o = ctx.options;
    return windowed(client, `/api/information-registers/${enc(name)}/records`, {
      from: o.from,
      to: o.to,
      where: await whereOption(o, ctx.cwd),
      limit: o.limit,
      cursor: o.cursor,
    }, o.all);
  },

  async tasks(ctx) {
    const client = (ctx.client = await sessionClient(ctx));
    return windowed(client, "/api/tasks", { limit: ctx.options.limit, cursor: ctx.options.cursor }, ctx.options.all);
  },

  async raw(ctx) {
    const [method, path] = need(ctx.positionals, 2, "raw <METHOD> <path> [--data JSON]");
    if (!path.startsWith("/")) throw usage("raw <path> must start with /");
    const [pathname, search = ""] = path.split("?");
    const query = Object.fromEntries(new URLSearchParams(search));
    const body = await jsonOption(ctx.options.data, "data", ctx.cwd);
    return (ctx.client = await sessionClient(ctx)).request(method.toUpperCase(), pathname, { query, body });
  },
};

/**
 * Runs one CLI invocation. Returns the exit code; all I/O goes through the injected streams so
 * tests can drive it in-process.
 */
export async function run(argv, io = {}) {
  const stdout = io.stdout ?? ((s) => process.stdout.write(s));
  const stderr = io.stderr ?? ((s) => process.stderr.write(s));
  const env = io.env ?? process.env;
  const cwd = io.cwd ?? process.cwd();
  let parsed;
  try {
    parsed = parseArgs(argv);
  } catch (e) {
    stderr(`onno-drive: ${e.message}\n`);
    return 2;
  }
  const [command, ...positionals] = parsed.positionals;
  const options = parsed.options;
  if (!command || command === "help" || options.help) {
    stdout(USAGE + "\n");
    return command || options.help ? 0 : 2;
  }
  const handler = COMMANDS[command];
  if (!handler) {
    stderr(`onno-drive: unknown command "${command}" (run \`onno-drive help\`)\n`);
    return 2;
  }
  const print = (value) => stdout(JSON.stringify(value, null, options.compact ? 0 : 2) + "\n");
  const ctx = { positionals, options, env, cwd, stdout, fetch: io.fetch, statePath: statePath(options, env, cwd) };
  try {
    const result = await handler(ctx);
    if (result !== undefined) print(result);
    return 0;
  } catch (e) {
    if (!(e instanceof DriveError)) throw e;
    if (e.body !== undefined && e.body !== null) {
      // Apps with server.error.include-stacktrace=always attach a Java trace; keep it opt-in.
      const body = e.body && typeof e.body === "object" && !options.trace ? { ...e.body, trace: undefined } : e.body;
      print(body);
    }
    stderr(`onno-drive: ${e.message}\n`);
    return e.code;
  } finally {
    // Persist rotated cookies (session, XSRF) so the next invocation reuses them.
    if (ctx.client?.dirty && command !== "logout") {
      const prior = await loadState(ctx.statePath).catch(() => null);
      if (prior) await saveState(ctx.statePath, { ...prior, cookies: ctx.client.state.cookies, csrf: ctx.client.state.csrf });
    }
  }
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  process.exitCode = await run(process.argv.slice(2));
}
