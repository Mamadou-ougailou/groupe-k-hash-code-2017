"""Forme de l'instance : granularite du sac a dos, pression de capacite,
dispersion des tailles, correlation taille/popularite, lignes dupliquees.

Ces mesures servent a elaguer la recherche de parametres : elles disent quels
reglages valent la peine d'etre cherches sur cette instance, et lesquels n'ont
mecaniquement aucun effet.

    python -m tools.stats.granularity instances/kittens.in
"""

from __future__ import annotations

import numpy as np

from .common import Instance, cli


def _spearman(a: np.ndarray, b: np.ndarray) -> float:
    if a.size < 2:
        return 0.0
    ra = np.argsort(np.argsort(a)).astype(np.float64)
    rb = np.argsort(np.argsort(b)).astype(np.float64)
    ra -= ra.mean()
    rb -= rb.mean()
    denom = np.sqrt((ra * ra).sum() * (rb * rb).sum())
    return float((ra * rb).sum() / denom) if denom > 0 else 0.0


def compute(inst: Instance) -> dict:
    sizes = inst.sizes.astype(np.float64)
    mean_size = float(sizes.mean())
    cv = float(sizes.std() / mean_size) if mean_size > 0 else 0.0

    volume = np.zeros(inst.V, dtype=np.int64)
    np.add.at(volume, inst.req_video, inst.req_count)
    requested = volume > 0
    useful_volume = int(inst.sizes[requested].sum())

    pair_ids = inst.req_video * inst.E + inst.req_endpoint
    distinct = int(np.unique(pair_ids).size)
    duplicate_lines = int(inst.R - distinct)

    corr = _spearman(sizes[requested], volume[requested].astype(np.float64)) if requested.any() else 0.0

    return {
        "videos_par_cache_moyen": round(inst.X / mean_size, 2) if mean_size else 0.0,
        "videos_par_cache_median": round(inst.X / float(np.median(sizes)), 2) if np.median(sizes) else 0.0,
        "plus_grande_video_par_cache": round(inst.X / float(sizes.max()), 2) if sizes.max() else 0.0,
        "taille_moyenne": round(mean_size, 2),
        "cv_tailles": round(cv, 4),
        "pression_capacite": round(inst.C * inst.X / max(useful_volume, 1), 4),
        "volume_videos_demandees_Mo": useful_volume,
        "videos_demandees": int(requested.sum()),
        "correlation_taille_popularite": round(corr, 4),
        "lignes_dupliquees": duplicate_lines,
        "taux_duplication": round(duplicate_lines / inst.R, 4) if inst.R else 0.0,
        "couples_distincts": distinct,
    }


if __name__ == "__main__":
    cli("granularity", compute)
