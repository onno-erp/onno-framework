// esbuild aliases bare `react` imports in a widget bundle to this shim, so the compiled plugin uses
// the host SPA's single React instance (on window.onno.React) instead of bundling its own — which is
// what makes hooks, context, and the shared render tree work. It's a CommonJS module on purpose: the
// export is the live host React object, so every named import (useState, createElement, memo, …) and
// the default import resolve against it automatically without enumerating the API.
const g = typeof window !== "undefined" ? window : globalThis;
if (!g.onno || !g.onno.React) {
  throw new Error("@onno/widget-sdk: window.onno.React is not installed (plugin loaded outside the onno SPA).");
}
module.exports = g.onno.React;
