#!/usr/bin/env bash
# Issue #77 (epic #76): a static check for the calls antivirus engines dislike
# in an unsigned exe: hidden schtasks/reg/powershell/cmd, privilege tricks,
# service registration from the app, Run-key writes, self-extracted binaries,
# self-replacement of the running exe.
#
# Scans every .go file under desktop/ except *_test.go, installer/ and scripts/.
# Known violations live in scripts/forbidden-calls.allowlist (file|rule|stage|why).
# A violation not in the list fails; a list entry with no violation left fails
# too, so the list only shrinks. Run from anywhere: scripts/forbidden-calls.sh
#   --list   print every current violation (file:line) and exit 0
#   --selftest   prove that a new schtasks call and a stale entry both fail
set -euo pipefail

cd "$(dirname "$0")/.."
allow="${FORBIDDEN_ALLOWLIST:-scripts/forbidden-calls.allowlist}"
root="${FORBIDDEN_ROOT:-.}"

# rule id @@ extended regex @@ path regex excluded from this rule (may be empty)
rules=(
  'schtasks@@schtasks@@'
  'reg-exe@@reg\.exe|"reg"|reg add@@'
  'powershell@@powershell|pwsh\.exe@@'
  'cmd-c@@cmd(\.exe)?"?,[[:space:]]*"?/c|cmd /c@@'
  'se-takeownership@@SeTakeOwnershipPrivilege@@'
  'se-restore@@SeRestorePrivilege@@'
  'set-owner@@SetNamedSecurityInfo.*OWNER_SECURITY_INFORMATION@@'
  'service-create-delete@@\.(CreateService|DeleteService)\(@@'
  'run-key@@CurrentVersion\\Run@@'
  'embed-binary@@go:embed[^"]*\.(dll|exe|sys|so)@@'
  'self-replace@@(exe|dst|path|staged)[A-Za-z]*[[:space:]]*\+[[:space:]]*"\.(old|new)"|Boop\.exe\.(old|new)@@^core/(log|diagnostics)\.go$'
)

scan() { # prints "file|rule|file:line:text"
  local r id rx ex f
  for r in "${rules[@]}"; do
    id="${r%%@@*}"; r="${r#*@@}"
    rx="${r%%@@*}"; ex="${r#*@@}"
    while IFS= read -r f; do
      f="${f#./}"
      if [[ -n "$ex" && "$f" =~ $ex ]]; then continue; fi
      case "$f" in installer/*|scripts/*|*_test.go) continue ;; esac
      grep -nE -- "$rx" "$root/$f" | sed -E "s#^#$f|$id|$f:#" || true
    done < <(cd "$root" && find . -name '*.go' -not -name '*_test.go' | sort)
  done
}

check() {
  local found keys allowed bad=0 k
  found="$(scan)"
  keys="$(cut -d'|' -f1,2 <<<"$found" | grep -v '^$' | sort -u || true)"
  allowed="$(grep -vE '^[[:space:]]*(#|$)' "$allow" | cut -d'|' -f1,2 | sort -u)"
  while IFS= read -r k; do
    [[ -z "$k" ]] && continue
    if ! grep -qxF -- "$k" <<<"$allowed"; then
      echo "::error::new forbidden call ${k#*|} in ${k%%|*} (not in $allow)"
      grep -F -- "${k%%|*}|${k#*|}|" <<<"$found" | cut -d'|' -f3- | sed 's/^/    /'
      bad=1
    fi
  done <<<"$keys"
  while IFS= read -r k; do
    [[ -z "$k" ]] && continue
    if ! grep -qxF -- "$k" <<<"$keys"; then
      echo "::error::stale allowlist entry ${k#*|} in ${k%%|*}: the violation is gone, delete the line from $allow"
      bad=1
    fi
  done <<<"$allowed"
  [[ $bad -eq 0 ]] && echo "ok: $(wc -l <<<"$keys" | tr -d ' ') known violations, all in the allowlist, none new, none stale"
  return $bad
}

selftest() {
  local tmp rc
  tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' RETURN
  mkdir -p "$tmp/winsvc"
  printf 'package x\nfunc f() { exec.Command("schtasks.exe", "/Create") }\n' >"$tmp/winsvc/new.go"
  : >"$tmp/empty.allowlist"
  rc=0; FORBIDDEN_ROOT="$tmp" FORBIDDEN_ALLOWLIST="$tmp/empty.allowlist" "$0" >/dev/null 2>&1 || rc=$?
  [[ $rc -ne 0 ]] || { echo "selftest: a new schtasks call did not fail the check"; return 1; }
  echo 'winsvc/gone.go|schtasks|C-3|stale' >"$tmp/stale.allowlist"
  rm "$tmp/winsvc/new.go"
  rc=0; FORBIDDEN_ROOT="$tmp" FORBIDDEN_ALLOWLIST="$tmp/stale.allowlist" "$0" >/dev/null 2>&1 || rc=$?
  [[ $rc -ne 0 ]] || { echo "selftest: a stale entry did not fail the check"; return 1; }
  echo "selftest ok: new violation fails, stale entry fails"
}

case "${1:-}" in
  --list) scan | cut -d'|' -f3- ;;
  --selftest) selftest ;;
  *) check ;;
esac
