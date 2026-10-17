#!/usr/bin/env bash
# Checks that Nuvio still talks to external players the way Nuvio To Zidoo expects.
#
#   scripts/check-nuvio.sh <path to a NuvioTV checkout>
#
# Prints one line per check and exits 1 if any check fails. Run weekly by
# .github/workflows/nuvio-check.yml against the newest NuvioMedia/NuvioTV code.
set -u

nuvio="${1:?usage: check-nuvio.sh <NuvioTV checkout>}"
player="$nuvio/app/src/main/java/com/nuvio/tv/core/player"
contract="$player/ExternalPlayerResultContract.kt"
tracker="$player/ExternalPlaybackTracker.kt"
failures=0

check() {
  local description="$1" file="$2" pattern="$3"
  if [ -f "$file" ] && grep -Fq -- "$pattern" "$file"; then
    echo "ok    $description"
  else
    echo "FAIL  $description (looked for: $pattern in ${file#"$nuvio"/})"
    failures=$((failures + 1))
  fi
}

# What Nuvio sends when it opens the external player.
check "sends the resume point as \"position\"" "$contract" 'putExtra("position"'
check "sends \"from_start\" for Start from beginning" "$contract" 'putExtra("from_start"'
check "sends the title as \"title\"" "$contract" 'putExtra("title"'
check "sends skip timestamps as \"skip_segments\"" "$contract" '"skip_segments"'
check "sends request headers as \"headers\"" "$contract" 'putExtra("headers"'

# What Nuvio reads back.
check "reads the position back from \"extra_position\"/\"position\"" "$contract" '"extra_position", "position"'
check "reads the duration back from \"extra_duration\"/\"duration\"" "$contract" '"extra_duration", "duration"'
check "reads \"end_by\"" "$contract" '"end_by"'
check "treats end_by=playback_completion as finished" "$contract" '"playback_completion"'

# The episode title format Up Next relies on: "Show - S01E05".
check "titles episodes \"Show - SxxEyy\"" "$tracker" "E\${episode.toString().padStart(2, '0')}"
check "joins show and episode with \" - \"" "$tracker" '"$base - $seasonEp"'

# Auto-play of the next episode after a finished external playback.
check "auto-plays the next episode after an external finish" "$tracker" 'maybeTriggerAutoNextEpisode(metadata)'

version="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' "$nuvio/app/build.gradle.kts" | head -1)"
echo
echo "Nuvio version checked: ${version:-unknown}"

if [ "$failures" -gt 0 ]; then
  echo "$failures check(s) failed."
  exit 1
fi
echo "All checks passed."
