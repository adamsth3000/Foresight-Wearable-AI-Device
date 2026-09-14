"""Start the isolated local developer Vision service."""

from __future__ import annotations

import argparse
import logging

from .service import serve


def main() -> int:
    parser = argparse.ArgumentParser(description="Run local Foresight live Vision detection.")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8767)
    options = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    serve(host=options.host, port=options.port)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
