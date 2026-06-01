// Renders a curated set of lucide-react icons to public/icons/*.svg.
// The DivKit nav references these by /icons/{name}.svg and recolors them with
// tint_color, so they must be monochrome stroke SVGs (which is what lucide ships).
// Run via `npm run gen:icons`. Add a name here when a layout authors a new icon.
import { renderToStaticMarkup } from "react-dom/server";
import { createElement } from "react";
import { mkdirSync, writeFileSync } from "fs";
import { fileURLToPath } from "url";
import { dirname, resolve } from "path";

const NAMES = [
  "home", "euro", "users", "user", "book", "book-open", "bar-chart",
  "file-text", "sparkles", "calendar", "wallet", "banknote", "receipt",
  "building", "building-2", "package", "settings", "layout-dashboard",
  "circle", "map-pin", "bed",
];

const here = dirname(fileURLToPath(import.meta.url));
const outDir = resolve(here, "../public/icons");
const iconsDir = resolve(here, "../node_modules/lucide-react/dist/esm/icons");
mkdirSync(outDir, { recursive: true });

let ok = 0;
for (const name of NAMES) {
  try {
    const mod = await import(resolve(iconsDir, `${name}.js`));
    const svg = renderToStaticMarkup(createElement(mod.default));
    writeFileSync(resolve(outDir, `${name}.svg`), svg + "\n");
    ok++;
  } catch {
    console.warn(`skip: ${name} (not found in this lucide-react version)`);
  }
}
console.log(`wrote ${ok}/${NAMES.length} icons to public/icons/`);
