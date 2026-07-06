// esbuild's automatic JSX runtime emits imports from `react/jsx-runtime`; the widget build aliases
// that to this shim so JSX compiles against the host SPA's runtime (window.onno.jsxRuntime) — no
// second React. CommonJS so the live host namespace object (jsx, jsxs, Fragment) is the export.
const g = typeof window !== "undefined" ? window : globalThis;
if (!g.onno || !g.onno.jsxRuntime) {
  throw new Error("@onno/widget-sdk: window.onno.jsxRuntime is not installed (plugin loaded outside the onno SPA).");
}
module.exports = g.onno.jsxRuntime;
