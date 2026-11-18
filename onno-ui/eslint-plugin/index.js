// The onno ESLint plugin: conventions of @onno/ui that the type checker cannot see, as lint errors
// whose message states the fix. Plain ESM with no imports, so `@onno/ui/eslint` adds nothing to an
// app's runtime bundle and needs only ESLint itself (an optional peer dependency).
//
//   // eslint.config.js of an app built with the su.onno.ui Gradle plugin
//   import tseslint from "typescript-eslint";
//   import onno from "@onno/ui/eslint";
//   export default tseslint.config(...tseslint.configs.recommended, onno.configs.app);

/** Subpaths of the @onno packages an app may import; everything else under them is private. */
const PUBLIC_SUBPATHS = ["@onno/ui/icons", "@onno/ui/vite", "@onno/ui/eslint"];

const docs = (description) => ({ description, url: "https://github.com/onno-erp/onno-framework/blob/main/onno-ui/README.md#linting" });

/** The string a Literal or a TemplateLiteral starts with, or null for any other expression. */
function leadingText(node) {
  if (!node) return null;
  if (node.type === "Literal" && typeof node.value === "string") return node.value;
  if (node.type === "TemplateLiteral") return node.quasis[0].value.cooked ?? node.quasis[0].value.raw;
  return null;
}

function propertyName(node) {
  if (!node || node.computed) return null;
  if (node.key.type === "Identifier") return node.key.name;
  if (node.key.type === "Literal") return String(node.key.value);
  return null;
}

/** `truck` → `Truck`, `file-text` → `FileText`, `arrow_right` → `ArrowRight`. */
function pascal(name) {
  return name.split(/[^A-Za-z0-9]+/).filter(Boolean).map((part) => part[0].toUpperCase() + part.slice(1)).join("");
}

const noPrivateImports = {
  meta: {
    type: "problem",
    docs: docs("Import @onno packages through their public entry points, never their private source files."),
    schema: [{
      type: "object",
      properties: {
        allow: { type: "array", items: { type: "string" } },
        lucide: { type: "boolean" },
      },
      additionalProperties: false,
    }],
    messages: {
      privatePath: "`{{source}}` is a private file of `{{pkg}}`, not an extension contract. Import from `{{pkg}}`{{icons}}. If what you need is not exported, it is a framework change (export it deliberately), not a deep import.",
      sourceTree: "`{{source}}` reaches into the @onno/ui source tree. Import from `@onno/ui` or `@onno/ui/icons` instead.",
      lucide: "Import icons from `@onno/ui/icons` (it re-exports every Lucide icon), not `lucide-react`: the app then uses the framework's Lucide version and needs no dependency of its own.",
    },
  },
  create(context) {
    const options = context.options[0] ?? {};
    const allowed = new Set([...PUBLIC_SUBPATHS, ...(options.allow ?? [])]);
    const lucide = options.lucide ?? true;
    function check(sourceNode) {
      const source = leadingText(sourceNode);
      if (source === null || (sourceNode.type === "TemplateLiteral" && sourceNode.expressions.length)) return;
      const scoped = /^(@onno\/[^/]+)\/.+/.exec(source);
      if (scoped && !allowed.has(source)) {
        context.report({ node: sourceNode, messageId: "privatePath", data: {
          source, pkg: scoped[1], icons: scoped[1] === "@onno/ui" ? " (or `@onno/ui/icons` for icons)" : "",
        } });
      } else if (/(^|\/)onno-ui\/src(\/|$)|node_modules\/@onno\//.test(source)) {
        context.report({ node: sourceNode, messageId: "sourceTree", data: { source } });
      } else if (lucide && (source === "lucide-react" || source.startsWith("lucide-react/"))) {
        context.report({ node: sourceNode, messageId: "lucide" });
      }
    }
    return {
      ImportDeclaration: (node) => check(node.source),
      ExportNamedDeclaration: (node) => node.source && check(node.source),
      ExportAllDeclaration: (node) => check(node.source),
      ImportExpression: (node) => check(node.source),
    };
  },
};

const iconComponent = {
  meta: {
    type: "problem",
    docs: docs("`icon` takes an imported Lucide component, never an icon-name string."),
    schema: [],
    messages: {
      named: "`icon` takes an imported Lucide component, not the name \"{{name}}\". Write `import { {{component}} } from \"@onno/ui/icons\"` and pass `icon: {{component}}` (or `icon={ {{component}} }`).",
    },
  },
  create(context) {
    function insideCva(node) {
      for (let at = node.parent; at; at = at.parent) {
        if (at.type === "CallExpression" && at.callee.type === "Identifier" && at.callee.name === "cva") return true;
      }
      return false;
    }
    function report(node, value) {
      const name = leadingText(value);
      if (name === null || !name.trim() || insideCva(node)) return;
      context.report({ node: value, messageId: "named", data: { name, component: pascal(name) || "Truck" } });
    }
    return {
      Property(node) {
        if (propertyName(node) === "icon") report(node, node.value);
      },
      JSXAttribute(node) {
        if (node.name.type !== "JSXIdentifier" || node.name.name !== "icon" || !node.value) return;
        const value = node.value.type === "JSXExpressionContainer" ? node.value.expression : node.value;
        report(node, value);
      },
    };
  },
};

const noRawApiFetch = {
  meta: {
    type: "problem",
    docs: docs("Read and write onno data through the typed hooks or `api`, not a raw fetch of /api."),
    schema: [],
    messages: {
      rawFetch: "Don't call the onno REST API with a raw `fetch(\"{{path}}…\")`: it bypasses the CSRF header, error toasts, typed results and live refresh. Use the typed hooks from `@onno/ui` (`useRecords`, `useRecord`, `useAggregate`, `useBalance`, `useCommand`, the write hooks) or its `api` client; behaviour the hooks lack belongs in a Java `Commands` bean.",
    },
  },
  create(context) {
    const isFetch = (callee) =>
      (callee.type === "Identifier" && callee.name === "fetch")
      || (callee.type === "MemberExpression" && !callee.computed && callee.property.type === "Identifier"
        && callee.property.name === "fetch" && callee.object.type === "Identifier"
        && (callee.object.name === "window" || callee.object.name === "globalThis" || callee.object.name === "self"));
    return {
      CallExpression(node) {
        if (!isFetch(node.callee)) return;
        const path = leadingText(node.arguments[0]);
        if (path !== null && /^\/api(\/|\?|$)/.test(path)) context.report({ node, messageId: "rawFetch", data: { path: "/api" } });
      },
    };
  },
};

const notifyCaughtError = {
  meta: {
    type: "problem",
    docs: docs("Report a caught failure with notifyError, so one failed request shows one toast (#505)."),
    schema: [],
    messages: {
      toast: "Report the caught `{{name}}` with `notifyError({{name}})` from lib/notify-error, not `toast.error`: the api layer may already have toasted this ApiError, and the user would see the failure twice (#505). Word it yourself with `notifyError({{name}}, { fallback })` or `{ format }`.",
    },
  },
  create(context) {
    /** Names bound to a caught failure, innermost last. */
    const caught = [];
    const catchParam = (fn) => (fn.params[0]?.type === "Identifier" ? fn.params[0].name : null);
    const isPromiseCatch = (fn) =>
      fn.parent?.type === "CallExpression" && fn.parent.arguments[0] === fn
      && fn.parent.callee.type === "MemberExpression" && !fn.parent.callee.computed
      && fn.parent.callee.property.type === "Identifier" && fn.parent.callee.property.name === "catch";
    const isToastError = (callee) =>
      callee.type === "MemberExpression" && !callee.computed && callee.object.type === "Identifier"
      && callee.object.name === "toast" && callee.property.type === "Identifier" && callee.property.name === "error";
    function references(node, name) {
      if (!node || typeof node !== "object") return false;
      if (node.type === "Identifier") return node.name === name;
      for (const key of Object.keys(node)) {
        if (key === "parent" || key === "loc" || key === "range") continue;
        if (key === "property" && node.type === "MemberExpression" && !node.computed) continue;
        if (key === "key" && node.type === "Property" && !node.computed) continue;
        const child = node[key];
        if (Array.isArray(child) ? child.some((each) => references(each, name)) : child && typeof child.type === "string" && references(child, name)) return true;
      }
      return false;
    }
    const enterFn = (fn) => { if (isPromiseCatch(fn)) caught.push(catchParam(fn)); };
    const exitFn = (fn) => { if (isPromiseCatch(fn)) caught.pop(); };
    return {
      CatchClause: (node) => caught.push(node.param?.type === "Identifier" ? node.param.name : null),
      "CatchClause:exit": () => caught.pop(),
      ArrowFunctionExpression: enterFn,
      "ArrowFunctionExpression:exit": exitFn,
      FunctionExpression: enterFn,
      "FunctionExpression:exit": exitFn,
      CallExpression(node) {
        if (!isToastError(node.callee)) return;
        const name = caught.findLast((each) => each && node.arguments.some((arg) => references(arg, each)));
        if (name) context.report({ node, messageId: "toast", data: { name } });
      },
    };
  },
};

const plugin = {
  meta: { name: "@onno/ui/eslint" },
  rules: {
    "no-private-imports": noPrivateImports,
    "icon-component": iconComponent,
    "no-raw-api-fetch": noRawApiFetch,
    "notify-caught-error": notifyCaughtError,
  },
  configs: {},
};

/**
 * For an app's own UI (src/main/ui) on @onno/ui. It configures no parser: pair it with the app's
 * TypeScript setup (`typescript-eslint`), which an onnoUiInit app already type-checks with.
 */
plugin.configs.app = {
  name: "onno/app",
  files: ["**/*.{js,jsx,ts,tsx}"],
  plugins: { onno: plugin },
  rules: {
    "onno/no-private-imports": "error",
    "onno/icon-component": "error",
    "onno/no-raw-api-fetch": "error",
  },
};

export const rules = plugin.rules;
export const configs = plugin.configs;
export default plugin;
