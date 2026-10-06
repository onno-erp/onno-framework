#!/usr/bin/env node
// Release-gate npm audit with a narrow, reviewed allowlist.
//
// `npm audit` has no ignore mechanism, so this runs `npm audit --json` and fails on every
// advisory except the ones listed below. Each entry must say why it is tolerated; remove it as
// soon as a patched version exists. Usage: node .github/scripts/npm-audit.mjs [--prefix <dir>]
import { execFileSync } from "node:child_process";

const ALLOWED = new Map([
  // braces: every published version is affected; reached only through Tailwind 3's build-time
  // file watching/globbing (chokidar, micromatch). Nothing ships to the browser.
  ["GHSA-vfj7-8cjw-p6xm", "braces — no patched version; Tailwind 3 build tooling only"],
  // postcss-selector-parser <7.1.6: pinned to 6.x by Tailwind 3 / postcss-nested; build-time CSS
  // processing of our own sources only.
  ["GHSA-rj75-hqrm-r3gf", "postcss-selector-parser — no compatible fix; Tailwind 3 build tooling only"],
]);

const args = ["audit", "--json", ...process.argv.slice(2)];
let raw;
try {
  raw = execFileSync("npm", args, { encoding: "utf8", stdio: ["ignore", "pipe", "inherit"] });
} catch (e) {
  raw = e.stdout; // npm audit exits non-zero when it finds anything
}
const report = JSON.parse(raw);
if (report.error) {
  console.error("npm audit failed:", report.error);
  process.exit(1);
}

const blocking = [];
const tolerated = new Set();
for (const vuln of Object.values(report.vulnerabilities ?? {})) {
  for (const via of vuln.via ?? []) {
    if (typeof via !== "object") continue; // transitive: reported under its own advisory
    const id = String(via.url ?? "").split("/").pop();
    if (ALLOWED.has(id)) tolerated.add(`${id} (${ALLOWED.get(id)})`);
    else blocking.push(`${via.severity} ${via.name}: ${via.title} ${via.url}`);
  }
}

for (const t of tolerated) console.log(`tolerated: ${t}`);
if (blocking.length) {
  console.error(`npm audit found ${blocking.length} advisory(ies) not on the allowlist:`);
  for (const b of [...new Set(blocking)]) console.error(`  ${b}`);
  process.exit(1);
}
console.log("npm audit: no blocking advisories");
