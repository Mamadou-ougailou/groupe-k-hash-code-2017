"""
Pipeline complet : générer → valider → résoudre → juger → visualiser.

Usage:
    # Pipeline complet avec génération d'instance
    python tools/pipeline.py --generator dejaVu --seed 42

    # Pipeline avec instance existante
    python tools/pipeline.py --instance instances/me_at_the_zoo.in

    # Lancer aussi le dashboard après la résolution
    python tools/pipeline.py --instance instances/me_at_the_zoo.in --dashboard

    # Seulement le dashboard (pas de solve)
    python tools/pipeline.py --dashboard-only

Note sur le solveur :
    Le solveur Java est choisi dans src/GoogleHash2017.java (ligne hardcodée).
    Pour changer de solveur, modifiez cette ligne et relancez le pipeline.
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

# Project root = GoogleHashCode2017/
PROJECT_ROOT = Path(__file__).resolve().parent.parent

# Directories
INSTANCES_DIR = PROJECT_ROOT / "instances"
OUTPUT_DIR    = PROJECT_ROOT / "output"
OUT_DIR       = PROJECT_ROOT / "out"       # Java compiled classes

# Judge executable (platform-aware)
_JUDGE_EXE = PROJECT_ROOT / ("judge.exe" if sys.platform == "win32" else "judge")
_JUDGE_SRC = PROJECT_ROOT / "judgeHashCode2017.cpp"


# ─────────────────────────────────────────────────────────────────────────────
# Helpers
# ─────────────────────────────────────────────────────────────────────────────

def _rel(path: Path) -> Path:
    """Return path relative to PROJECT_ROOT when possible (cleaner subprocess args)."""
    try:
        return path.relative_to(PROJECT_ROOT)
    except ValueError:
        return path


def _header(step: str) -> None:
    print(f"\n{'=' * 60}")
    print(f"  {step}")
    print(f"{'=' * 60}")


# ─────────────────────────────────────────────────────────────────────────────
# Steps
# ─────────────────────────────────────────────────────────────────────────────

def step_generate(generator: str, seed: int) -> Path:
    """Step 1 – Generate a new instance with instanceCreator."""
    _header("STEP 1: GENERATE INSTANCE")

    # Import from the package (tools/ must be on sys.path)
    tools_dir = str(PROJECT_ROOT / "tools")
    if tools_dir not in sys.path:
        sys.path.insert(0, tools_dir)

    from instance_creator.instanceCreator import generator as gen_registry  # noqa: PLC0415

    if generator not in gen_registry:
        print(f"  ERROR: Unknown generator '{generator}'")
        print(f"  Available: {list(gen_registry.keys())}")
        sys.exit(1)

    # Default kwargs per generator (mirrors instanceCreator.__main__)
    kwargs: dict = {"seed": seed}
    if generator == "dejaVu":
        kwargs.update({"E": 115, "V": 10_000, "R": 999_000,
                       "V_min_size": 4, "V_max_size": 110, "C": 20, "X": 2000})
    else:  # universalLambda
        from instance_creator.instanceCreator import (  # noqa: PLC0415
            MAX_AUTHORISED_VIDEO_SIZE, MAX_AUTHORISED_SERVER_LATENCY,
            MAX_AUTHORISED_CACHE_LATENCY,
        )
        kwargs.update({
            "E": 115, "V": 10_000, "C": 20, "X": 2000,
            "videoSizeLambda": lambda idV, rng: max(1, MAX_AUTHORISED_VIDEO_SIZE - idV + rng.randint(0, idV + 1)),
            "requestLambda": lambda idE, idV, rng: rng.randint(8000, 12001) if (idE % 2, idV % 2) == (0, 1) else rng.randint(-100, 101),
            "dcLambda": lambda idE, rng: max(2, min((idE + MAX_AUTHORISED_SERVER_LATENCY) // 2 + rng.randint(-100, 100), MAX_AUTHORISED_SERVER_LATENCY)),
            "connectionLambda": lambda idE, idC, dcL, rng: rng.randint(-300, min(MAX_AUTHORISED_CACHE_LATENCY, dcL - 1)),
        })

    print(f"  Generator : {generator}")
    print(f"  Seed      : {seed}")

    INSTANCES_DIR.mkdir(parents=True, exist_ok=True)
    instance_str = gen_registry[generator](**kwargs)
    out_path = INSTANCES_DIR / f"custom_{generator.lower()}{seed}.in"
    out_path.write_text(instance_str, encoding="utf-8")

    print(f"  Written   : {out_path}")
    print(f"  Size      : {out_path.stat().st_size:,} bytes")
    return out_path


def step_validate(instance_path: Path) -> bool:
    """Step 2 – Validate the instance file."""
    _header("STEP 2: VALIDATE INSTANCE")
    print(f"  File: {instance_path}")

    tools_dir = str(PROJECT_ROOT / "tools")
    if tools_dir not in sys.path:
        sys.path.insert(0, tools_dir)

    from instance_creator.instanceValidator import validate_file  # noqa: PLC0415

    try:
        ok = validate_file(str(instance_path))
        if ok:
            print("  ✓ VALID INSTANCE")
        return ok
    except AssertionError as e:
        print(f"  ✗ INVALID INSTANCE: {e}")
        return False
    except Exception as e:  # noqa: BLE001
        print(f"  ✗ ERROR during validation: {e}")
        return False


def step_compile() -> bool:
    """Step 3 – Compile the Java solver."""
    _header("STEP 3: COMPILE JAVA SOLVER")

    OUT_DIR.mkdir(parents=True, exist_ok=True)

    # Collect all .java source files
    java_files = [PROJECT_ROOT / "src" / "GoogleHash2017.java"]
    for subdir in ("model", "io", "solver", "solver/impl", "solver/ils"):
        src_dir = PROJECT_ROOT / "src" / subdir
        if src_dir.is_dir():
            java_files.extend(sorted(src_dir.glob("*.java")))

    cmd = ["javac", "-d", str(OUT_DIR)] + [str(f) for f in java_files]
    print(f"  Compiling {len(java_files)} source file(s)...")

    result = subprocess.run(cmd, cwd=str(PROJECT_ROOT), capture_output=True, text=True)
    if result.returncode != 0:
        print("  ✗ Compilation FAILED")
        if result.stderr:
            print(result.stderr)
        return False

    print("  ✓ Compilation OK")
    return True


def step_solve(instance_path: Path) -> Path:
    """Step 4 – Run the Java solver."""
    _header("STEP 4: SOLVE")

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    output_path = OUTPUT_DIR / f"{instance_path.stem}.out"

    print(f"  Instance : {instance_path}")
    print(f"  Output   : {output_path}")

    cmd = [
        "java", "-cp", str(OUT_DIR),
        "GoogleHash2017",
        str(_rel(instance_path)),
        str(_rel(output_path)),
    ]
    print(f"  Running  : {' '.join(cmd)}")

    result = subprocess.run(cmd, cwd=str(PROJECT_ROOT), capture_output=True, text=True)

    # Always show stdout (solver progress / score info)
    if result.stdout:
        for line in result.stdout.strip().splitlines():
            print(f"    {line}")
    if result.stderr:
        for line in result.stderr.strip().splitlines():
            print(f"    [stderr] {line}")

    if result.returncode != 0:
        print(f"  ✗ Solver FAILED (exit code {result.returncode})")
        sys.exit(1)

    if not output_path.is_file():
        print(f"  ✗ Output file not created: {output_path}")
        sys.exit(1)

    print(f"  ✓ Solution saved: {output_path}")
    return output_path


def step_judge(instance_path: Path, output_path: Path) -> int | None:
    """Step 5 – Score the solution with the C++ judge."""
    _header("STEP 5: JUDGE")

    judge_exe = _JUDGE_EXE
    if not judge_exe.is_file():
        if _JUDGE_SRC.is_file():
            print("  Compiling judge...")
            r = subprocess.run(
                ["g++", "-O3", str(_JUDGE_SRC), "-o", str(judge_exe)],
                cwd=str(PROJECT_ROOT), capture_output=True, text=True,
            )
            if r.returncode != 0:
                print("  ✗ Judge compilation failed — skipping judge step.")
                if r.stderr:
                    print(r.stderr)
                return None
            print("  ✓ Judge compiled.")
        else:
            print(f"  ✗ Judge executable not found ({judge_exe.name}) — skipping.")
            return None

    cmd = [str(judge_exe), str(_rel(instance_path)), str(_rel(output_path))]
    print(f"  Running  : {' '.join(cmd)}")

    result = subprocess.run(cmd, cwd=str(PROJECT_ROOT), capture_output=True, text=True)
    if result.stdout:
        for line in result.stdout.strip().splitlines():
            print(f"    {line}")
    if result.stderr:
        for line in result.stderr.strip().splitlines():
            print(f"    [stderr] {line}")

    # Parse score from judge output (e.g. "Score = 12345")
    score: int | None = None
    for line in (result.stdout or "").splitlines():
        if "Score" in line and "=" in line:
            try:
                score = int(line.split("=")[1].strip())
            except ValueError:
                pass

    if score is not None:
        print(f"  ✓ Official Score: {score:,}")
    elif result.returncode != 0:
        print(f"  ✗ Judge exited with code {result.returncode}")

    return score


def step_dashboard() -> None:
    """Step 6 – Launch the web dashboard."""
    _header("STEP 6: DASHBOARD")
    print("  Starting web dashboard on http://127.0.0.1:8765")
    print("  Press Ctrl+C to stop.")
    print("=" * 60 + "\n")

    subprocess.run(
        [sys.executable, "-m", "tools.dashboard.dashboard"],
        cwd=str(PROJECT_ROOT),
    )


# ─────────────────────────────────────────────────────────────────────────────
# CLI
# ─────────────────────────────────────────────────────────────────────────────

def main() -> None:
    parser = argparse.ArgumentParser(
        description="Pipeline Hash Code 2017 : générer → valider → compiler → résoudre → juger → visualiser",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )

    mode = parser.add_mutually_exclusive_group()
    mode.add_argument(
        "--generator", "-g",
        choices=["dejaVu", "universalLambda"],
        help="Générer une nouvelle instance avec ce générateur",
    )
    mode.add_argument(
        "--instance", "-i",
        type=Path,
        help="Utiliser une instance .in existante",
    )
    mode.add_argument(
        "--dashboard-only",
        action="store_true",
        help="Lancer seulement le dashboard (sans résolution)",
    )

    parser.add_argument("--seed", "-s", type=int, default=42,
                        help="Graine aléatoire pour la génération (défaut: 42)")
    parser.add_argument("--dashboard", "-d", action="store_true",
                        help="Lancer le dashboard après la résolution")
    parser.add_argument("--skip-validate", action="store_true",
                        help="Passer l'étape de validation de l'instance")
    parser.add_argument("--skip-judge", action="store_true",
                        help="Passer l'étape du judge")

    args = parser.parse_args()

    print("╔══════════════════════════════════════════════════════════╗")
    print("║       Hash Code 2017 — Pipeline Complet                 ║")
    print("╚══════════════════════════════════════════════════════════╝")

    # ── Dashboard-only shortcut ───────────────────────────────────────────────
    if args.dashboard_only:
        step_dashboard()
        return

    # ── Determine instance path ───────────────────────────────────────────────
    if args.generator:
        instance_path = step_generate(args.generator, args.seed)
    elif args.instance:
        instance_path = args.instance.resolve()
        if not instance_path.is_file():
            # Try relative to project root
            instance_path = (PROJECT_ROOT / args.instance).resolve()
        if not instance_path.is_file():
            print(f"\n  ERROR: Instance file not found: {args.instance}")
            sys.exit(1)
        print(f"\n  Using existing instance: {instance_path}")
    else:
        parser.print_help()
        print("\n  Spécifiez --generator, --instance, ou --dashboard-only.")
        sys.exit(1)

    # ── Validate ──────────────────────────────────────────────────────────────
    if not args.skip_validate:
        if not step_validate(instance_path):
            print("\n  Pipeline arrêté : instance invalide.")
            sys.exit(1)

    # ── Compile ───────────────────────────────────────────────────────────────
    if not step_compile():
        print("\n  Pipeline arrêté : compilation échouée.")
        sys.exit(1)

    # ── Solve ─────────────────────────────────────────────────────────────────
    output_path = step_solve(instance_path)

    # ── Judge ─────────────────────────────────────────────────────────────────
    if not args.skip_judge:
        step_judge(instance_path, output_path)

    # ── Summary ───────────────────────────────────────────────────────────────
    print(f"\n{'=' * 60}")
    print("  PIPELINE TERMINÉ")
    print(f"{'=' * 60}")
    print(f"  Instance : {instance_path}")
    print(f"  Solution : {output_path}")
    print(f"{'=' * 60}")

    # ── Dashboard ─────────────────────────────────────────────────────────────
    if args.dashboard:
        step_dashboard()


if __name__ == "__main__":
    main()
