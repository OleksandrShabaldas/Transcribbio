"""Entry point: `python -m transcribbio_ml [--host H] [--port P] [--token T]`.

Selects a free port when none is given, prints a single-line JSON handshake to
stdout (which the desktop app parses to learn the port + token), then serves.
"""

from __future__ import annotations

import argparse
import json
import os
import socket
import sys


def _free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def main() -> None:
    parser = argparse.ArgumentParser(prog="transcribbio_ml")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=0)
    parser.add_argument("--token", default=os.environ.get("TRANSCRIBBIO_SIDECAR_TOKEN"))
    args = parser.parse_args()

    if args.token:
        os.environ["TRANSCRIBBIO_SIDECAR_TOKEN"] = args.token

    port = args.port or _free_port()

    # Build the app AFTER the token is in the environment.
    import uvicorn

    from .main import create_app

    app = create_app()

    handshake = {"event": "sidecar_ready", "host": args.host, "port": port, "pid": os.getpid()}
    print("TRANSCRIBBIO_SIDECAR " + json.dumps(handshake), flush=True)
    sys.stdout.flush()

    uvicorn.run(app, host=args.host, port=port, log_level="info")


if __name__ == "__main__":
    main()
