"""Banc de reglage : confronte les reglages conseillés a une recherche aleatoire.

Protocole. Le conseiller ne consomme qu'UNE execution ; la recherche aleatoire en
consomme N. Les trois lectures possibles :

  conseiller >= meilleur aleatoire  -> la regle gagne franchement : meme resultat
                                       pour N fois moins de calcul
  conseiller ~ mediane aleatoire    -> la regle n'apporte rien, autant tirer au sort
  conseiller <  mediane aleatoire   -> la regle nuit, les seuils sont a revoir

Le budget en secondes est identique pour toutes les executions : sans cela la
comparaison ne veut rien dire.

    python -m tools.stats.tuner instances/kittens.in --seconds 10 --random 8
"""

from __future__ import annotations

import argparse
import json
import random
import statistics
import subprocess
import sys
import time
from pathlib import Path

from .advisor import BASE_CONFIG, recommend

ROOT = Path(__file__).resolve().parent.parent.parent
CLASSES = ROOT / "out"

SEARCH_SPACE = {
    "ils.descentShare": lambda r: round(r.uniform(0.1, 0.7), 3),
    "ils.sweeps": lambda r: r.randint(10, 80),
    "ils.kick": lambda r: r.randint(1, 12),
    "ils.local": lambda r: r.randint(1, 8),
    "ils.temp": lambda r: 0.0 if r.random() < 0.4 else round(10 ** r.uniform(-3, -1), 5),
    "ils.noise": lambda r: round(r.uniform(0.0, 0.5), 3),
    "ils.repair": lambda r: r.randint(1, 4),
}


def compile_java() -> None:
    sources = [ROOT / "src" / "GoogleHash2017.java"]
    for sub in ("model", "io", "solver", "solver/impl", "solver/ils"):
        sources.extend(sorted((ROOT / "src" / sub).glob("*.java")))
    CLASSES.mkdir(parents=True, exist_ok=True)
    result = subprocess.run(["javac", "-d", str(CLASSES)] + [str(s) for s in sources],
                            cwd=ROOT, capture_output=True, text=True)
    if result.returncode != 0:
        print(result.stderr)
        raise SystemExit("compilation echouee")


def run_solver(instance: Path, config: dict, seconds: int, tag: str) -> dict:
    out_path = ROOT / "output" / f"{instance.stem}.{tag}.out"
    trace_path = ROOT / "traces" / f"{instance.stem}.{tag}.json"
    out_path.parent.mkdir(parents=True, exist_ok=True)
    trace_path.parent.mkdir(parents=True, exist_ok=True)

    flags = [f"-D{k}={v}" for k, v in sorted(config.items())]
    cmd = (["java", f"-Dils.seconds={seconds}", f"-Dils.trace={trace_path}"] + flags
           + ["-cp", str(CLASSES), "GoogleHash2017", str(instance), str(out_path)])

    started = time.perf_counter()
    result = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True)
    elapsed = time.perf_counter() - started

    if result.returncode != 0 or not trace_path.is_file():
        return {"tag": tag, "config": config, "score": None,
                "erreur": (result.stderr or "pas de trace")[:200]}

    trace = json.loads(trace_path.read_text())
    return {
        "tag": tag,
        "config": config,
        "score": trace["score_final"],
        "part_borne_superieure": trace["part_borne_superieure"],
        "kicks": trace["ils"]["kicks"],
        "improvements": trace["ils"]["improvements"],
        "degeneree": trace["degeneree"],
        "secondes_reelles": round(elapsed, 1),
    }


def random_config(rng: random.Random) -> dict:
    return {key: draw(rng) for key, draw in SEARCH_SPACE.items()}


def evaluate(instance: Path, seconds: int, n_random: int, seed: int) -> dict:
    rng = random.Random(seed)

    runs = [run_solver(instance, dict(BASE_CONFIG), seconds, "defaut")]
    print(f"    defaut        -> {runs[-1]['score']}", flush=True)

    rec = recommend(instance)
    runs.append(run_solver(instance, rec["configuration"], seconds, "conseiller"))
    print(f"    conseiller    -> {runs[-1]['score']}  ({rec['archetype']})", flush=True)

    randoms = []
    for i in range(n_random):
        res = run_solver(instance, random_config(rng), seconds, f"alea{i}")
        randoms.append(res)
        print(f"    aleatoire {i:<2d}  -> {res['score']}", flush=True)
    runs.extend(randoms)

    scores = [r["score"] for r in randoms if r["score"] is not None]
    best_random = max(randoms, key=lambda r: r["score"] or -1) if scores else None

    return {
        "instance": instance.name,
        "archetype": rec["archetype"],
        "plafond": rec["plafond_score"],
        "secondes_par_run": seconds,
        "defaut": runs[0]["score"],
        "conseiller": runs[1]["score"],
        "aleatoire_meilleur": best_random["score"] if best_random else None,
        "aleatoire_median": int(statistics.median(scores)) if scores else None,
        "aleatoire_pire": min(scores) if scores else None,
        "config_meilleur_aleatoire": best_random["config"] if best_random else None,
        "runs": runs,
    }


def verdict(row: dict) -> str:
    adv, best, med = row["conseiller"], row["aleatoire_meilleur"], row["aleatoire_median"]
    worst = row["aleatoire_pire"]
    if adv is None or best is None or med is None:
        return "incomplet"
    if worst == best == adv == row["defaut"]:
        return "indecidable : toutes les configurations donnent le meme score"
    if adv > best:
        return "regle GAGNE (au-dessus du meilleur aleatoire, pour 1 run contre N)"
    if adv == best:
        return "regle egale le meilleur aleatoire, pour 1 run contre N"
    if adv >= med:
        return "regle utile mais battue par la recherche aleatoire"
    return "regle NUIT (sous la mediane aleatoire)"


def main() -> None:
    parser = argparse.ArgumentParser(description="Reglage par instance : conseiller contre aleatoire")
    parser.add_argument("instances", nargs="+", type=Path)
    parser.add_argument("--seconds", type=int, default=10, help="budget identique par execution")
    parser.add_argument("--random", type=int, default=6, help="nombre de configurations aleatoires")
    parser.add_argument("--seed", type=int, default=1)
    parser.add_argument("--json", type=Path)
    parser.add_argument("--skip-compile", action="store_true")
    args = parser.parse_args()

    if not args.skip_compile:
        print("compilation...")
        compile_java()

    rows = []
    for instance in args.instances:
        print(f"\n=== {instance.name} ({args.seconds} s par run) ===", flush=True)
        rows.append(evaluate(instance, args.seconds, args.random, args.seed))

    print("\n" + "=" * 100)
    print(f"{'instance':<28} {'archetype':<20} {'defaut':>10} {'conseiller':>11} "
          f"{'alea max':>10} {'alea med':>10}")
    print("-" * 100)
    for row in rows:
        print(f"{row['instance']:<28} {row['archetype']:<20} {row['defaut'] or 0:>10} "
              f"{row['conseiller'] or 0:>11} {row['aleatoire_meilleur'] or 0:>10} "
              f"{row['aleatoire_median'] or 0:>10}")
    print("-" * 100)
    for row in rows:
        print(f"  {row['instance']:<28} {verdict(row)}")

    total_default = sum(r["defaut"] or 0 for r in rows)
    total_advisor = sum(r["conseiller"] or 0 for r in rows)
    print(f"\n  somme des scores : defaut {total_default}  conseiller {total_advisor}  "
          f"({total_advisor - total_default:+d})")

    if args.json:
        args.json.write_text(json.dumps(rows, indent=2, ensure_ascii=False), encoding="utf-8")
        print(f"\nJSON : {args.json}")


if __name__ == "__main__":
    main()
