#!/usr/bin/env bash
# Model-checks the formal models in docs/formal with TLC.
#
#   docs/formal/check.sh                     check ProcessTokens.cfg (must pass)
#   docs/formal/check.sh --counterexamples   also run the configs that MUST find a violation
#
# The pinned tla2tools.jar is downloaded once into build/tla/ (gitignored) and verified by
# SHA-256. Override the location with TLA2TOOLS_JAR=/path/to/tla2tools.jar (no download then).
# Needs Java 11+ and, on first run, network access to github.com.
set -euo pipefail

TLA_VERSION="1.7.4"
TLA_SHA256="936a262061c914694dfd669a543be24573c45d5aa0ff20a8b96b23d01e050e88"
TLA_URL="https://github.com/tlaplus/tlaplus/releases/download/v${TLA_VERSION}/tla2tools.jar"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SPEC_DIR="${ROOT}/docs/formal"
WORK="${ROOT}/build/tla"
JAR="${TLA2TOOLS_JAR:-${WORK}/tla2tools-${TLA_VERSION}.jar}"
mkdir -p "${WORK}"

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

if [[ -z "${TLA2TOOLS_JAR:-}" ]]; then
  if [[ ! -f "${JAR}" ]] || [[ "$(sha256 "${JAR}")" != "${TLA_SHA256}" ]]; then
    echo "Downloading tla2tools ${TLA_VERSION} ..."
    curl -fsSL -o "${JAR}.part" "${TLA_URL}"
    if [[ "$(sha256 "${JAR}.part")" != "${TLA_SHA256}" ]]; then
      rm -f "${JAR}.part"
      echo "tla2tools.jar checksum mismatch; refusing to run it" >&2
      exit 1
    fi
    mv "${JAR}.part" "${JAR}"
  fi
fi

# Runs TLC on one config; prints the summary lines; returns TLC's exit status.
run_tlc() {
  local spec="$1"
  local config="$2"
  local log="${WORK}/${config%.cfg}.log"
  echo "== TLC ${spec} with ${config}"
  set +e
  (cd "${SPEC_DIR}" && java -XX:+UseParallelGC -cp "${JAR}" tlc2.TLC \
      -workers auto -cleanup -metadir "${WORK}/states" \
      -config "${config}" "${spec}") > "${log}" 2>&1
  local status=$?
  set -e
  grep -E "Model checking completed|Error:|is violated|Temporal properties were violated|states generated|depth of the complete" "${log}" || true
  echo "   (full log: ${log}, exit ${status})"
  return "${status}"
}

failed=0
run_tlc ProcessTokens.tla ProcessTokens.cfg || failed=1

if [[ "${1:-}" == "--counterexamples" ]]; then
  # These configs model behaviours the engine must NOT have (or a known gap); TLC must
  # find a violation, otherwise the model has lost its teeth.
  for config in ProcessTokensSequentialMigration.cfg ProcessTokensEscapingMigration.cfg; do
    if run_tlc ProcessTokens.tla "${config}"; then
      echo "   expected a violation from ${config}, but TLC found none" >&2
      failed=1
    else
      echo "   violation found, as expected"
    fi
  done
fi

exit "${failed}"
