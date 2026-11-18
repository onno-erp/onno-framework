#!/usr/bin/env node
// Publish the framework's frontend packages (@onno/ui, @onno/crm, @onno/crm-channels, @onno/print)
// at the release version, from the publish workflow.
//
//   node .github/scripts/publish-npm.mjs <version> [--dry-run]
//
// Every package is stamped with the release version (the version in the repository is a
// placeholder), and its @onno/* dependencies are pinned to that same version: the workspace uses
// "*", which npm never matches against a pre-release.
//
// Targets:
//   - the onno npm registry (Verdaccio at NPM_REGISTRY_URL, default https://npm.onno.su/), always,
//     with user NPM_REGISTRY_USER (default "publisher") and password NPM_REGISTRY_PASSWORD;
//   - npmjs.org, for a full release only, when NPM_TOKEN is set.
// A pre-release gets the dist-tag "next", a full release "latest". A version that already exists on
// a target is skipped, so a re-run after a partial failure finishes the job.

import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const PACKAGES = [
  "onno-ui",
  "onno-crm-starter/src/main/ui",
  "onno-crm-channels-starter/src/main/ui",
  "onno-print-starter/src/main/ui",
];

const [version, ...flags] = process.argv.slice(2);
const dryRun = flags.includes("--dry-run");
if (!version || !/^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$/.test(version)) {
  console.error(`Usage: publish-npm.mjs <version> [--dry-run] (got ${version ?? "nothing"})`);
  process.exit(1);
}
const prerelease = version.includes("-");
const distTag = prerelease ? "next" : "latest";

const onnoRegistry = (process.env.NPM_REGISTRY_URL || "https://npm.onno.su/").replace(/\/?$/, "/");
const targets = [];
if (process.env.NPM_REGISTRY_PASSWORD) {
  const host = onnoRegistry.replace(/^https?:/, "");
  targets.push({
    name: "onno npm registry",
    registry: onnoRegistry,
    npmrc: `${host}:username=${process.env.NPM_REGISTRY_USER || "publisher"}\n`
      + `${host}:_password=${Buffer.from(process.env.NPM_REGISTRY_PASSWORD).toString("base64")}\n`,
  });
} else {
  console.error("NPM_REGISTRY_PASSWORD is not set: cannot publish to the onno npm registry.");
  if (!dryRun) process.exit(1);
}
if (!prerelease && process.env.NPM_TOKEN) {
  targets.push({
    name: "npmjs.org",
    registry: "https://registry.npmjs.org/",
    npmrc: `//registry.npmjs.org/:_authToken=${process.env.NPM_TOKEN}\n`,
    extra: ["--access", "public"],
  });
}

// Stamp the version and pin the internal dependencies, in place (CI checkouts are throwaway).
const names = new Set(PACKAGES.map((dir) => JSON.parse(readFileSync(join(dir, "package.json"), "utf8")).name));
for (const dir of PACKAGES) {
  const file = join(dir, "package.json");
  const pkg = JSON.parse(readFileSync(file, "utf8"));
  pkg.version = version;
  for (const section of ["dependencies", "peerDependencies", "optionalDependencies"]) {
    for (const dep of Object.keys(pkg[section] ?? {})) {
      if (names.has(dep)) pkg[section][dep] = version;
    }
  }
  writeFileSync(file, `${JSON.stringify(pkg, null, 2)}\n`);
}

const npm = (args, opts = {}) => execFileSync("npm", args, { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"], ...opts });

for (const target of targets) {
  const userconfig = join(mkdtempSync(join(tmpdir(), "npmrc-")), ".npmrc");
  writeFileSync(userconfig, target.npmrc, { mode: 0o600 });
  for (const dir of PACKAGES) {
    const { name } = JSON.parse(readFileSync(join(dir, "package.json"), "utf8"));
    let exists = false;
    try {
      exists = npm(["view", `${name}@${version}`, "version", "--registry", target.registry, "--userconfig", userconfig]).trim() === version;
    } catch {
      exists = false; // 404: not published yet
    }
    if (exists) {
      console.log(`${target.name}: ${name}@${version} already published, skipping`);
      continue;
    }
    const args = ["publish", "--registry", target.registry, "--userconfig", userconfig, "--tag", distTag, ...(target.extra ?? [])];
    if (dryRun) args.push("--dry-run");
    npm(args, { cwd: dir, stdio: "inherit" });
    console.log(`${target.name}: published ${name}@${version} (${distTag})${dryRun ? " [dry run]" : ""}`);
  }
}
