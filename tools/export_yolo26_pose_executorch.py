#!/usr/bin/env python3
"""Export YOLO26 pose weights to ExecuTorch and stage Android assets."""

from __future__ import annotations

import argparse
import shutil
from pathlib import Path

from ultralytics import YOLO


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Export yolo26n-pose.pt to ExecuTorch .pte format."
    )
    parser.add_argument(
        "--model",
        default="yolo26n-pose.pt",
        help="Path or Ultralytics model name to export.",
    )
    parser.add_argument(
        "--imgsz",
        type=int,
        default=640,
        help="Square input image size for export.",
    )
    parser.add_argument(
        "--device",
        default="cpu",
        help="Export device passed to Ultralytics.",
    )
    parser.add_argument(
        "--assets-dir",
        default="app/src/main/assets",
        help="Android assets directory where .pte and metadata are copied.",
    )
    parser.add_argument(
        "--output-name",
        default="yolo26n-pose",
        help="Base output asset name without extension.",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    project_root = Path.cwd()
    assets_dir = (project_root / args.assets_dir).resolve()
    assets_dir.mkdir(parents=True, exist_ok=True)

    model = YOLO(args.model)
    exported = Path(
        model.export(
            format="executorch",
            imgsz=args.imgsz,
            device=args.device,
        )
    )

    if exported.is_file() and exported.suffix == ".pte":
        pte_path = exported
        metadata_path = exported.with_name("metadata.yaml")
    else:
        pte_path = exported / "model.pte"
        metadata_path = exported / "metadata.yaml"

    if not pte_path.exists():
        raise FileNotFoundError(f"ExecuTorch model not found: {pte_path}")

    target_pte = assets_dir / f"{args.output_name}.pte"
    shutil.copy2(pte_path, target_pte)
    print(f"Copied {target_pte}")

    if metadata_path.exists():
        target_metadata = assets_dir / f"{args.output_name}-metadata.yaml"
        shutil.copy2(metadata_path, target_metadata)
        print(f"Copied {target_metadata}")


if __name__ == "__main__":
    main()
