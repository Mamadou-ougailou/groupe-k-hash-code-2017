"""Local HTTP server for Instance Studio. Standard library only (plus numpy).

Run:  python3 backend/server.py [port]
"""

from __future__ import annotations

import json
import os
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import storage
from generator import ParamError, generate, parse_params, params_to_dict
from validator import validate_text

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FRONTEND_DIR = os.path.join(BASE_DIR, "frontend")

MAX_JSON_BODY = 64 * 1024
MAX_UPLOAD_BODY = 64 * 1024 * 1024

# The tunnel makes this reachable by anyone with the URL; cap concurrent CPU work.
_generation_slots = threading.BoundedSemaphore(2)

STATIC = {
    "/": ("index.html", "text/html; charset=utf-8"),
    "/index.html": ("index.html", "text/html; charset=utf-8"),
    "/style.css": ("style.css", "text/css; charset=utf-8"),
    "/app.js": ("app.js", "application/javascript; charset=utf-8"),
}


class Handler(BaseHTTPRequestHandler):
    server_version = "InstanceStudio/1.0"
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))

    # ---- helpers -------------------------------------------------------

    def _send(self, code: int, body: bytes, ctype: str, extra: dict | None = None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Accept-Ranges", "none")
        self.send_header("X-Content-Type-Options", "nosniff")
        for key, value in (extra or {}).items():
            self.send_header(key, value)
        self.end_headers()
        if not getattr(self, "_head_only", False):
            self.wfile.write(body)

    def _json(self, code: int, payload: dict):
        self._send(code, json.dumps(payload).encode("utf-8"),
                   "application/json; charset=utf-8")

    def _error(self, code: int, message: str):
        self._json(code, {"error": message})

    def _read_body(self, limit: int) -> bytes | None:
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self._error(400, "Content-Length invalide")
            return None
        if length <= 0:
            self._error(400, "Corps de requête vide")
            return None
        if length > limit:
            self._error(413, f"Corps trop volumineux (max {limit // (1024 * 1024)} Mo)")
            return None
        return self.rfile.read(length)

    # ---- routing -------------------------------------------------------

    def do_HEAD(self):
        # Browsers probe with HEAD before some downloads; without this the base
        # class answers 501 and the download never starts.
        self._head_only = True
        try:
            self.do_GET()
        finally:
            self._head_only = False

    def do_GET(self):
        path = self.path.split("?", 1)[0]

        if path in STATIC:
            filename, ctype = STATIC[path]
            try:
                with open(os.path.join(FRONTEND_DIR, filename), "rb") as f:
                    self._send(200, f.read(), ctype)
            except OSError:
                self._error(404, "fichier introuvable")
            return

        if path == "/api/instances":
            self._json(200, {"instances": storage.list_all()})
            return

        if path.startswith("/api/instances/") and path.endswith("/download"):
            self._download(path[len("/api/instances/"):-len("/download")])
            return

        self._error(404, "route inconnue")

    def do_POST(self):
        path = self.path.split("?", 1)[0]
        if path == "/api/generate":
            self._generate()
        elif path == "/api/validate":
            self._validate()
        else:
            self._error(404, "route inconnue")

    # ---- endpoints -----------------------------------------------------

    def _download(self, instance_id: str):
        meta = storage.get(instance_id)
        if meta is None:
            self._error(404, "instance introuvable")
            return
        try:
            with open(storage.instance_path(instance_id), "rb") as f:
                payload = f.read()
        except (OSError, ValueError):
            self._error(404, "fichier introuvable")
            return
        name = storage.slugify(meta.get("name", ""), instance_id)
        self._send(200, payload, "text/plain; charset=utf-8",
                   {"Content-Disposition": f'attachment; filename="{name}.in"'})

    def _generate(self):
        body = self._read_body(MAX_JSON_BODY)
        if body is None:
            return
        try:
            raw = json.loads(body.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            self._error(400, "JSON invalide")
            return
        if not isinstance(raw, dict):
            self._error(400, "objet JSON attendu")
            return

        try:
            params = parse_params(raw)
        except ParamError as exc:
            self._error(400, str(exc))
            return

        if not _generation_slots.acquire(blocking=False):
            self._error(429, "Serveur occupé: deux générations sont déjà en cours. Réessaie dans un instant.")
            return

        instance_id = None
        try:
            instance_id, path = storage.reserve()
            stats = generate(params, path)
            meta = storage.commit(instance_id, raw.get("name", ""),
                                  params_to_dict(params), stats)
            self._json(200, {"instance": meta})
        except storage.StorageFull as exc:
            self._error(507, str(exc))
        except ParamError as exc:
            if instance_id:
                storage.discard(instance_id)
            self._error(400, str(exc))
        except Exception as exc:
            if instance_id:
                storage.discard(instance_id)
            self._error(500, f"échec de la génération: {exc}")
        finally:
            _generation_slots.release()

    def _validate(self):
        body = self._read_body(MAX_UPLOAD_BODY)
        if body is None:
            return
        text = body.decode("utf-8", errors="replace")
        try:
            self._json(200, validate_text(text))
        except Exception as exc:
            self._error(500, f"échec de la validation: {exc}")


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else int(os.environ.get("PORT", "8000"))
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print(f"Instance Studio sur http://127.0.0.1:{port}")
    print("Tunnel public:  ./tunnel.sh")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\narrêt")
        server.server_close()


if __name__ == "__main__":
    main()
