#!/usr/bin/env bash
# Resolution d'une instance, avec registre : une execution moins bonne
# n'ecrase JAMAIS une meilleure solution deja obtenue.
#
#   ./solve.sh instances/kittens.in [budget_secondes] [dossier_sortie]
#   ./solve.sh instances/kittens.in 60 final
#
# Relancer plusieurs fois ne peut que faire monter le score : la variance entre
# executions vaut quelques centaines de points sur les grosses instances.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

INSTANCE="${1:?usage: ./solve.sh <instance.in> [budget_secondes] [dossier_sortie]}"
BUDGET="${2:-60}"
OUTDIR="${3:-final}"
HEAP="${HEAP:-8g}"

[ -f "$INSTANCE" ] || { echo "instance introuvable : $INSTANCE" >&2; exit 1; }

NAME="$(basename "$INSTANCE" .in)"
BEST="$OUTDIR/$NAME.out"
SCOREFILE="$OUTDIR/$NAME.score"
CANDIDATE="$(mktemp "$OUTDIR/.$NAME.XXXXXX.out" 2>/dev/null || mktemp)"
trap 'rm -f "$CANDIDATE"' EXIT

mkdir -p "$OUTDIR" out

# Compilation si les classes sont absentes ou plus vieilles que les sources.
if [ ! -f out/GoogleHash2017.class ] || [ -n "$(find src -name '*.java' -newer out/GoogleHash2017.class 2>/dev/null | head -1)" ]; then
  echo "compilation..."
  javac -d out src/GoogleHash2017.java src/model/*.java src/io/*.java \
        src/solver/*.java src/solver/impl/*.java src/solver/ils/*.java
fi

if [ ! -x ./judge ] && [ -f judgeHashCode2017.cpp ]; then
  g++ -O3 judgeHashCode2017.cpp -o judge 2>/dev/null || true
fi

echo "resolution de $NAME (budget ${BUDGET}s, heap $HEAP)"
START=$(date +%s)
java "-Xmx$HEAP" "-Dils.seconds=$BUDGET" -cp out GoogleHash2017 "$INSTANCE" "$CANDIDATE" \
  | sed 's/^/  /'
ELAPSED=$(( $(date +%s) - START ))

if [ ! -s "$CANDIDATE" ]; then
  echo "ECHEC : aucune solution produite, le fichier existant est conserve" >&2
  exit 1
fi

# Le juge officiel fait foi, et il renvoie 0 si la solution est invalide.
if [ -x ./judge ]; then
  NEW=$(./judge "$INSTANCE" "$CANDIDATE" | grep -oE '[0-9]+$' || echo 0)
else
  echo "juge absent : solution acceptee sans verification de score" >&2
  NEW=1
fi

if [ "$NEW" -le 0 ]; then
  echo "SOLUTION INVALIDE (score $NEW) : le fichier existant est conserve" >&2
  exit 1
fi

OLD=0
[ -f "$SCOREFILE" ] && OLD=$(cat "$SCOREFILE")

if [ "$NEW" -gt "$OLD" ]; then
  mv -f "$CANDIDATE" "$BEST"
  echo "$NEW" > "$SCOREFILE"
  trap - EXIT
  printf "  NOUVEAU MEILLEUR : %'d  (ancien %'d, +%'d)  en %ds\n" "$NEW" "$OLD" "$((NEW - OLD))" "$ELAPSED"
else
  printf "  %'d <= meilleur connu %'d : conserve, rien n'est ecrase  (%ds)\n" "$NEW" "$OLD" "$ELAPSED"
fi
