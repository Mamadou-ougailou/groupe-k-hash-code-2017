"""Lecture rapide et structures partagees pour les statistiques.

Independant de tools/dashboard et de src/ : ne depend que des formats de fichier.
Le lecteur de tools/dashboard/hc_format.py lit ligne par ligne en Python pur, ce
qui coute des dizaines de secondes sur les instances a 1 M de requetes presentes
dans le depot. Ici tout est vectorise.
"""

from __future__ import annotations

import json
import sys
from dataclasses import dataclass
from pathlib import Path

import numpy as np


@dataclass
class Instance:
    V: int
    E: int
    R: int
    C: int
    X: int
    sizes: np.ndarray        # (V,)
    dc: np.ndarray           # (E,)
    degrees: np.ndarray      # (E,)
    ep_start: np.ndarray     # (E+1,) decoupage CSR des connexions
    ep_cache: np.ndarray     # (sum degres,)
    ep_lat: np.ndarray       # (sum degres,)
    req_video: np.ndarray    # (R,)
    req_endpoint: np.ndarray # (R,)
    req_count: np.ndarray    # (R,)

    @property
    def total_requests(self) -> int:
        return int(self.req_count.sum())

    def latency_matrix_lookup(self):
        """Table (endpoint, cache) -> latence, -1 si non connecte. Couteuse en
        memoire pour E*C grand ; a n'utiliser que si E*C reste raisonnable."""
        lat = np.full(self.E * self.C, -1, dtype=np.int64)
        rows = np.repeat(np.arange(self.E), self.degrees)
        lat[rows * self.C + self.ep_cache] = self.ep_lat
        return lat


def read_instance(path: str | Path) -> Instance:
    tokens = np.array(Path(path).read_text().split(), dtype=np.int64)
    V, E, R, C, X = (int(t) for t in tokens[:5])

    i = 5
    sizes = tokens[i:i + V]
    i += V

    dc = np.empty(E, dtype=np.int64)
    degrees = np.empty(E, dtype=np.int64)
    cache_chunks = []
    lat_chunks = []
    for e in range(E):
        dc[e] = tokens[i]
        k = int(tokens[i + 1])
        degrees[e] = k
        i += 2
        if k:
            block = tokens[i:i + 2 * k].reshape(k, 2)
            cache_chunks.append(block[:, 0])
            lat_chunks.append(block[:, 1])
            i += 2 * k

    ep_cache = np.concatenate(cache_chunks) if cache_chunks else np.empty(0, dtype=np.int64)
    ep_lat = np.concatenate(lat_chunks) if lat_chunks else np.empty(0, dtype=np.int64)
    ep_start = np.concatenate([[0], np.cumsum(degrees)])

    reqs = tokens[i:i + 3 * R].reshape(R, 3)

    return Instance(
        V=V, E=E, R=R, C=C, X=X,
        sizes=sizes, dc=dc, degrees=degrees,
        ep_start=ep_start, ep_cache=ep_cache, ep_lat=ep_lat,
        req_video=reqs[:, 0].copy(),
        req_endpoint=reqs[:, 1].copy(),
        req_count=reqs[:, 2].copy(),
    )


def read_solution(path: str | Path, inst: Instance) -> np.ndarray:
    """Matrice booleenne (C, V) : has[c, v] vaut True si le cache c contient v."""
    tokens = Path(path).read_text().split()
    has = np.zeros((inst.C, inst.V), dtype=bool)
    if not tokens:
        return has

    lines = [ln.split() for ln in Path(path).read_text().splitlines() if ln.strip()]
    n = int(lines[0][0])
    for line in lines[1:1 + n]:
        cache_id = int(line[0])
        if not 0 <= cache_id < inst.C:
            raise ValueError(f"id de cache hors bornes dans la solution : {cache_id}")
        vids = np.array([int(v) for v in line[1:]], dtype=np.int64)
        if vids.size:
            has[cache_id, vids] = True
    return has


def best_latency_per_request(inst: Instance, has: np.ndarray | None):
    """Pour chaque requete : meilleure latence atteignable (datacenter inclus),
    et le cache qui la fournit (-1 pour le datacenter).

    has=None donne la borne superieure : toute video est supposee presente partout.
    """
    best = inst.dc[inst.req_endpoint].astype(np.int64).copy()
    arg = np.full(inst.R, -1, dtype=np.int64)

    order = np.argsort(inst.req_endpoint, kind="stable")
    sorted_ep = inst.req_endpoint[order]
    bounds = np.searchsorted(sorted_ep, np.arange(inst.E + 1))

    for e in range(inst.E):
        lo, hi = bounds[e], bounds[e + 1]
        if lo == hi:
            continue
        rows = order[lo:hi]
        for i in range(inst.ep_start[e], inst.ep_start[e + 1]):
            c, l = int(inst.ep_cache[i]), int(inst.ep_lat[i])
            if has is None:
                better = l < best[rows]
            else:
                better = (l < best[rows]) & has[c, inst.req_video[rows]]
            if better.any():
                target = rows[better]
                best[target] = l
                arg[target] = c
    return best, arg


def emit(metric: str, path: str | Path, payload: dict) -> None:
    """Sortie normalisee, commune a toutes les metriques."""
    print(json.dumps({
        "metric": metric,
        "instance": Path(path).name,
        "values": payload,
    }, indent=2, ensure_ascii=False))


def cli(metric: str, compute):
    """Rend un module de metrique executable : python -m tools.stats.<module> fichier.in"""
    if len(sys.argv) < 2:
        print(f"usage: python -m tools.stats.{metric} <instance.in> [solution.out]")
        raise SystemExit(1)
    path = sys.argv[1]
    inst = read_instance(path)
    if len(sys.argv) > 2:
        emit(metric, path, compute(inst, read_solution(sys.argv[2], inst)))
    else:
        emit(metric, path, compute(inst))
