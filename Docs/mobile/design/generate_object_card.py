# -*- coding: utf-8 -*-
"""Generate object-card mockups (3-step sheet) for Docs/mobile/design/object-card/."""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))

from generate_screens import (  # noqa: E402
    H,
    SHEET_BOTTOM,
    T,
    W,
    highlight_building,
    map_base,
    map_controls,
    nav_bar,
    org_row,
    screen,
    search_dock,
    sheet,
    status_bar,
    teardrop,
)

OUT = ROOT / "object-card"
STATUS_H = 36
SHEET_GAP = 8
CONTENT_TOP = STATUS_H
CONTENT_H = SHEET_BOTTOM - CONTENT_TOP
STEP1_H = 72
STEP2_H = int(CONTENT_H * 0.5)
STEP3_H = CONTENT_H


def sheet_step1_compact(y: int, title: str, subtitle: str | None = None) -> str:
    """Step 1: brief info + close on the right (handle rendered by sheet())."""
    parts = [
        f'<text x="16" y="{y + 44}" font-family="Roboto, Noto Sans, sans-serif" font-size="16" font-weight="650" fill="#1F1F1F">{T(title)}</text>',
    ]
    if subtitle:
        parts.append(
            f'<text x="16" y="{y + 62}" font-family="Roboto, Noto Sans, sans-serif" font-size="12" fill="#6A655C">{T(subtitle)}</text>'
        )
    cy = y + 40 if subtitle else y + 44
    parts.append(f'<circle cx="358" cy="{cy}" r="16" fill="#F3EFE8"/>')
    parts.append(
        f'<text x="358" y="{cy + 5}" text-anchor="middle" font-family="Roboto, sans-serif" font-size="16" fill="#6A655C">{T("×")}</text>'
    )
    return "\n".join(parts)


def sheet_header(y: int, title: str, subtitle: str) -> str:
    return "\n".join(
        [
            f'<text x="16" y="{y + 44}" font-family="Roboto, Noto Sans, sans-serif" font-size="16" font-weight="650" fill="#1F1F1F">{T(title)}</text>',
            f'<text x="16" y="{y + 64}" font-family="Roboto, Noto Sans, sans-serif" font-size="12" fill="#6A655C">{T(subtitle)}</text>',
            f'<circle cx="358" cy="{y + 40}" r="16" fill="#F3EFE8"/>',
            f'<text x="358" y="{y + 45}" text-anchor="middle" font-family="Roboto, sans-serif" font-size="16" fill="#6A655C">{T("×")}</text>',
        ]
    )


def org_fields(y: int, start: int = 108) -> str:
    return f"""
    <text x="16" y="{y + start}" font-family="Roboto, Noto Sans, sans-serif" font-size="13" fill="#6A655C">Telefon</text>
    <text x="108" y="{y + start}" font-family="Roboto, Noto Sans, sans-serif" font-size="14" fill="#00B341">021 450 112</text>
    <text x="16" y="{y + start + 32}" font-family="Roboto, Noto Sans, sans-serif" font-size="13" fill="#6A655C">Sajt</text>
    <text x="108" y="{y + start + 32}" font-family="Roboto, Noto Sans, sans-serif" font-size="14" fill="#00B341">benu.rs</text>
    <text x="16" y="{y + start + 64}" font-family="Roboto, Noto Sans, sans-serif" font-size="13" fill="#6A655C">Adresa</text>
    <text x="108" y="{y + start + 64}" font-family="Roboto, Noto Sans, sans-serif" font-size="14" fill="#1F1F1F">{T("Bulevar oslobođenja 47")}</text>
    <text x="16" y="{y + start + 96}" font-family="Roboto, Noto Sans, sans-serif" font-size="13" fill="#6A655C">{T("Radno vreme")}</text>
    <text x="108" y="{y + start + 96}" font-family="Roboto, Noto Sans, sans-serif" font-size="14" fill="#1F1F1F">08:00-21:00</text>
    """


def building_org_list(y: int, start: int = 96) -> str:
    return (
        org_row(16, y + start, "Apoteka Benu", "Apoteka")
        + org_row(16, y + start + 52, "Kafić Veliki", "Kafić")
        + org_row(16, y + start + 104, "Banka Intesa", "Banka")
        + org_row(16, y + start + 156, "Pekara Anđelko", "Pekara")
        + org_row(16, y + start + 208, "Frizerski salon", "Usluge")
    )


def scroll_fade(bottom_y: int) -> str:
    """Hint that body scrolls on step 3."""
    return f"""
    <defs>
      <linearGradient id="fade" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0%" stop-color="#FFFFFF" stop-opacity="0"/>
        <stop offset="100%" stop-color="#FFFFFF" stop-opacity="0.95"/>
      </linearGradient>
    </defs>
    <rect x="0" y="{bottom_y - 48}" width="{W}" height="48" fill="url(#fade)"/>
    <rect x="182" y="{bottom_y - 18}" width="26" height="3" rx="1.5" fill="#D0CBC3" opacity="0.8"/>
    """


def gap_marker() -> str:
    """Visualize 8dp gap above search dock."""
    y = SHEET_BOTTOM
    return f'<line x1="8" y1="{y}" x2="{W - 8}" y2="{y}" stroke="#00B341" stroke-width="1" stroke-dasharray="4 3" opacity="0.35"/>'


def build() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    files: dict[str, str] = {}

    addr = "Bulevar oslobođenja 47"
    org_sub = "3 organizacije"

    # --- Building steps ---
    y1 = SHEET_BOTTOM - STEP1_H
    files["01-building-step1.svg"] = screen(
        "Building card — step 1 (minimal)",
        map_base(close=True),
        highlight_building(),
        status_bar(),
        map_controls(sheet_h=STEP1_H + SHEET_GAP),
        sheet(STEP1_H, sheet_step1_compact(y1, addr)),
        gap_marker(),
        search_dock(),
        nav_bar(),
    )

    y2 = SHEET_BOTTOM - STEP2_H
    files["02-building-step2.svg"] = screen(
        "Building card — step 2 (half)",
        map_base(close=True),
        highlight_building(),
        status_bar(),
        map_controls(sheet_h=STEP2_H + SHEET_GAP),
        sheet(
            STEP2_H,
            sheet_header(y2, addr, org_sub)
            + org_row(16, y2 + 96, "Apoteka Benu", "Apoteka")
            + org_row(16, y2 + 148, "Kafić Veliki", "Kafić")
            + org_row(16, y2 + 200, "Banka Intesa", "Banka"),
        ),
        gap_marker(),
        search_dock(),
        nav_bar(),
    )

    y3 = SHEET_BOTTOM - STEP3_H
    body3 = sheet_header(y3, addr, org_sub) + building_org_list(y3)
    files["03-building-step3.svg"] = screen(
        "Building card — step 3 (full)",
        map_base(close=True),
        highlight_building(),
        status_bar(),
        map_controls(sheet_h=STEP3_H + SHEET_GAP),
        sheet(STEP3_H, body3 + scroll_fade(SHEET_BOTTOM)),
        gap_marker(),
        search_dock(),
        nav_bar(),
    )

    # --- Organization steps ---
    files["04-org-step1.svg"] = screen(
        "Organization card — step 1 (minimal)",
        map_base(close=True),
        highlight_building(),
        teardrop(121, 446),
        status_bar(),
        map_controls(sheet_h=STEP1_H + SHEET_GAP),
        sheet(STEP1_H, sheet_step1_compact(y1, "Apoteka Benu", "Apoteka")),
        gap_marker(),
        search_dock(),
        nav_bar(),
    )

    files["05-org-step2.svg"] = screen(
        "Organization card — step 2 (half)",
        map_base(close=True),
        highlight_building(),
        teardrop(121, 446),
        status_bar(),
        map_controls(sheet_h=STEP2_H + SHEET_GAP),
        sheet(
            STEP2_H,
            sheet_header(y2, "Apoteka Benu", "Apoteka") + org_fields(y2, 96),
        ),
        gap_marker(),
        search_dock(),
        nav_bar(),
    )

    files["06-org-step3.svg"] = screen(
        "Organization card — step 3 (full)",
        map_base(close=True),
        highlight_building(),
        teardrop(121, 446),
        status_bar(),
        map_controls(sheet_h=STEP3_H + SHEET_GAP),
        sheet(
            STEP3_H,
            sheet_header(y3, "Apoteka Benu", "Apoteka")
            + org_fields(y3, 96)
            + f"""
    <text x="16" y="{y3 + 240}" font-family="Roboto, Noto Sans, sans-serif" font-size="13" fill="#6A655C">Opis</text>
    <text x="108" y="{y3 + 240}" font-family="Roboto, Noto Sans, sans-serif" font-size="14" fill="#1F1F1F">{T("Apoteka sa punim asortimanom lekova.")}</text>
    <text x="16" y="{y3 + 280}" font-family="Roboto, Noto Sans, sans-serif" font-size="13" fill="#6A655C">Email</text>
    <text x="108" y="{y3 + 280}" font-family="Roboto, Noto Sans, sans-serif" font-size="14" fill="#00B341">info@benu.rs</text>
    """
            + scroll_fade(SHEET_BOTTOM),
        ),
        gap_marker(),
        search_dock(),
        nav_bar(),
    )

    # --- From search (dropdown closed) ---
    files["07-search-select-org.svg"] = screen(
        "Search → organization (dropdown closed, step 1)",
        map_base(close=True),
        teardrop(200, 360),
        status_bar(),
        map_controls(sheet_h=STEP1_H + SHEET_GAP),
        sheet(STEP1_H, sheet_step1_compact(y1, "Apoteka Benu", "Apoteka")),
        gap_marker(),
        search_dock("apotek", placeholder=False),
        nav_bar(),
    )

    files["08-search-select-building.svg"] = screen(
        "Search → building (dropdown closed, step 1)",
        map_base(close=True),
        highlight_building(),
        teardrop(121, 446),
        status_bar(),
        map_controls(sheet_h=STEP1_H + SHEET_GAP),
        sheet(STEP1_H, sheet_step1_compact(y1, addr)),
        gap_marker(),
        search_dock("bulevar 47", placeholder=False),
        nav_bar(),
    )

    for name, svg in files.items():
        path = OUT / name
        path.write_text(svg, encoding="utf-8", newline="\n")

    print(f"wrote {len(files)} files to {OUT}")


if __name__ == "__main__":
    build()
