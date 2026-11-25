#!/usr/bin/env python3
"""Balanced framing gate regression (mirrors Catalog.Framing 0.2.5).

Run:
  python3 chromatex_lab/mobile_capture/ios/Tests/framing_regression_test.py
"""

from __future__ import annotations

import sys
from pathlib import Path

# Catalog.Framing — keep in sync with Catalog.swift
SCALE_MIN = 0.94
SCALE_MAX = 1.06
HARD_FILL_MIN = 0.92
HARD_FILL_MAX = 1.10
HARD_SKEW_FRAC = 0.045
PAPER_IMBALANCE_MAX = 0.42
# 100/220 * (1 - 2*0.04) — reference main-camera composition
HOLE_FILL_FRAC = 100.0 / 220.0 * 0.92
HOLE_MM = 100.0
INNER_SPAN_MM = 122.0

IOS = Path(__file__).resolve().parents[1] / "ChromatexLab"


def hole_scale(
    ring_span_x: float,
    ring_span_y: float,
    short_px: int,
    window_fraction: float = HOLE_FILL_FRAC,
) -> float:
    wf = min(max(window_fraction, 0.28), 0.56)
    hole_side = max(48.0, short_px * wf)
    hole_meas = ((ring_span_x + ring_span_y) / 2.0) * (HOLE_MM / INNER_SPAN_MM)
    if hole_side <= 0 or hole_meas <= 0:
        return -1.0
    return hole_meas / hole_side


def hard_skew_ok(skew_px: float, short_px: int = 480) -> bool:
    return skew_px <= short_px * HARD_SKEW_FRAC


def test_constants_ordering() -> None:
    assert HARD_FILL_MIN <= SCALE_MIN
    assert HARD_FILL_MAX >= SCALE_MAX
    assert HARD_SKEW_FRAC < 0.08
    assert HARD_SKEW_FRAC >= 0.040
    assert 0.35 <= PAPER_IMBALANCE_MAX <= 0.50


def test_good_skew_passes() -> None:
    """Good hub-style skew≈3.6 must remain eligible."""
    assert hard_skew_ok(3.6)
    # Matched hole vs the FOV yellow square (~0.418 of the short side).
    s = hole_scale(242, 242, 480)
    assert SCALE_MIN <= s <= SCALE_MAX, s
    print(f"OK good skew/scale scale={s:.3f}")


def test_high_skew_fails_hard() -> None:
    """skew≈23 must FAIL hard skew (limit ~21.6 @480)."""
    assert not hard_skew_ok(23.2), "skew 23.2 should fail"
    print(f"OK rejects high skew=23.2 (limit={480 * HARD_SKEW_FRAC:.1f})")


def test_far_scale_fails() -> None:
    """Far / mid-far must miss the yellow square; a matched hole (~span 242 @480) stays."""
    far = hole_scale(155, 155, 480)
    mid = hole_scale(190, 190, 480)
    old_small = hole_scale(216, 216, 480)  # was "ok" against the old 0.38 square
    ok = hole_scale(242, 242, 480)
    assert far < SCALE_MIN, far
    assert mid < SCALE_MIN, mid
    assert old_small < SCALE_MIN, old_small
    assert SCALE_MIN <= ok <= SCALE_MAX, ok
    print(f"OK rejects far={far:.3f} mid={mid:.3f} old={old_small:.3f}; keeps ok={ok:.3f}")


def test_swift_sources() -> None:
    cat = (IOS / "Models" / "Catalog.swift").read_text()
    assert "scaleMin = 0.94" in cat
    assert "hardSkewFrac = 0.045" in cat
    assert '"0.2.10"' in cat
    assert "referenceShortFovDeg" in cat
    ana = (IOS / "Capture" / "FramingGuideAnalyzer.swift").read_text()
    assert "Catalog.Framing.hardSkewFrac" in ana
    assert "Catalog.Framing.skewFrac" in ana
    assert "hardFailReason" in ana
    assert "fullTarget / innerSpanMm" in ana
    cap = (IOS / "Views" / "CaptureView.swift").read_text()
    assert "onChange(of: camera.lastFrameAt)" in cap
    assert "closeCapture()" in cap
    assert "guide.ok && hardOk" in cap
    cam = (IOS / "Camera" / "CameraController.swift").read_text()
    assert "lastFrameAt" in cam
    assert "videoFieldOfView" in cam
    assert "let rotationDegrees = 0" in cam
    preview = (IOS / "Camera" / "CameraPreviewView.swift").read_text()
    assert "resizeAspect" in preview
    assert "resizeAspectFill" not in preview


def main() -> int:
    tests = [
        test_constants_ordering,
        test_good_skew_passes,
        test_high_skew_fails_hard,
        test_far_scale_fails,
        test_swift_sources,
    ]
    failed = 0
    for t in tests:
        try:
            t()
            print(f"PASS {t.__name__}")
        except Exception as e:
            failed += 1
            print(f"FAIL {t.__name__}: {e}", file=sys.stderr)
    if failed:
        print(f"\n{failed} failed", file=sys.stderr)
        return 1
    print(f"\nAll {len(tests)} passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
