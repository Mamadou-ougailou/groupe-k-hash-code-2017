"""Predicteur de plateau : ecart entre le meilleur et le deuxieme meilleur cache
de chaque endpoint, pondere par le volume de requetes.

C'est le cout potentiel de retrait, calcule sur la seule instance, avant meme
d'avoir lance un solveur. Si deux caches d'un endpoint ont la meme latence,
retirer une video de l'un ne coute rien tant que l'autre la contient : delta nul,
plateau, et un hill-climbing pur s'y arrete. Le repli ultime etant le
datacenter, un endpoint a un seul cache n'est jamais sur un plateau.

    python -m tools.stats.plateau instances/kittens.in
"""

from __future__ import annotations

import numpy as np

from .common import Instance, cli


def compute(inst: Instance) -> dict:
    gaps = np.full(inst.E, -1.0)       # -1 = endpoint sans cache
    best = np.full(inst.E, -1.0)

    for e in range(inst.E):
        lo, hi = inst.ep_start[e], inst.ep_start[e + 1]
        if lo == hi:
            continue
        lats = np.sort(inst.ep_lat[lo:hi])
        best[e] = lats[0]
        second = lats[1] if lats.size >= 2 else inst.dc[e]
        gaps[e] = min(second, inst.dc[e]) - lats[0]

    connected = gaps >= 0
    volume = np.zeros(inst.E, dtype=np.int64)
    np.add.at(volume, inst.req_endpoint, inst.req_count)

    total = int(volume.sum())
    vol_connected = int(volume[connected].sum())
    flat = connected & (gaps == 0)
    vol_flat = int(volume[flat].sum())

    useful = gaps[connected]
    return {
        "ecart_best2_best1_moyen": round(float(useful.mean()), 2) if useful.size else 0.0,
        "ecart_median": round(float(np.median(useful)), 2) if useful.size else 0.0,
        "ecart_p90": round(float(np.percentile(useful, 90)), 2) if useful.size else 0.0,
        "endpoints_a_ecart_nul": int(flat.sum()),
        "part_endpoints_a_ecart_nul": round(float(flat.sum() / max(connected.sum(), 1)), 4),
        "part_requetes_sur_plateau": round(vol_flat / total, 4) if total else 0.0,
        "part_requetes_connectees": round(vol_connected / total, 4) if total else 0.0,
        "endpoints_sans_cache": int((~connected).sum()),
    }


if __name__ == "__main__":
    cli("plateau", compute)
