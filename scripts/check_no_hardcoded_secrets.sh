#!/usr/bin/env bash
#
# SEC-007 — Static check: no hardcoded secrets committed in tracked Spring config.
#
# Test case: docs/Test_Cases_V1.xlsx, sheet 13_SECURITY_HARDENING, row SEC-007.
#   Tool:               Custom script / grep
#   Automation Mapping: Static Check | CI lint step | check_no_hardcoded_secrets.sh
#
# What it asserts (SEC-007 Test Steps / Expected Result):
#   Every secret-bearing placeholder in tracked application*.yml / application*.properties is a
#   BARE ${VAR} with no insecure literal fallback. A pattern of the form ${JWT_SECRET:something}
#   or ${SPRING_DATASOURCE_PASSWORD:something} means the literal after the colon IS a committed
#   secret and anyone reading the repo can use it.
#
# It also fails on a plain literal assignment (e.g. `password: 1234`) for the same keys.
#
# Files that git ignores (e.g. application-local.yml, .env) are NOT scanned — they are, by
# definition, not committed. When run outside a git work tree the ignore list is read from
# .gitignore as a plain-text fallback.
#
# Usage:  bash fieldops/scripts/check_no_hardcoded_secrets.sh
# Exit:   0 = clean, 1 = at least one committed secret / insecure default found.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

# Config keys whose value must never be committed as a literal.
SECRET_KEYS=(
  "JWT_SECRET"
  "SPRING_DATASOURCE_PASSWORD"
  "SPRING_DATASOURCE_USERNAME"
  "FIREBASE_PRIVATE_KEY"
  "SMS_API_KEY"
)

# Bare `key: literal` forms that bypass ${VAR} entirely.
LITERAL_KEY_PATTERN='^[[:space:]]*(password|secret|api-key|apiKey|private-key|privateKey)[[:space:]]*:[[:space:]]*[^$[:space:]#].*$'

failures=0
scanned=0

is_ignored() {
  local f="$1"
  if git -C "${MODULE_ROOT}" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    git -C "${MODULE_ROOT}" check-ignore -q "$f" && return 0
    return 1
  fi
  # Fallback: plain-text .gitignore basename match.
  local base
  base="$(basename "$f")"
  [ -f "${MODULE_ROOT}/.gitignore" ] && grep -qxF "${base}" "${MODULE_ROOT}/.gitignore"
}

echo "SEC-007 — scanning tracked Spring config under ${MODULE_ROOT} for hardcoded secrets"
echo "--------------------------------------------------------------------------------"

while IFS= read -r -d '' file; do
  if is_ignored "$file"; then
    echo "  skip (git-ignored, not committed): ${file#"${MODULE_ROOT}/"}"
    continue
  fi

  scanned=$((scanned + 1))
  rel="${file#"${MODULE_ROOT}/"}"
  echo "  scan: ${rel}"

  # 1. ${SECRET_KEY:insecure-default}
  for key in "${SECRET_KEYS[@]}"; do
    if hits="$(grep -nE "\\\$\{${key}:[^}]+\}" "$file")"; then
      while IFS= read -r line; do
        echo "    FAIL ${rel}:${line}"
        echo "         -> \${${key}:...} has a literal fallback; use a bare \${${key}}."
        failures=$((failures + 1))
      done <<< "$hits"
    fi
  done

  # 2. bare `password: <literal>` style assignments
  if hits="$(grep -nE "${LITERAL_KEY_PATTERN}" "$file")"; then
    while IFS= read -r line; do
      echo "    FAIL ${rel}:${line}"
      echo "         -> secret-bearing key assigned a literal value; use \${ENV_VAR}."
      failures=$((failures + 1))
    done <<< "$hits"
  fi
done < <(find "${MODULE_ROOT}/src/main/resources" \
              \( -name 'application*.yml' -o -name 'application*.yaml' \
                 -o -name 'application*.properties' \) -type f -print0)

echo "--------------------------------------------------------------------------------"
if [ "${scanned}" -eq 0 ]; then
  echo "SEC-007 ERROR: no config files were scanned — check the module path."
  exit 1
fi

if [ "${failures}" -eq 0 ]; then
  echo "SEC-007 PASS: ${scanned} tracked config file(s) scanned, no hardcoded secrets found."
  exit 0
fi

echo "SEC-007 FAIL: ${failures} hardcoded secret / insecure default finding(s)."
exit 1
