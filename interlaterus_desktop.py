#!/usr/bin/env python3
"""
InterlaterusDesktop — vertical integration orchestrator.

Stack (Image #1 / nonlineari arch):
  GravityDesktop (download) → ffmpeg/ffprobe (media data)
  → Blockcode_NLS_Records (tesseract mint) → NLS-Record catalog
  → optional NCOMM export manifest

Usage:
  python3 interlaterus_desktop.py mint --file ~/Downloads/direct-h264-xxx.mp4 --url URL --title TITLE
  python3 interlaterus_desktop.py watch
  python3 interlaterus_desktop.py list
  python3 interlaterus_desktop.py export
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from blockcode_nft_client import get_blockcode_nft_client

XDG_CONFIG = Path.home() / ".config" / "interlaterus-desktop"
XDG_DATA = Path.home() / ".local" / "share" / "interlaterus-desktop"
DB_PATH = XDG_DATA / "blockcode_registry.db"
CATALOG_PATH = XDG_DATA / "nls_media_catalog.json"
MANIFEST_PATH = XDG_DATA / "ncomm_export_manifest.json"
CHARACTER_CATALOG_PATH = XDG_DATA / "character_catalog.json"
CHARACTER_ASSET_DIR = XDG_DATA / "characters"
DOWNLOADS = Path.home() / "Downloads"
WATCH_PATTERNS = (
    re.compile(r"^direct-h264-.+\.mp4$"),
    re.compile(r"^direct-gif-.+\.gif$"),
    re.compile(r"^direct-h264-rot\d+-.+\.mp4$"),
)
# Character Designer — Form is Feed from Source
# Acquisition: hand-drawn still  OR  character captured in video
FORM_MODES = ("hand_drawn", "video_capture")
# Dimensional space of the form itself (orthogonal to acquisition)
FORM_SPACES = ("2d_graphics", "3d_form")
# Mesh / 3D asset suffixes
MESH_3D_SUFFIXES = {".obj", ".gltf", ".glb", ".fbx", ".stl", ".ply", ".usd", ".usda", ".usdc", ".blend"}
HAND_DRAWN_SUFFIXES = {".png", ".jpg", ".jpeg", ".webp", ".gif", ".svg", ".bmp", ".tif", ".tiff"}
VIDEO_SUFFIXES = {".mp4", ".webm", ".mkv", ".mov", ".avi", ".m4v"}

# Designer-philosopher / game-theory lineage (Tractatus-style nested form)
# Wittgenstein: world = facts; picture = logical form of representation;
# Nested numbers 1 → 1.1 → 2.1 … mirror Sources (1)(2)(3)(4) + (N).
THEORY_LINEAGE_DEFAULT = {
    "tractatus": {
        "title": "Tractatus Logico-Philosophicus",
        "author": "Ludwig Wittgenstein",
        "core": "Nested propositions; picture theory; limits of language = limits of world",
        "map": {
            "1": "world / case — totality of form inputs",
            "2": "atomic facts — design particles (Source 1)",
            "3": "logical picture — 2D graphics or 3D form projection",
            "4": "thought / significant proposition — character identity",
            "5": "truth-functions — pattern_code + vertex operations",
            "6": "general form of proposition — Form is Feed from Source",
            "7": "whereof one cannot speak — silence / off-catalog",
        },
    },
    "game_designers": "ludic systems, spatial identity, playable form",
    "designer_philosophers": "form-follows-function; observer-fed character",
}

SPATIAL = ["AB", "AABB", "ABAB", "ABBA"]
RHYTHM = ["2:4", "3:3", "4:4", "5:3"]
STRUCTURE = ["P&B", "P|B", "P→B", "P←B"]
TRANSFORM = ["F1", "F2", "F3", "F4"]
OWNER_DEFAULT = "AABB.3:3.P|B.F2"
TESSERACT_VERTICES = [
    [x, y, z, t]
    for x in (0, 1)
    for y in (0, 1)
    for z in (0, 1)
    for t in (0, 1)
]


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def ensure_dirs() -> None:
    XDG_CONFIG.mkdir(parents=True, exist_ok=True)
    XDG_DATA.mkdir(parents=True, exist_ok=True)
    CHARACTER_ASSET_DIR.mkdir(parents=True, exist_ok=True)


def ffmpeg_path() -> str:
    return os.environ.get("FFMPEG_PATH", shutil.which("ffmpeg") or "/usr/bin/ffmpeg")


def ffprobe_path() -> str:
    return os.environ.get("FFPROBE_PATH", shutil.which("ffprobe") or "/usr/bin/ffprobe")


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def run_json(cmd: List[str]) -> Dict[str, Any]:
    try:
        out = subprocess.check_output(cmd, stderr=subprocess.DEVNULL, text=True)
        return json.loads(out)
    except Exception:
        return {}


def probe_media(path: Path) -> Dict[str, Any]:
    data = run_json(
        [
            ffprobe_path(),
            "-v",
            "quiet",
            "-print_format",
            "json",
            "-show_format",
            "-show_streams",
            str(path),
        ]
    )
    fmt = data.get("format") or {}
    streams = data.get("streams") or []
    video = next((s for s in streams if s.get("codec_type") == "video"), {})
    audio = next((s for s in streams if s.get("codec_type") == "audio"), {})
    duration = float(fmt.get("duration") or video.get("duration") or 0)
    size = int(fmt.get("size") or path.stat().st_size)
    bitrate = int(fmt.get("bit_rate") or 0)
    width = int(video.get("width") or 0)
    height = int(video.get("height") or 0)
    return {
        "path": str(path),
        "filename": path.name,
        "duration_sec": duration,
        "size_bytes": size,
        "bitrate": bitrate,
        "width": width,
        "height": height,
        "vcodec": video.get("codec_name", ""),
        "acodec": audio.get("codec_name", ""),
        "format": fmt.get("format_name", path.suffix.lstrip(".")),
        "sha256": sha256_file(path),
    }


def audio_vector_from_probe(probe: Dict[str, Any]) -> List[float]:
    """4D metadata vector for blockcode (duration, size norm, bitrate norm, aspect)."""
    dur = max(probe.get("duration_sec") or 0, 0.001)
    size_mb = (probe.get("size_bytes") or 0) / (1024 * 1024)
    br_mbps = (probe.get("bitrate") or 0) / 1_000_000
    w, h = probe.get("width") or 1, probe.get("height") or 1
    aspect = w / max(h, 1)
    return [
        round(min(dur / 600.0, 2.0), 4),
        round(min(size_mb / 500.0, 2.0), 4),
        round(min(br_mbps / 10.0, 2.0), 4),
        round(min(aspect, 2.0), 4),
    ]


def pattern_from_digest(digest: str) -> str:
    """Deterministic blockcode pattern from sha256 hex."""
    i = int(digest[:8], 16)
    return ".".join(
        [
            SPATIAL[i % len(SPATIAL)],
            RHYTHM[(i >> 4) % len(RHYTHM)],
            STRUCTURE[(i >> 8) % len(STRUCTURE)],
            TRANSFORM[(i >> 12) % len(TRANSFORM)],
        ]
    )


# Golden-ratio inverse — Fibonacci stage scale (Character Designer fold.x)
PHI = (1.0 + 5.0 ** 0.5) / 2.0
PHI_INV = PHI - 1.0  # ≈ 0.618 — angle = φ⁻¹ on notebook diagrams


def vertex_from_digest(digest: str) -> List[int]:
    """Character Designer: Form is Feed from Source — map content sha256 → tesseract vertex.

    Uses four independent low bits from the digest so POS cells distribute ~uniformly
    across the 16 tesseract corners instead of collapsing to [0,0,0,0].
    Source channels (1)(2)(3)(4) → dimensions x,y,z,t; optional (N) is multi-dose.
    """
    d = (digest or "").strip().lower().replace("0x", "")
    if len(d) < 8:
        d = hashlib.sha256((digest or "0").encode("utf-8")).hexdigest()
    # 4 hex chars → 4 independent bits (one per dimension)
    return [(int(d[i], 16) & 1) for i in range(4)]


def vertex_load(conn: sqlite3.Connection) -> Dict[Tuple[int, int, int, int], int]:
    """Occupancy counts per tesseract vertex (multi-tenant cells allowed)."""
    counts: Dict[Tuple[int, int, int, int], int] = {
        tuple(v): 0 for v in TESSERACT_VERTICES  # type: ignore[misc]
    }
    for r in conn.execute(
        "SELECT vertex_x, vertex_y, vertex_z, vertex_t FROM blockcode_nft_records"
    ):
        key = (int(r[0]), int(r[1]), int(r[2]), int(r[3]))
        counts[key] = counts.get(key, 0) + 1
    return counts


def get_db() -> sqlite3.Connection:
    ensure_dirs()
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS blockcode_nft_records (
            pattern_code TEXT PRIMARY KEY,
            vertex_x INTEGER, vertex_y INTEGER, vertex_z INTEGER, vertex_t INTEGER,
            owner_pattern TEXT,
            metadata_json TEXT,
            media_path TEXT,
            source_url TEXT,
            sha256 TEXT UNIQUE,
            created_at TEXT
        )
        """
    )
    conn.commit()
    return conn


def load_client_from_db(conn: sqlite3.Connection):
    client = get_blockcode_nft_client("interlaterus-main", [0, 0, 0, 0])
    rows = conn.execute("SELECT * FROM blockcode_nft_records").fetchall()
    for r in rows:
        meta = json.loads(r["metadata_json"] or "{}")
        pattern = r["pattern_code"]
        vertex = [r["vertex_x"], r["vertex_y"], r["vertex_z"], r["vertex_t"]]
        if pattern not in client.nft_registry:
            client.nft_registry[pattern] = {
                "pattern_code": pattern,
                "vertex": vertex,
                "owner_pattern": r["owner_pattern"],
                "metadata": meta,
                "metadata_vector": meta.get("audio_vector", [0, 0, 0, 0]),
                "created_at": r["created_at"],
                "transfer_history": [],
            }
    return client


def occupied_vertices(conn: sqlite3.Connection) -> set:
    occ = set()
    for r in conn.execute(
        "SELECT vertex_x, vertex_y, vertex_z, vertex_t FROM blockcode_nft_records"
    ):
        occ.add((r[0], r[1], r[2], r[3]))
    return occ


def pick_vertex(conn: sqlite3.Connection, sha256: str = "") -> List[int]:
    """Place a mint on the tesseract.

    Primary (Character Designer — Form is Feed from Source):
      content sha256 → deterministic 4-bit vertex. Multi-tenant cells are OK.

    Fallback when no digest: least-loaded cell (never collapse to always [0,0,0,0]
    after the 16 exclusive slots fill — that was the pipeline bug).
    """
    if sha256:
        return vertex_from_digest(sha256)

    loads = vertex_load(conn)
    # prefer empty exclusive slot first
    for v in TESSERACT_VERTICES:
        t = tuple(v)
        if loads.get(t, 0) == 0:
            return list(v)
    # all cells occupied: least-loaded (stable order for ties)
    best = min(
        TESSERACT_VERTICES,
        key=lambda v: (loads.get(tuple(v), 0), TESSERACT_VERTICES.index(v)),
    )
    return list(best)


def append_catalog(entry: Dict[str, Any]) -> None:
    catalog: List[Dict[str, Any]] = []
    if CATALOG_PATH.exists():
        try:
            catalog = json.loads(CATALOG_PATH.read_text())
        except Exception:
            catalog = []
    catalog.append(entry)
    CATALOG_PATH.write_text(json.dumps(catalog, indent=2))


def write_sidecar(media: Path, record: Dict[str, Any]) -> Path:
    sidecar = media.with_suffix(media.suffix + ".interlaterus.json")
    sidecar.write_text(json.dumps(record, indent=2))
    return sidecar


def mint_media_file(
    path: Path,
    source_url: str = "",
    title: str = "",
    owner_pattern: str = OWNER_DEFAULT,
) -> Dict[str, Any]:
    path = path.expanduser().resolve()
    if not path.is_file():
        raise FileNotFoundError(path)

    probe = probe_media(path)
    conn = get_db()
    existing = conn.execute(
        "SELECT pattern_code, vertex_x, vertex_y, vertex_z, vertex_t FROM blockcode_nft_records WHERE sha256 = ?",
        (probe["sha256"],),
    ).fetchone()
    if existing:
        conn.close()
        return {
            "status": "already_minted",
            "pattern_code": existing["pattern_code"],
            "sha256": probe["sha256"],
            "vertex": [
                existing["vertex_x"],
                existing["vertex_y"],
                existing["vertex_z"],
                existing["vertex_t"],
            ],
        }

    pattern = pattern_from_digest(probe["sha256"])
    # Form is Feed from Source — vertex derived from content digest (not first-free collapse)
    vertex = pick_vertex(conn, sha256=probe["sha256"])
    audio_vector = audio_vector_from_probe(probe)
    display_title = title or path.stem.replace("direct-h264-", "").replace("direct-gif-", "")

    metadata = {
        "title": display_title,
        "artist": "NLS / InterlaterusDesktop",
        "source_url": source_url,
        "media_path": str(path),
        "sha256": probe["sha256"],
        "technical": probe,
        "audio_vector": audio_vector,
        "interstellar_nonlinear": True,
        "gravity_prepared": True,
        "minted_by": "InterlaterusDesktop",
        "stack": [
            "nlsrecords-executive-portfolio/ffmpeg",
            "Blockcode_NLS_Records/tesseract",
            "NLS-Record/catalog",
        ],
    }

    client = load_client_from_db(conn)
    if client.get_nft_by_pattern(pattern):
        suffix = probe["sha256"][:6]
        pattern = f"{pattern}.{suffix}"

    nft = client.mint_nft(
        pattern_code=pattern,
        vertex=vertex,
        owner_pattern=owner_pattern,
        metadata=metadata,
    )

    created = utc_now()
    conn.execute(
        """
        INSERT INTO blockcode_nft_records
        (pattern_code, vertex_x, vertex_y, vertex_z, vertex_t, owner_pattern,
         metadata_json, media_path, source_url, sha256, created_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?)
        """,
        (
            pattern,
            vertex[0],
            vertex[1],
            vertex[2],
            vertex[3],
            owner_pattern,
            json.dumps(metadata),
            str(path),
            source_url,
            probe["sha256"],
            created,
        ),
    )
    conn.commit()
    conn.close()

    record = {
        "pattern_code": pattern,
        "vertex": vertex,
        "owner_pattern": owner_pattern,
        "nft": nft,
        "probe": probe,
        "minted_at": created,
    }
    write_sidecar(path, record)
    append_catalog(
        {
            "pattern_code": pattern,
            "vertex": vertex,
            "title": display_title,
            "source_url": source_url,
            "media_path": str(path),
            "sha256": probe["sha256"],
            "audio_vector": audio_vector,
            "minted_at": created,
        }
    )
    return {"status": "minted", **record}


def export_ncomm_manifest() -> Path:
    ensure_dirs()
    conn = get_db()
    rows = conn.execute(
        "SELECT pattern_code, vertex_x, vertex_y, vertex_z, vertex_t, "
        "media_path, source_url, sha256, created_at FROM blockcode_nft_records"
    ).fetchall()
    conn.close()
    manifest = {
        "exported_at": utc_now(),
        "origin": "InterlaterusDesktop",
        "transport": "NCOMM/SSH/USB-C (optional)",
        "records": [
            {
                "pattern_code": r["pattern_code"],
                "vertex": [r["vertex_x"], r["vertex_y"], r["vertex_z"], r["vertex_t"]],
                "media_path": r["media_path"],
                "source_url": r["source_url"],
                "sha256": r["sha256"],
                "created_at": r["created_at"],
            }
            for r in rows
        ],
    }
    MANIFEST_PATH.write_text(json.dumps(manifest, indent=2))
    return MANIFEST_PATH


def list_mints() -> None:
    conn = get_db()
    rows = conn.execute(
        "SELECT pattern_code, vertex_x, vertex_y, vertex_z, vertex_t, "
        "media_path, sha256, created_at FROM blockcode_nft_records ORDER BY created_at DESC"
    ).fetchall()
    conn.close()
    if not rows:
        print("No blockcode mints yet.")
        return
    for r in rows:
        v = f"[{r['vertex_x']},{r['vertex_y']},{r['vertex_z']},{r['vertex_t']}]"
        print(f"{r['pattern_code']:28} {v}  {Path(r['media_path']).name}  {r['created_at']}")


def rebalance_vertices(dry_run: bool = False, only_zeros: bool = True) -> Dict[str, Any]:
    """Rewrite POS vertices from content sha256 (Character Designer Form-from-Source).

    Fixes the historical pile-up on [0,0,0,0] when pick_vertex fell through to
    TESSERACT_VERTICES[len(occ)%16] after the 16 exclusive slots filled.

    Updates: blockcode_registry.db, *.interlaterus.json sidecars, nls_media_catalog.json.
    """
    conn = get_db()
    rows = conn.execute(
        "SELECT pattern_code, vertex_x, vertex_y, vertex_z, vertex_t, "
        "media_path, sha256, metadata_json FROM blockcode_nft_records"
    ).fetchall()

    before_hist: Dict[str, int] = {}
    after_hist: Dict[str, int] = {}
    planned: List[Tuple[str, List[int], List[int], str, str]] = []
    skipped = 0
    missing_sha = 0

    for r in rows:
        old = [int(r["vertex_x"]), int(r["vertex_y"]), int(r["vertex_z"]), int(r["vertex_t"])]
        bk = ",".join(str(x) for x in old)
        before_hist[bk] = before_hist.get(bk, 0) + 1
        sha = (r["sha256"] or "").strip()
        if not sha:
            missing_sha += 1
            after_hist[bk] = after_hist.get(bk, 0) + 1
            skipped += 1
            continue
        new = vertex_from_digest(sha)
        if only_zeros and old != [0, 0, 0, 0]:
            after_hist[bk] = after_hist.get(bk, 0) + 1
            skipped += 1
            continue
        ak = ",".join(str(x) for x in new)
        after_hist[ak] = after_hist.get(ak, 0) + 1
        if old == new:
            skipped += 1
            continue
        planned.append((r["pattern_code"], old, new, sha, r["media_path"] or ""))

    if dry_run:
        conn.close()
        return {
            "ok": True,
            "dry_run": True,
            "total": len(rows),
            "would_change": len(planned),
            "skipped": skipped,
            "missing_sha": missing_sha,
            "before_histogram": before_hist,
            "after_histogram": after_hist,
            "sample": [
                {"pattern": p, "old": o, "new": n, "sha256": s[:16]}
                for p, o, n, s, _ in planned[:12]
            ],
            "phi_inv": PHI_INV,
            "mode": "form_from_source",
        }

    updates: List[Dict[str, Any]] = []
    for pattern, old, new, sha, media_path in planned:
        conn.execute(
            """
            UPDATE blockcode_nft_records
            SET vertex_x = ?, vertex_y = ?, vertex_z = ?, vertex_t = ?
            WHERE pattern_code = ?
            """,
            (new[0], new[1], new[2], new[3], pattern),
        )
        if media_path:
            media = Path(media_path)
            sidecar = media.with_suffix(media.suffix + ".interlaterus.json")
            if sidecar.is_file():
                try:
                    sc = json.loads(sidecar.read_text())
                    sc["vertex"] = new
                    sc["vertex_rebalanced_at"] = utc_now()
                    sc["vertex_mode"] = "form_from_source"
                    sidecar.write_text(json.dumps(sc, indent=2))
                except Exception:
                    pass
        updates.append({"pattern": pattern, "old": old, "new": new})
    conn.commit()
    conn.close()

    catalog_updated = 0
    if CATALOG_PATH.is_file():
        try:
            catalog = json.loads(CATALOG_PATH.read_text())
            if isinstance(catalog, list):
                by_sha = {s: n for _, _, n, s, _ in planned}
                by_pattern = {p: n for p, _, n, _, _ in planned}
                for entry in catalog:
                    sha = (entry.get("sha256") or "").strip()
                    pat = entry.get("pattern_code")
                    if sha and sha in by_sha:
                        entry["vertex"] = by_sha[sha]
                        catalog_updated += 1
                    elif pat and pat in by_pattern:
                        entry["vertex"] = by_pattern[pat]
                        catalog_updated += 1
                CATALOG_PATH.write_text(json.dumps(catalog, indent=2))
        except Exception:
            pass

    return {
        "ok": True,
        "dry_run": False,
        "total": len(rows),
        "changed": len(planned),
        "skipped": skipped,
        "missing_sha": missing_sha,
        "catalog_updated": catalog_updated,
        "before_histogram": before_hist,
        "after_histogram": after_hist,
        "sample": updates[:12],
        "phi_inv": PHI_INV,
        "mode": "form_from_source",
    }


def pending_meta_path(media: Path) -> Path:
    return media.with_suffix(media.suffix + ".pending-mint.json")


def read_pending(media: Path) -> Dict[str, Any]:
    p = pending_meta_path(media)
    if p.is_file():
        try:
            return json.loads(p.read_text())
        except Exception:
            pass
    return {}


def _sidecar_already_minted(media: Path) -> Optional[Dict[str, Any]]:
    """Fast path: existing interlaterus sidecar means mint already done (skip sha256)."""
    sidecar = media.with_suffix(media.suffix + ".interlaterus.json")
    if not sidecar.is_file():
        return None
    try:
        sc = json.loads(sidecar.read_text())
        if sc.get("pattern_code") or (sc.get("probe") or {}).get("sha256") or sc.get("sha256"):
            return {
                "status": "already_minted",
                "pattern_code": sc.get("pattern_code"),
                "sha256": (sc.get("probe") or {}).get("sha256") or sc.get("sha256"),
                "via": "sidecar",
            }
    except Exception:
        pass
    return None


def run_watch_once(seen: Optional[set] = None, max_mints: int = 5) -> Dict[str, Any]:
    """Single-pass mint scan of ~/Downloads (idempotent). Used by nls-video cron mint_watch.

    Skips files that already have a valid .interlaterus.json sidecar (no full-file hash).
    Caps new mints per tick with max_mints so cron stays responsive.
    """
    if seen is None:
        seen = set()
    results: List[Dict[str, Any]] = []
    scanned = 0
    minted = 0
    already = 0
    errors = 0
    skipped_sidecar = 0
    if not DOWNLOADS.is_dir():
        return {
            "scanned": 0,
            "minted": 0,
            "already_minted": 0,
            "errors": 0,
            "skipped_sidecar": 0,
            "results": [],
            "error": f"downloads dir missing: {DOWNLOADS}",
        }
    for f in sorted(DOWNLOADS.iterdir()):
        if not f.is_file():
            continue
        if not any(p.match(f.name) for p in WATCH_PATTERNS):
            continue
        scanned += 1
        try:
            st = f.stat()
        except OSError as e:
            errors += 1
            results.append({"file": f.name, "status": "error", "error": str(e)})
            continue
        key = f"{f.name}:{st.st_size}"
        if key in seen:
            continue
        if st.st_size < 1024:
            continue
        # Fast skip: sidecar already present
        existing = _sidecar_already_minted(f)
        if existing:
            seen.add(key)
            already += 1
            skipped_sidecar += 1
            if skipped_sidecar <= 3 or skipped_sidecar % 50 == 0:
                results.append({"file": f.name, **existing})
            continue
        if minted >= max_mints:
            results.append({"file": f.name, "status": "deferred", "reason": "max_mints"})
            continue
        pending = read_pending(f)
        try:
            result = mint_media_file(
                f,
                source_url=pending.get("source_url", ""),
                title=pending.get("title", ""),
            )
            seen.add(key)
            status = result.get("status", "ok")
            if status == "minted":
                minted += 1
            elif status == "already_minted":
                already += 1
            results.append(
                {
                    "file": f.name,
                    "status": status,
                    "pattern_code": result.get("pattern_code"),
                    "sha256": result.get("sha256") or (result.get("probe") or {}).get("sha256"),
                }
            )
        except Exception as e:
            errors += 1
            results.append({"file": f.name, "status": "error", "error": str(e)})
    return {
        "scanned": scanned,
        "minted": minted,
        "already_minted": already,
        "skipped_sidecar": skipped_sidecar,
        "errors": errors,
        "max_mints": max_mints,
        "results": results[:50],
    }


def watch_downloads(interval: float = 2.0) -> None:
    seen: set[str] = set()
    print(f"InterlaterusDesktop watch: {DOWNLOADS} (patterns: direct-h264/gif)")
    while True:
        batch = run_watch_once(seen)
        for r in batch.get("results") or []:
            if r.get("status") == "error":
                print(f"✗ mint failed {r.get('file')}: {r.get('error')}", file=sys.stderr)
            else:
                print(f"✓ {r.get('status')}: {r.get('pattern_code', '?')} ← {r.get('file')}")
        time.sleep(interval)


def cmd_mint(args: argparse.Namespace) -> int:
    path = Path(args.file)
    result = mint_media_file(path, source_url=args.url or "", title=args.title or "")
    print(json.dumps(result, indent=2))
    return 0 if result.get("status") in ("minted", "already_minted") else 1


# ----------------------------- Character Designer (dual form) -----------------------------
# Form can be: hand-drawn still  OR  character captured from video.
# Both feed Sources (1–4) the same way: digest → pattern + vertex (Form is Feed from Source).


def load_character_catalog() -> List[Dict[str, Any]]:
    if not CHARACTER_CATALOG_PATH.is_file():
        return []
    try:
        data = json.loads(CHARACTER_CATALOG_PATH.read_text())
        return data if isinstance(data, list) else []
    except Exception:
        return []


def save_character_catalog(entries: List[Dict[str, Any]]) -> None:
    ensure_dirs()
    CHARACTER_CATALOG_PATH.write_text(json.dumps(entries, indent=2, default=str))


def _infer_form_mode(path: Path, explicit: str = "") -> str:
    mode = (explicit or "").strip().lower().replace("-", "_")
    if mode in FORM_MODES:
        return mode
    if mode in ("handdraw", "hand", "draw", "sketch", "drawing"):
        return "hand_drawn"
    if mode in ("video", "capture", "frame", "clip"):
        return "video_capture"
    suf = path.suffix.lower()
    if suf in HAND_DRAWN_SUFFIXES or suf in MESH_3D_SUFFIXES:
        return "hand_drawn"  # authored form (not video-captured)
    if suf in VIDEO_SUFFIXES:
        return "video_capture"
    raise ValueError(
        f"Cannot infer form mode for {path.name}. "
        "Use --mode hand_drawn|video_capture "
        "(and --space 2d_graphics|3d_form for dimensional space)."
    )


def _infer_form_space(path: Path, explicit: str = "", form_mode: str = "") -> str:
    """2d_graphics (flat / poster / UI art) vs 3d_form (sculpture / mesh / volumetric)."""
    space = (explicit or "").strip().lower().replace("-", "_")
    aliases = {
        "2d": "2d_graphics",
        "2d_graphic": "2d_graphics",
        "graphics": "2d_graphics",
        "graphic": "2d_graphics",
        "flat": "2d_graphics",
        "poster": "2d_graphics",
        "3d": "3d_form",
        "mesh": "3d_form",
        "model": "3d_form",
        "sculpture": "3d_form",
        "sculpt": "3d_form",
        "volumetric": "3d_form",
    }
    if space in FORM_SPACES:
        return space
    if space in aliases:
        return aliases[space]
    suf = path.suffix.lower()
    if suf in MESH_3D_SUFFIXES:
        return "3d_form"
    if suf in HAND_DRAWN_SUFFIXES or suf in VIDEO_SUFFIXES:
        return "2d_graphics"
    # default: authored stills/videos are 2D graphics; call out 3d explicitly for mesh
    return "2d_graphics"


def extract_character_frame(
    video: Path,
    at_sec: float = 1.0,
    out_path: Optional[Path] = None,
) -> Path:
    """Capture one still from video as Character Designer form input (video_capture)."""
    ensure_dirs()
    video = video.expanduser().resolve()
    if not video.is_file():
        raise FileNotFoundError(video)
    if out_path is None:
        out_path = CHARACTER_ASSET_DIR / f"capture-{video.stem}-t{at_sec:.2f}.png"
    out_path = out_path.expanduser().resolve()
    out_path.parent.mkdir(parents=True, exist_ok=True)
    cmd = [
        ffmpeg_path(),
        "-y",
        "-ss",
        str(max(0.0, float(at_sec))),
        "-i",
        str(video),
        "-frames:v",
        "1",
        "-q:v",
        "2",
        str(out_path),
    ]
    subprocess.check_call(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if not out_path.is_file() or out_path.stat().st_size < 32:
        raise RuntimeError(f"ffmpeg failed to extract frame → {out_path}")
    return out_path


def register_character(
    path: Path,
    form_mode: str = "",
    form_space: str = "",
    name: str = "",
    source_url: str = "",
    from_video: str = "",
    at_sec: float = 1.0,
    mint: bool = True,
    notes: str = "",
    historic: Optional[List[str]] = None,
    lineage: str = "",
    aesthetic: str = "",
) -> Dict[str, Any]:
    """Register a Character Designer form.

    Acquisition (form_mode):
      hand_drawn     — sketch / scan / tablet / authored mesh
      video_capture  — character appearance from video (frame or clip)

    Dimensional space (form_space) — orthogonal:
      2d_graphics    — flat graphics, posters, kaleidoscopic stills, UI art
      3d_form        — sculpture lineage, mesh, volumetric character

    Historic / theory:
      historic  — free tags e.g. sculptures, rave poster, NOHN
      lineage   — short historic note (e.g. sculpture → 2D graphic tradition)
      aesthetic — look reference (e.g. "Naked Nuras Loves Rave" mirrored form)
    """
    ensure_dirs()
    path = path.expanduser().resolve()
    video_src: Optional[Path] = Path(from_video).expanduser().resolve() if from_video else None
    asset_path = path
    capture_meta: Dict[str, Any] = {}

    # If user pointed at a video (or --from-video), capture a still for the character form
    if form_mode in ("", "video_capture", "video", "capture") and (
        path.suffix.lower() in VIDEO_SUFFIXES or video_src is not None
    ):
        vid = video_src or path
        if vid.suffix.lower() in VIDEO_SUFFIXES:
            asset_path = extract_character_frame(vid, at_sec=at_sec)
            capture_meta = {
                "from_video": str(vid),
                "at_sec": float(at_sec),
                "capture_still": str(asset_path),
            }
            form_mode = "video_capture"

    mode = _infer_form_mode(asset_path if form_mode != "video_capture" else asset_path, form_mode)
    space = _infer_form_space(asset_path, form_space, form_mode=mode)
    if not asset_path.is_file():
        raise FileNotFoundError(asset_path)

    # Stage a copy under characters/ for stable catalog paths
    staged = CHARACTER_ASSET_DIR / f"{mode}-{space}-{asset_path.name}"
    if asset_path.resolve() != staged.resolve():
        try:
            if not staged.is_file() or staged.stat().st_size != asset_path.stat().st_size:
                shutil.copy2(asset_path, staged)
            asset_path = staged
        except Exception:
            pass  # keep original path if copy fails

    digest = sha256_file(asset_path)
    pattern = pattern_from_digest(digest)
    vertex = vertex_from_digest(digest)
    display = name or asset_path.stem.replace("direct-h264-", "").replace("capture-", "")
    hist_tags = [h.strip() for h in (historic or []) if h and str(h).strip()]

    form_label_bits = []
    form_label_bits.append("hand-drawn" if mode == "hand_drawn" else "video-captured")
    form_label_bits.append("2D graphics" if space == "2d_graphics" else "3D form")
    form_label = " · ".join(form_label_bits) + " character"

    character: Dict[str, Any] = {
        "id": digest[:16],
        "name": display,
        "form_mode": mode,  # hand_drawn | video_capture
        "form_space": space,  # 2d_graphics | 3d_form
        "form_label": form_label,
        "asset_path": str(asset_path),
        "sha256": digest,
        "pattern_code": pattern,
        "vertex": vertex,
        "source_url": source_url,
        "notes": notes,
        "aesthetic": aesthetic or None,
        "historic": {
            "tags": hist_tags,
            "lineage": lineage or None,
            # sculpture history ↔ graphic character (e.g. Naked Nuras Loves Rave)
            "with_sculptures": any(
                "sculpt" in t.lower() for t in hist_tags + [lineage or "", notes or ""]
            ),
        },
        "theory": THEORY_LINEAGE_DEFAULT,
        "sources": {
            "1_formation": {
                "pattern_code": pattern,
                "vertex": vertex,
                "tractatus": "2 / 2.1 — design particles / pictures of facts",
            },
            "2_visual": {
                "asset": str(asset_path),
                "form_mode": mode,
                "form_space": space,
                "aesthetic": aesthetic or None,
                "tractatus": "3 — logical picture of the facts (2D or 3D projection)",
            },
            "3_signal": {
                "sha256": digest,
                "tractatus": "4 — thought as significant proposition / identity hash",
            },
            "4_types": {
                "unique_type": f"{mode}/{space}",
                "notes": notes,
                "historic": hist_tags,
                "tractatus": "5 — truth-functions on elementary form",
            },
            "N_viral": {
                "multi_dose": False,
                "tractatus": "6–7 — general form; silence outside catalog",
            },
        },
        "phi_inv": PHI_INV,
        "registered_at": utc_now(),
        "capture": capture_meta or None,
    }

    if mint:
        mint_target = Path(capture_meta["from_video"]) if capture_meta.get("from_video") else asset_path
        try:
            if mint_target.suffix.lower() in VIDEO_SUFFIXES:
                mint_result = mint_media_file(
                    mint_target, source_url=source_url, title=display
                )
                character["mint"] = {
                    "status": mint_result.get("status"),
                    "pattern_code": mint_result.get("pattern_code"),
                    "vertex": mint_result.get("vertex"),
                    "media_path": str(mint_target),
                }
                if mint_result.get("vertex"):
                    character["vertex"] = mint_result["vertex"]
                if mint_result.get("pattern_code"):
                    character["pattern_code"] = mint_result["pattern_code"]
            elif mint_target.suffix.lower() in MESH_3D_SUFFIXES:
                character["mint"] = {
                    "status": "identity_only",
                    "reason": "3d_form_mesh",
                    "form_space": "3d_form",
                }
            else:
                character["mint"] = {
                    "status": "identity_only",
                    "reason": f"{mode}_{space}",
                }
        except Exception as e:
            character["mint"] = {"status": "error", "error": str(e)}

    # sidecar next to asset
    sidecar = asset_path.with_suffix(asset_path.suffix + ".character.json")
    try:
        sidecar.write_text(json.dumps(character, indent=2, default=str))
        character["sidecar"] = str(sidecar)
    except Exception:
        pass

    catalog = load_character_catalog()
    replaced = False
    for i, e in enumerate(catalog):
        if (e.get("sha256") or "") == digest:
            catalog[i] = character
            replaced = True
            break
    if not replaced:
        catalog.append(character)
    save_character_catalog(catalog)

    character["status"] = "updated" if replaced else "registered"
    character["catalog"] = str(CHARACTER_CATALOG_PATH)
    return character


def list_characters() -> List[Dict[str, Any]]:
    return load_character_catalog()


def cmd_character(args: argparse.Namespace) -> int:
    action = getattr(args, "character_action", "list")
    if action == "list":
        entries = list_characters()
        if getattr(args, "json", False):
            print(json.dumps(entries, indent=2, default=str))
            return 0
        if not entries:
            print("No characters yet.")
            print("  2D graphic:  character register --file art.png --mode hand_drawn --space 2d_graphics")
            print("  3D form:     character register --file hero.obj --mode hand_drawn --space 3d_form")
            print("  from video:  character register --file clip.mp4 --mode video_capture --at 2.5")
            print("  historic:    ... --historic sculptures --lineage 'sculpture→graphic' --aesthetic 'Nuras'")
            return 0
        for e in entries:
            v = e.get("vertex") or []
            space = e.get("form_space") or "?"
            hist = ",".join((e.get("historic") or {}).get("tags") or []) or "—"
            print(
                f"{e.get('form_mode', '?'):14}  {space:12}  {str(v):16}  "
                f"{(e.get('name') or '')[:22]:22}  hist={hist[:18]}"
            )
        return 0
    if action == "register":
        if not args.file:
            print(json.dumps({"ok": False, "error": "--file required"}, indent=2))
            return 1
        hist = []
        if getattr(args, "historic", None):
            for h in args.historic:
                hist.extend([p.strip() for p in str(h).split(",") if p.strip()])
        try:
            result = register_character(
                Path(args.file),
                form_mode=args.mode or "",
                form_space=getattr(args, "space", "") or "",
                name=args.name or "",
                source_url=args.url or "",
                from_video=args.from_video or "",
                at_sec=float(args.at or 1.0),
                mint=not args.no_mint,
                notes=args.notes or "",
                historic=hist,
                lineage=getattr(args, "lineage", "") or "",
                aesthetic=getattr(args, "aesthetic", "") or "",
            )
        except Exception as e:
            print(json.dumps({"ok": False, "error": str(e)}, indent=2))
            return 1
        print(json.dumps(result, indent=2, default=str))
        return 0
    print(f"unknown character action: {action}", file=sys.stderr)
    return 1


def main() -> int:
    parser = argparse.ArgumentParser(description="InterlaterusDesktop vertical NFT/media orchestrator")
    sub = parser.add_subparsers(dest="cmd", required=True)

    p_mint = sub.add_parser("mint", help="Probe media + mint blockcode NFT + catalog")
    p_mint.add_argument("--file", required=True, help="Downloaded media file path")
    p_mint.add_argument("--url", default="", help="Source URL")
    p_mint.add_argument("--title", default="", help="Display title")
    p_mint.set_defaults(func=cmd_mint)

    sub.add_parser("list", help="List minted blockcode records").set_defaults(
        func=lambda _: list_mints() or 0
    )
    sub.add_parser("watch", help="Watch ~/Downloads and auto-mint").set_defaults(
        func=lambda _: watch_downloads() or 0
    )
    sub.add_parser("watch-once", help="Single mint scan pass (for cron mint_watch)").set_defaults(
        func=lambda _: (print(json.dumps(run_watch_once(), indent=2)) or 0)
    )
    sub.add_parser("export", help="Export NCOMM sync manifest").set_defaults(
        func=lambda _: print(export_ncomm_manifest()) or 0
    )

    p_reb = sub.add_parser(
        "rebalance-vertices",
        help="Rewrite POS vertices from sha256 (fix pile-up on [0,0,0,0]; Character Designer Form-from-Source)",
    )
    p_reb.add_argument("--dry-run", action="store_true", help="Show histogram only")
    p_reb.add_argument(
        "--all",
        action="store_true",
        help="Rebalance every record (default: only vertex [0,0,0,0])",
    )
    p_reb.set_defaults(
        func=lambda a: (
            print(
                json.dumps(
                    rebalance_vertices(dry_run=a.dry_run, only_zeros=not a.all),
                    indent=2,
                )
            )
            or 0
        )
    )

    p_ch = sub.add_parser(
        "character",
        help="Character Designer — form from hand-drawn still OR character captured in video",
    )
    p_ch.add_argument(
        "character_action",
        nargs="?",
        default="list",
        choices=["list", "register"],
        help="list | register",
    )
    p_ch.add_argument("--file", default="", help="Image / mesh / video asset")
    p_ch.add_argument(
        "--mode",
        default="",
        help="Acquisition: hand_drawn | video_capture",
    )
    p_ch.add_argument(
        "--space",
        default="",
        help="Dimensional space: 2d_graphics | 3d_form (sculpture/mesh)",
    )
    p_ch.add_argument("--name", default="", help="Display name")
    p_ch.add_argument("--url", default="", help="Source URL")
    p_ch.add_argument("--from-video", default="", help="Parent video path when registering a frame")
    p_ch.add_argument("--at", type=float, default=1.0, help="Seconds into video for frame capture")
    p_ch.add_argument("--notes", default="", help="Unique-type notes (Source 4)")
    p_ch.add_argument(
        "--historic",
        action="append",
        default=[],
        help="Historic tags (repeatable/comma): sculptures, rave, NOHN, …",
    )
    p_ch.add_argument(
        "--lineage",
        default="",
        help="Historic lineage note, e.g. 'sculpture tradition → 2D graphic character'",
    )
    p_ch.add_argument(
        "--aesthetic",
        default="",
        help="Look reference, e.g. 'Naked Nuras Loves Rave' kaleidoscopic face",
    )
    p_ch.add_argument("--no-mint", action="store_true", help="Skip blockcode mint for video assets")
    p_ch.add_argument("--json", action="store_true")
    p_ch.set_defaults(func=cmd_character)

    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())