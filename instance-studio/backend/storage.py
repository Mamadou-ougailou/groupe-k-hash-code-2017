"""On-disk store for generated instances: one .in file plus one .json sidecar."""

from __future__ import annotations

import json
import os
import re
import secrets
import threading
import time

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
INSTANCE_DIR = os.path.join(BASE_DIR, "data", "instances")

MAX_INSTANCES = 200
MAX_TOTAL_BYTES = 3 * 1024 ** 3

ID_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{6}$")
_SLUG_RE = re.compile(r"[^a-zA-Z0-9_-]+")

_lock = threading.Lock()


class StorageFull(RuntimeError):
    pass


def _ensure_dir():
    os.makedirs(INSTANCE_DIR, exist_ok=True)


def slugify(name: str, fallback: str = "instance") -> str:
    slug = _SLUG_RE.sub("-", (name or "").strip())[:40].strip("-")
    return slug or fallback


def new_id() -> str:
    return f"{int(time.time()):08x}-{secrets.token_hex(3)}"


def instance_path(instance_id: str) -> str:
    if not ID_RE.match(instance_id):
        raise ValueError("identifiant invalide")
    return os.path.join(INSTANCE_DIR, f"{instance_id}.in")


def _meta_path(instance_id: str) -> str:
    if not ID_RE.match(instance_id):
        raise ValueError("identifiant invalide")
    return os.path.join(INSTANCE_DIR, f"{instance_id}.json")


def usage() -> tuple[int, int]:
    _ensure_dir()
    count = 0
    total = 0
    for entry in os.scandir(INSTANCE_DIR):
        if entry.name.endswith(".in"):
            count += 1
            total += entry.stat().st_size
    return count, total


def reserve() -> tuple[str, str]:
    """Pick a free id and check the store still has room. Returns (id, path)."""
    with _lock:
        _ensure_dir()
        count, total = usage()
        if count >= MAX_INSTANCES:
            raise StorageFull(
                f"Le serveur stocke déjà {count} instances (maximum {MAX_INSTANCES}). "
                "Supprime des fichiers dans data/instances pour libérer de la place."
            )
        if total >= MAX_TOTAL_BYTES:
            raise StorageFull("Le quota disque du serveur est atteint.")
        instance_id = new_id()
        return instance_id, instance_path(instance_id)


def commit(instance_id: str, name: str, params: dict, stats: dict) -> dict:
    path = instance_path(instance_id)
    meta = {
        "id": instance_id,
        "name": slugify(name, f"instance-{instance_id}"),
        "created_at": time.time(),
        "bytes": os.path.getsize(path),
        "params": params,
        "stats": stats,
    }
    with open(_meta_path(instance_id), "w") as f:
        json.dump(meta, f, indent=2)
    return meta


def discard(instance_id: str) -> None:
    for path in (instance_path(instance_id), _meta_path(instance_id)):
        try:
            os.remove(path)
        except FileNotFoundError:
            pass


def get(instance_id: str) -> dict | None:
    try:
        with open(_meta_path(instance_id)) as f:
            return json.load(f)
    except (FileNotFoundError, ValueError, OSError):
        return None


def list_all() -> list[dict]:
    _ensure_dir()
    out = []
    for entry in os.scandir(INSTANCE_DIR):
        if not entry.name.endswith(".json"):
            continue
        try:
            with open(entry.path) as f:
                out.append(json.load(f))
        except (ValueError, OSError):
            continue
    out.sort(key=lambda m: m.get("created_at", 0), reverse=True)
    return out
