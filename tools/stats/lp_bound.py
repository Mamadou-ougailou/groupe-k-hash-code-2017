"""Borne superieure par relaxation lineaire du probleme complet.

Contrairement a tools/stats/bound.py, cette relaxation ne surcompte pas : une
requete ne peut etre servie qu'une fois au total, contrainte explicite du
programme.

    maximiser   somme_p somme_c  y[p,c] * n_p * (L_D(e_p) - L(e_p,c))
    sous        somme_v taille_v * x[c,v] <= X          pour chaque cache c
                y[p,c] <= x[c, v_p]                     liaison
                somme_c y[p,c] <= 1                     une requete servie une fois
                0 <= x, y <= 1

x[c,v] est la fraction de la video v placee dans le cache c, y[p,c] la fraction
de la demande p servie depuis c. L'optimum entier est un cas particulier, donc
l'optimum fractionnaire le majore : c'est une borne valide, et bien plus serree
que le plafond sans capacite.

Le nombre de variables croit vite. --max-vars protege contre les instances ou
le solveur exploserait la memoire.

    python -m tools.stats.lp_bound instances/me_at_the_zoo.in
"""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import numpy as np
from scipy.optimize import linprog
from scipy.sparse import coo_matrix

from . import ceiling
from .common import Instance, read_instance


def build(inst: Instance):
    """Agrege les requetes, puis indexe les variables x et y."""
    pair_id = inst.req_video * inst.E + inst.req_endpoint
    uniq, inverse = np.unique(pair_id, return_inverse=True)
    counts = np.zeros(uniq.size, dtype=np.int64)
    np.add.at(counts, inverse, inst.req_count)

    pair_video = (uniq // inst.E).astype(np.int64)
    pair_endpoint = (uniq % inst.E).astype(np.int64)

    # Videos demandees par chaque endpoint, pour restreindre les variables x.
    order = np.argsort(pair_endpoint, kind="stable")
    ep_bounds = np.searchsorted(pair_endpoint[order], np.arange(inst.E + 1))

    x_index: dict[tuple[int, int], int] = {}
    y_rows: list[tuple[int, int, int, int]] = []   # (pair, cache, latence, index x)

    for p in range(uniq.size):
        e = int(pair_endpoint[p])
        v = int(pair_video[p])
        dc = int(inst.dc[e])
        for i in range(inst.ep_start[e], inst.ep_start[e + 1]):
            c = int(inst.ep_cache[i])
            lat = int(inst.ep_lat[i])
            if lat >= dc:
                continue
            key = (c, v)
            xi = x_index.get(key)
            if xi is None:
                xi = len(x_index)
                x_index[key] = xi
            y_rows.append((p, c, dc - lat, xi))

    return uniq, counts, pair_video, pair_endpoint, x_index, y_rows, ep_bounds, order


def estimate_size(inst: Instance) -> tuple[int, int]:
    """Majore le nombre de variables SANS rien construire.

    La verification doit preceder la construction : sur kittens, batir les
    structures avant de verifier consomme 8,8 Go pour finalement refuser.
    """
    pair_id = inst.req_video * inst.E + inst.req_endpoint
    uniq = np.unique(pair_id)
    pair_endpoint = (uniq % inst.E).astype(np.int64)
    ny = int(inst.degrees[pair_endpoint].sum())
    nx = min(inst.C * inst.V, ny)
    return nx, ny


def compute(inst: Instance, max_vars: int = 4_000_000, verbose: bool = False) -> dict:
    t0 = time.perf_counter()

    nx_est, ny_est = estimate_size(inst)
    if nx_est + ny_est > max_vars:
        return {
            "resolu": False,
            "raison": (f"environ {nx_est + ny_est:,} variables (x<={nx_est:,}, y={ny_est:,}) "
                       f"au-dela de la limite {max_vars:,} — rien n'a ete construit"),
            "borne_lp": None,
        }

    uniq, counts, pair_video, pair_endpoint, x_index, y_rows, _, _ = build(inst)

    nx = len(x_index)
    ny = len(y_rows)
    if nx + ny > max_vars:
        return {
            "resolu": False,
            "raison": f"{nx + ny} variables (x={nx}, y={ny}) au-dela de la limite {max_vars}",
            "borne_lp": None,
        }

    sizes = inst.sizes.astype(np.float64)

    # Objectif : on minimise l'oppose du gain.
    obj = np.zeros(nx + ny, dtype=np.float64)
    for j, (p, _c, gain, _xi) in enumerate(y_rows):
        obj[nx + j] = -float(counts[p]) * gain

    rows: list[int] = []
    cols: list[int] = []
    vals: list[float] = []
    rhs: list[float] = []
    r = 0

    # Capacite de chaque cache.
    cache_rows = {}
    for (c, v), xi in x_index.items():
        cache_rows.setdefault(c, []).append((xi, sizes[v]))
    for c, entries in cache_rows.items():
        for xi, w in entries:
            rows.append(r); cols.append(xi); vals.append(w)
        rhs.append(float(inst.X))
        r += 1

    # Liaison y[p,c] <= x[c,v_p].
    for j, (_p, _c, _gain, xi) in enumerate(y_rows):
        rows.append(r); cols.append(nx + j); vals.append(1.0)
        rows.append(r); cols.append(xi); vals.append(-1.0)
        rhs.append(0.0)
        r += 1

    # Une requete servie au plus une fois.
    by_pair: dict[int, list[int]] = {}
    for j, (p, _c, _g, _xi) in enumerate(y_rows):
        by_pair.setdefault(p, []).append(j)
    for p, js in by_pair.items():
        for j in js:
            rows.append(r); cols.append(nx + j); vals.append(1.0)
        rhs.append(1.0)
        r += 1

    A = coo_matrix((vals, (rows, cols)), shape=(r, nx + ny)).tocsr()
    built = time.perf_counter()

    res = linprog(obj, A_ub=A, b_ub=np.array(rhs), bounds=(0.0, 1.0),
                  method="highs")
    if not res.success:
        return {"resolu": False, "raison": res.message, "borne_lp": None}

    total = inst.total_requests
    lp_value = -res.fun
    lp_score = int(lp_value * 1000 // total)
    loose = ceiling.compute(inst)["plafond_score"]

    return {
        "resolu": True,
        "borne_lp": lp_score,
        "borne_lache": loose,
        "resserrement": round(1 - lp_score / loose, 4) if loose else 0.0,
        "variables": nx + ny,
        "contraintes": r,
        "secondes_construction": round(built - t0, 2),
        "secondes_resolution": round(time.perf_counter() - built, 2),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Borne superieure par relaxation lineaire")
    parser.add_argument("instances", nargs="+", type=Path)
    parser.add_argument("--max-vars", type=int, default=4_000_000)
    parser.add_argument("--score", type=int, action="append", default=None,
                        help="score obtenu, pour afficher la part de la borne LP")
    args = parser.parse_args()

    for i, path in enumerate(args.instances):
        inst = read_instance(path)
        print(f"=== {path.name} ===", flush=True)
        out = compute(inst, args.max_vars)
        if not out["resolu"]:
            print(f"  non resolu : {out['raison']}")
            continue
        print(f"  borne LP      : {out['borne_lp']:,}")
        print(f"  borne lache   : {out['borne_lache']:,}")
        print(f"  resserrement  : {100 * out['resserrement']:.1f}%")
        print(f"  {out['variables']:,} variables, {out['contraintes']:,} contraintes, "
              f"{out['secondes_resolution']}s de resolution")
        if args.score and i < len(args.score):
            print(f"  score obtenu  : {args.score[i]:,} = "
                  f"{100 * args.score[i] / out['borne_lp']:.2f}% de la borne LP")
        print(json.dumps(out, ensure_ascii=False))


if __name__ == "__main__":
    main()
