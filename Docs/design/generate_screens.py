# -*- coding: utf-8 -*-
"""Generate 2GIS-like Android map mockups for Docs/design/screens/."""
from __future__ import annotations

from pathlib import Path

OUT = Path(__file__).resolve().parent / "screens"
W, H = 390, 844
NAV_H = 28
SEARCH_H = 52
SEARCH_Y = H - NAV_H - SEARCH_H - 10  # 754
SHEET_BOTTOM = SEARCH_Y - 8  # sheet sits above search dock


def esc(s: str) -> str:
    return (
        s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
    )


def T(s: str) -> str:
    """Escape plus common sr-Latn letters as numeric entities (XML-safe)."""
    table = {
        "č": "&#269;",
        "ć": "&#263;",
        "đ": "&#273;",
        "š": "&#353;",
        "ž": "&#382;",
        "Č": "&#268;",
        "Ć": "&#262;",
        "Đ": "&#272;",
        "Š": "&#352;",
        "Ž": "&#381;",
        "…": "&#8230;",
        "×": "&#215;",
        "−": "&#8722;",
        "–": "&#8211;",
        "—": "&#8212;",
        "©": "&#169;",
        "↑": "&#8593;",
        "°": "&#176;",
    }
    out = esc(s)
    for k, v in table.items():
        out = out.replace(k, v)
    return out


def shadow(sid: str, dy: int, blur: int, op: float) -> str:
    return f"""
    <filter id="{sid}" x="-30%" y="-30%" width="160%" height="160%">
      <feDropShadow dx="0" dy="{dy}" stdDeviation="{blur}" flood-color="#14100A" flood-opacity="{op}"/>
    </filter>"""


def defs() -> str:
    return f"""
  <defs>
    {shadow("sh-card", 6, 10, 0.16)}
    {shadow("sh-sheet", -8, 12, 0.14)}
    {shadow("sh-btn", 2, 3, 0.14)}
    {shadow("sh-pin", 1, 2, 0.28)}
    <linearGradient id="sky" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0%" stop-color="#F4F0E8"/>
      <stop offset="100%" stop-color="#EFEBE3"/>
    </linearGradient>
    <clipPath id="screen"><rect width="{W}" height="{H}"/></clipPath>
  </defs>"""


def map_base(*, close: bool = False) -> str:
    """Beige OpenMapTiles-like city fabric: Danube, park, bulevar, 3D blocks."""
    ox, oy, sc = (0, 0, 1.0) if not close else (-40, -80, 1.18)
    # Buildings as isometric-ish footprints
    blocks = [
        (28, 168, 52, 38), (88, 160, 64, 46), (162, 170, 48, 34),
        (222, 156, 58, 50), (292, 164, 62, 42),
        (22, 248, 46, 40), (78, 240, 70, 52), (160, 252, 44, 36),
        (216, 238, 66, 54), (294, 246, 56, 44),
        (30, 430, 50, 36), (92, 422, 58, 48), (164, 434, 42, 32),
        (218, 418, 62, 50), (294, 428, 54, 40),
        (36, 532, 48, 40), (98, 524, 60, 46), (172, 536, 46, 34),
        (232, 520, 58, 48), (304, 530, 50, 38),
        (40, 640, 54, 42), (108, 632, 52, 48), (176, 644, 48, 36),
        (240, 628, 64, 50), (316, 640, 40, 36),
    ]
    brects = []
    for x, y, w, h in blocks:
        x = int(x * sc + ox)
        y = int(y * sc + oy)
        w, h = int(w * sc), int(h * sc)
        eh = max(10, int(h * 0.38))
        brects.append(
            f'<polygon points="{x},{y} {x+w},{y} {x+w},{y-eh} {x},{y-eh}" fill="#D9D3C8"/>'
            f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="1.5" fill="#E4DED4" stroke="#D0C9BE" stroke-width="0.6"/>'
        )
    return f"""
  <g clip-path="url(#screen)">
    <rect width="{W}" height="{H}" fill="url(#sky)"/>
    <!-- parks -->
    <path d="M 18 110 C 70 70 140 90 168 140 C 120 188 40 186 18 150 Z" fill="#C9DEC0"/>
    <path d="M 250 300 C 310 270 370 310 372 360 C 320 400 240 380 250 300 Z" fill="#C9DEC0"/>
    <path d="M 200 90 C 250 70 310 86 340 120 C 300 150 220 140 200 90 Z" fill="#D4E5CC"/>
    <!-- Danube -->
    <path d="M -10 380 C 40 350 90 390 70 450 C 50 520 20 560 -10 590 Z" fill="#B7D0E4"/>
    <path d="M -10 400 C 28 372 70 402 56 448 C 42 510 16 548 -10 572 Z" fill="#C5DAEA"/>
    <!-- road casings -->
    <g fill="none" stroke="#D8D2C6" stroke-linecap="round">
      <path d="M -20 220 H 410" stroke-width="18"/>
      <path d="M -20 390 H 410" stroke-width="22"/>
      <path d="M -20 510 H 410" stroke-width="14"/>
      <path d="M 148 -20 V 860" stroke-width="18"/>
      <path d="M 268 -20 V 860" stroke-width="14"/>
      <path d="M 70 -20 V 860" stroke-width="10"/>
    </g>
    <!-- road fill -->
    <g fill="none" stroke="#FFFFFF" stroke-linecap="round">
      <path d="M -20 220 H 410" stroke-width="13"/>
      <path d="M -20 390 H 410" stroke-width="16"/>
      <path d="M -20 510 H 410" stroke-width="10"/>
      <path d="M 148 -20 V 860" stroke-width="13"/>
      <path d="M 268 -20 V 860" stroke-width="10"/>
      <path d="M 70 -20 V 860" stroke-width="7"/>
    </g>
    <!-- minor streets -->
    <g fill="none" stroke="#F7F4EE" stroke-width="4" stroke-linecap="round">
      <path d="M -20 290 H 410"/>
      <path d="M -20 460 H 410"/>
      <path d="M -20 580 H 410"/>
      <path d="M 210 -20 V 860"/>
      <path d="M 330 -20 V 860"/>
    </g>
    {''.join(brects)}
  </g>"""


def highlight_building() -> str:
    # block around (92, 422, 58, 48)
    return """
  <g>
    <polygon points="92,422 150,422 150,386 92,386" fill="#00B341" fill-opacity="0.18"/>
    <rect x="92" y="422" width="58" height="48" rx="1.5" fill="#00B341" fill-opacity="0.35" stroke="#008A32" stroke-width="2.4"/>
  </g>"""


def pin(cx: int, cy: int, color: str, letter: str = "", label: str = "") -> str:
    lab = ""
    if label:
        lab = (
            f'<rect x="{cx + 12}" y="{cy - 10}" width="{8 + 6 * len(label)}" height="16" rx="4" fill="#FFFFFF" opacity="0.92"/>'
            f'<text x="{cx + 18}" y="{cy + 2}" font-family="Roboto, Noto Sans, sans-serif" font-size="10" font-weight="600" fill="#1F1F1F">{T(label)}</text>'
        )
    glyph = (
        f'<text x="{cx}" y="{cy + 4}" text-anchor="middle" font-family="Roboto, Noto Sans, sans-serif" font-size="10" font-weight="700" fill="#FFFFFF">{letter}</text>'
        if letter
        else ""
    )
    return f"""
  <g filter="url(#sh-pin)">
    <circle cx="{cx}" cy="{cy}" r="11" fill="{color}" stroke="#FFFFFF" stroke-width="2.5"/>
    {glyph}
    {lab}
  </g>"""


def teardrop(cx: int, cy: int) -> str:
    return f"""
  <g filter="url(#sh-pin)">
    <path d="M {cx} {cy - 22} C {cx + 13} {cy - 22} {cx + 16} {cy - 8} {cx} {cy + 10} C {cx - 16} {cy - 8} {cx - 13} {cy - 22} {cx} {cy - 22} Z" fill="#00B341" stroke="#FFFFFF" stroke-width="2"/>
    <circle cx="{cx}" cy="{cy - 12}" r="5" fill="#FFFFFF"/>
  </g>"""


def status_bar() -> str:
    return f"""
  <rect width="{W}" height="36" fill="#EFEBE3" fill-opacity="0.55"/>
  <text x="18" y="24" font-family="Roboto, Noto Sans, sans-serif" font-size="13" font-weight="500" fill="#1F1F1F">19:42</text>
  <g transform="translate(318,14)" fill="#1F1F1F">
    <rect x="0" y="4" width="3" height="6" rx="0.5"/>
    <rect x="5" y="2" width="3" height="8" rx="0.5"/>
    <rect x="10" y="0" width="3" height="10" rx="0.5"/>
    <rect x="18" y="1" width="22" height="10" rx="2" fill="none" stroke="#1F1F1F" stroke-width="1.3"/>
    <rect x="20" y="3" width="15" height="6" rx="1"/>
    <rect x="40" y="4" width="2" height="4" rx="0.5"/>
  </g>"""


def nav_bar() -> str:
    return f"""
  <rect x="0" y="{H - NAV_H}" width="{W}" height="{NAV_H}" fill="#EFEBE3"/>
  <rect x="155" y="{H - 14}" width="80" height="4" rx="2" fill="#1F1F1F" opacity="0.22"/>"""


def attribution(y: int) -> str:
    return (
        f'<text x="16" y="{y}" font-family="Roboto, Noto Sans, sans-serif" '
        f'font-size="9" fill="#6A655C">{T("© OpenStreetMap")}</text>'
    )


def round_btn(cx: int, cy: int, inner: str) -> str:
    return f"""
  <g filter="url(#sh-btn)">
    <circle cx="{cx}" cy="{cy}" r="22" fill="#FFFFFF"/>
    {inner}
  </g>"""


def map_controls(*, sheet_h: int = 0, compass: bool = False, loc: bool = True) -> str:
    # sit above search + peek
    base_y = SEARCH_Y - 36 - (sheet_h if sheet_h else 0)
    parts = []
    cy = base_y
    if loc:
        parts.append(
            round_btn(
                352,
                cy,
                '<circle cx="352" cy="{0}" r="7" fill="none" stroke="#00B341" stroke-width="2"/>'
                '<circle cx="352" cy="{0}" r="2.5" fill="#00B341"/>'.format(cy),
            )
        )
        cy -= 54
    parts.append(
        round_btn(352, cy, f'<text x="352" y="{cy + 7}" text-anchor="middle" font-family="Roboto, sans-serif" font-size="22" fill="#1F1F1F">-</text>')
    )
    cy -= 50
    parts.append(
        round_btn(352, cy, f'<text x="352" y="{cy + 8}" text-anchor="middle" font-family="Roboto, sans-serif" font-size="20" fill="#1F1F1F">+</text>')
    )
    cy -= 54
    if compass:
        parts.append(
            round_btn(
                352,
                cy,
                f'<polygon points="352,{cy - 10} 358,{cy + 8} 352,{cy + 3} 346,{cy + 8}" fill="#B42318"/>'
                f'<polygon points="352,{cy - 10} 346,{cy + 8} 352,{cy + 3} 358,{cy + 8}" fill="#1F1F1F" opacity="0.15"/>',
            )
        )
    attr_y = SEARCH_Y - 10 - (sheet_h if sheet_h else 0)
    parts.append(attribution(attr_y))
    return "\n".join(parts)


def search_dock(
    query: str = "",
    *,
    placeholder: bool = True,
    loading: bool = False,
    focused: bool = False,
    dock_y: int | None = None,
) -> str:
    x, y, w, h = 16, (SEARCH_Y if dock_y is None else dock_y), 358, SEARCH_H
    if placeholder and not query:
        field = f'<text x="{x + 48}" y="{y + 32}" font-family="Roboto, Noto Sans, sans-serif" font-size="16" fill="#6A655C">{T("Pretraga…")}</text>'
        trailing = ""
    else:
        field = f'<text x="{x + 48}" y="{y + 32}" font-family="Roboto, Noto Sans, sans-serif" font-size="16" fill="#1F1F1F">{T(query)}</text>'
        if loading:
            trailing = f"""
    <circle cx="{x + w - 28}" cy="{y + h/2}" r="8" fill="none" stroke="#E4DDD3" stroke-width="2"/>
    <path d="M {x + w - 28} {y + h/2 - 8} A 8 8 0 0 1 {x + w - 20} {y + h/2}" fill="none" stroke="#00B341" stroke-width="2.4" stroke-linecap="round"/>"""
        else:
            trailing = f"""
    <circle cx="{x + w - 28}" cy="{y + h/2}" r="12" fill="#F3EFE8"/>
    <text x="{x + w - 28}" y="{y + h/2 + 5}" text-anchor="middle" font-family="Roboto, sans-serif" font-size="14" fill="#6A655C">{T("×")}</text>"""
    caret = ""
    if focused:
        caret = f'<rect x="{x + 48 + 7 * len(query)}" y="{y + 16}" width="1.5" height="20" fill="#00B341"/>'
    return f"""
  <g filter="url(#sh-card)">
    <rect x="{x}" y="{y}" width="{w}" height="{h}" rx="14" fill="#FFFFFF"/>
    <circle cx="{x + 26}" cy="{y + h/2}" r="9" fill="none" stroke="#6A655C" stroke-width="2"/>
    <line x1="{x + 33}" y1="{y + h/2 + 7}" x2="{x + 38}" y2="{y + h/2 + 12}" stroke="#6A655C" stroke-width="2" stroke-linecap="round"/>
    {field}
    {caret}
    {trailing}
  </g>"""


def dropdown(
    rows: list[dict],
    *,
    header: str | None = None,
    selected: int | None = None,
    extra: str = "",
    extra_color: str = "#B42318",
    dock_y: int | None = None,
) -> str:
    row_h = 52
    head_h = 28 if header else 0
    n = len(rows)
    extra_h = 48 if extra and n == 0 else (18 if extra else 8)
    h = max(56, head_h + n * row_h + extra_h)
    sy = SEARCH_Y if dock_y is None else dock_y
    y = sy - 8 - h
    x, w = 16, 358
    parts = [f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="12" fill="#FFFFFF"/>']
    cy = y + 10
    if header:
        parts.append(
            f'<text x="{x + 16}" y="{cy + 14}" font-family="Roboto, Noto Sans, sans-serif" font-size="12" font-weight="600" fill="#6A655C">{T(header)}</text>'
        )
        cy += head_h
    if n == 0 and extra:
        parts.append(
            f'<text x="{x + 16}" y="{y + 36}" font-family="Roboto, Noto Sans, sans-serif" font-size="14" fill="{extra_color}">{T(extra)}</text>'
        )
        return f'<g filter="url(#sh-card)">{"".join(parts)}</g>'
    for i, row in enumerate(rows):
        if selected == i:
            parts.append(f'<rect x="{x + 6}" y="{cy}" width="{w - 12}" height="{row_h - 2}" rx="8" fill="#E7F8EC"/>')
        kind = row.get("kind", "ORG")
        kind_bg = "#E7F8EC" if kind == "ORG" else "#EEF1F4"
        kind_fg = "#00B341" if kind == "ORG" else "#5B6B7A"
        parts.append(f'<rect x="{x + 14}" y="{cy + 14}" width="36" height="18" rx="9" fill="{kind_bg}"/>')
        parts.append(
            f'<text x="{x + 32}" y="{cy + 27}" text-anchor="middle" font-family="Roboto, sans-serif" font-size="9" font-weight="700" fill="{kind_fg}">{kind}</text>'
        )
        parts.append(
            f'<text x="{x + 60}" y="{cy + 22}" font-family="Roboto, Noto Sans, sans-serif" font-size="15" fill="#1F1F1F">{T(row["label"])}</text>'
        )
        if row.get("sub"):
            parts.append(
                f'<text x="{x + 60}" y="{cy + 38}" font-family="Roboto, Noto Sans, sans-serif" font-size="12" fill="#6A655C">{T(row["sub"])}</text>'
            )
        if i < n - 1:
            parts.append(f'<line x1="{x + 16}" y1="{cy + row_h}" x2="{x + w - 16}" y2="{cy + row_h}" stroke="#E4DDD3"/>')
        cy += row_h
    if extra and n > 0:
        parts.append(
            f'<text x="{x + 16}" y="{y + h - 8}" font-family="Roboto, Noto Sans, sans-serif" font-size="13" fill="{extra_color}">{T(extra)}</text>'
        )
    return f'<g filter="url(#sh-card)">{"".join(parts)}</g>'


def sheet(height: int, body: str) -> str:
    y = SHEET_BOTTOM - height
    return f"""
  <g filter="url(#sh-sheet)">
    <path d="M 0 {y + 16} Q 0 {y} 16 {y} L {W - 16} {y} Q {W} {y} {W} {y + 16} L {W} {SHEET_BOTTOM} L 0 {SHEET_BOTTOM} Z" fill="#FFFFFF"/>
    <rect x="177" y="{y + 10}" width="36" height="4" rx="2" fill="#D0CBC3"/>
    {body}
  </g>"""


def sheet_header(y: int, title: str, subtitle: str, *, close: bool = True) -> str:
    parts = [
        f'<text x="16" y="{y + 44}" font-family="Roboto, Noto Sans, sans-serif" font-size="16" font-weight="650" fill="#1F1F1F">{T(title)}</text>',
        f'<text x="16" y="{y + 64}" font-family="Roboto, Noto Sans, sans-serif" font-size="12" fill="#6A655C">{T(subtitle)}</text>',
    ]
    if close:
        parts.append(f'<circle cx="358" cy="{y + 40}" r="16" fill="#F3EFE8"/>')
        parts.append(
            f'<text x="358" y="{y + 45}" text-anchor="middle" font-family="Roboto, sans-serif" font-size="16" fill="#6A655C">{T("×")}</text>'
        )
    return "\n".join(parts)


def org_row(x: int, y: int, name: str, cat: str) -> str:
    return f"""
    <text x="{x}" y="{y}" font-family="Roboto, Noto Sans, sans-serif" font-size="14" font-weight="600" fill="#1F1F1F">{T(name)}</text>
    <text x="{x}" y="{y + 16}" font-family="Roboto, Noto Sans, sans-serif" font-size="12" fill="#6A655C">{T(cat)}</text>
    <line x1="{x}" y1="{y + 26}" x2="{W - 16}" y2="{y + 26}" stroke="#E4DDD3"/>"""


def snackbar(text: str, *, above: int = 0) -> str:
    y = SEARCH_Y - 52 - above
    w = min(358, 28 + 7 * len(text))
    x = (W - w) / 2
    return f"""
  <g>
    <rect x="{x}" y="{y}" width="{w}" height="40" rx="20" fill="#2B2B2B"/>
    <text x="{W/2}" y="{y + 25}" text-anchor="middle" font-family="Roboto, Noto Sans, sans-serif" font-size="13" fill="#FFFFFF">{T(text)}</text>
  </g>"""


def keyboard(y: int) -> str:
    h = H - y
    keys = [
        "qwertzuiop",
        "asdfghjkl",
        "yxcvbnm",
    ]
    parts = [f'<rect x="0" y="{y}" width="{W}" height="{h}" fill="#D8D2C8"/>']
    ky = y + 10
    for row in keys:
        kw = 32
        gap = 4
        total = len(row) * (kw + gap) - gap
        kx = (W - total) / 2
        for ch in row:
            parts.append(f'<rect x="{kx}" y="{ky}" width="{kw}" height="36" rx="5" fill="#FFFFFF"/>')
            parts.append(
                f'<text x="{kx + kw/2}" y="{ky + 24}" text-anchor="middle" font-family="Roboto, sans-serif" font-size="14" fill="#1F1F1F">{ch}</text>'
            )
            kx += kw + gap
        ky += 42
    parts.append(f'<rect x="70" y="{ky}" width="250" height="36" rx="5" fill="#FFFFFF"/>')
    parts.append(
        f'<text x="195" y="{ky + 24}" text-anchor="middle" font-family="Roboto, sans-serif" font-size="13" fill="#6A655C">space</text>'
    )
    return "\n".join(parts)


def wrap(title: str, body: str) -> str:
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}" role="img">
  <title>{T(title)}</title>
{defs()}
{body}
</svg>
"""


def screen(title: str, *parts: str) -> str:
    return wrap(title, "\n".join(parts))


HITS = [
    {"kind": "ORG", "label": "Apoteka Benu", "sub": "Apoteka"},
    {"kind": "ADR", "label": "Bulevar oslobođenja 47", "sub": "Adresa"},
    {"kind": "ORG", "label": "Apoteka Lilly", "sub": "Apoteka"},
    {"kind": "ORG", "label": "Apoteka Janković", "sub": "Apoteka"},
]


def poi_sparse() -> str:
    return (
        pin(126, 248, "#00B341", "P")
        + pin(238, 360, "#3B82C4", "B")
        + pin(310, 250, "#E24B4A", "H")
        + pin(80, 470, "#7C6BC4", "S")
    )


def poi_dense() -> str:
    return (
        pin(126, 248, "#00B341", "P", "Apoteka")
        + pin(238, 360, "#3B82C4", "B")
        + pin(310, 250, "#E24B4A", "H")
        + pin(80, 470, "#7C6BC4", "S")
        + pin(200, 300, "#E67E22", "C", "Kafić")
        + pin(250, 470, "#F0A500", "S")
        + pin(180, 560, "#00B341", "P")
        + pin(300, 500, "#E67E22", "C")
        + pin(60, 340, "#3B82C4", "B")
    )


def build() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    files: dict[str, str] = {}

    files["01-browse-idle.svg"] = screen(
        "Browse: idle map",
        map_base(),
        status_bar(),
        map_controls(),
        search_dock(),
        nav_bar(),
    )

    files["02-browse-poi-z15.svg"] = screen(
        "Browse: POI at zoom 15",
        map_base(),
        poi_sparse(),
        status_bar(),
        map_controls(),
        search_dock(),
        nav_bar(),
    )

    files["03-browse-poi-z17.svg"] = screen(
        "Browse: POI at zoom 17",
        map_base(close=True),
        poi_dense(),
        status_bar(),
        map_controls(compass=True),
        search_dock(),
        nav_bar(),
    )

    peek_h = 120
    y_peek = SHEET_BOTTOM - peek_h
    files["04-building-loading.svg"] = screen(
        "Building: loading",
        map_base(close=True),
        highlight_building(),
        status_bar(),
        map_controls(sheet_h=peek_h),
        sheet(
            peek_h,
            sheet_header(y_peek, "Učitavam…", "Zgrada")
            + f'<text x="16" y="{y_peek + 96}" font-family="Roboto, Noto Sans, sans-serif" font-size="13" fill="#6A655C">{T("Učitavam…")}</text>',
        ),
        search_dock(),
        nav_bar(),
    )

    files["05-building-peek.svg"] = screen(
        "Building: peek",
        map_base(close=True),
        highlight_building(),
        status_bar(),
        map_controls(sheet_h=peek_h),
        sheet(
            peek_h,
            sheet_header(y_peek, "Bulevar oslobođenja 47", "3 organizacije"),
        ),
        search_dock(),
        nav_bar(),
    )

    half_h = 330
    y_half = SHEET_BOTTOM - half_h
    files["06-building-org-list.svg"] = screen(
        "Building: org list",
        map_base(close=True),
        highlight_building(),
        status_bar(),
        map_controls(sheet_h=half_h),
        sheet(
            half_h,
            sheet_header(y_half, "Bulevar oslobođenja 47", "3 organizacije")
            + org_row(16, y_half + 96, "Apoteka Benu", "Apoteka")
            + org_row(16, y_half + 148, "Kafić Veliki", "Kafić")
            + org_row(16, y_half + 200, "Banka Intesa", "Banka"),
        ),
        search_dock(),
        nav_bar(),
    )

    files["07-building-empty.svg"] = screen(
        "Building: empty",
        map_base(close=True),
        highlight_building(),
        status_bar(),
        map_controls(sheet_h=peek_h),
        sheet(
            peek_h,
            sheet_header(y_peek, "Zgrada", "Nema organizacija u zgradi"),
        ),
        search_dock(),
        nav_bar(),
    )

    org_h = 380
    y_org = SHEET_BOTTOM - org_h
    org_fields = f"""
    <text x="16" y="{y_org + 108}" font-family="Roboto, Noto Sans, sans-serif" font-size="13" fill="#6A655C">Telefon</text>
    <text x="108" y="{y_org + 108}" font-family="Roboto, Noto Sans, sans-serif" font-size="14" fill="#00B341">021 450 112</text>
    <text x="16" y="{y_org + 140}" font-family="Roboto, Noto Sans, sans-serif" font-size="13" fill="#6A655C">Sajt</text>
    <text x="108" y="{y_org + 140}" font-family="Roboto, Noto Sans, sans-serif" font-size="14" fill="#00B341">benu.rs</text>
    <text x="16" y="{y_org + 172}" font-family="Roboto, Noto Sans, sans-serif" font-size="13" fill="#6A655C">Adresa</text>
    <text x="108" y="{y_org + 172}" font-family="Roboto, Noto Sans, sans-serif" font-size="14" fill="#1F1F1F">{T("Bulevar oslobođenja 47")}</text>
    <text x="16" y="{y_org + 204}" font-family="Roboto, Noto Sans, sans-serif" font-size="13" fill="#6A655C">{T("Radno vreme")}</text>
    <text x="108" y="{y_org + 204}" font-family="Roboto, Noto Sans, sans-serif" font-size="14" fill="#1F1F1F">08:00-21:00</text>
    """
    files["08-org-card.svg"] = screen(
        "Organization card",
        map_base(close=True),
        highlight_building(),
        teardrop(121, 446),
        status_bar(),
        map_controls(sheet_h=org_h),
        sheet(
            org_h,
            sheet_header(y_org, "Apoteka Benu", "Apoteka") + org_fields,
        ),
        search_dock(),
        nav_bar(),
    )

    files["09-error-no-building.svg"] = screen(
        "Error: no building",
        map_base(),
        status_bar(),
        map_controls(),
        snackbar("Nema zgrade na ovoj tački"),
        search_dock(),
        nav_bar(),
    )

    files["10-error-outside-city.svg"] = screen(
        "Error: outside city",
        map_base(),
        status_bar(),
        map_controls(),
        snackbar("Van grada Novi Sad"),
        search_dock(),
        nav_bar(),
    )

    files["11-error-offline.svg"] = screen(
        "Error: offline",
        map_base(),
        status_bar(),
        map_controls(),
        snackbar("Nema veze sa serverom"),
        search_dock(),
        nav_bar(),
    )

    files["12-search-history.svg"] = screen(
        "Search: recent history",
        map_base(),
        status_bar(),
        map_controls(),
        dropdown(
            [
                {"kind": "ORG", "label": "Apoteka Benu", "sub": "Apoteka"},
                {"kind": "ADR", "label": "Bulevar oslobođenja 47", "sub": "Adresa"},
                {"kind": "ORG", "label": "Kafić Veliki", "sub": "Kafić"},
            ],
            header="Nedavno",
        ),
        search_dock(focused=True),
        nav_bar(),
    )

    files["13-search-short-query.svg"] = screen(
        "Search: query shorter than 2",
        map_base(),
        status_bar(),
        map_controls(),
        search_dock("a", placeholder=False, focused=True),
        nav_bar(),
    )

    files["14-search-loading.svg"] = screen(
        "Search: loading",
        map_base(),
        status_bar(),
        map_controls(),
        search_dock("apotek", placeholder=False, loading=True, focused=True),
        nav_bar(),
    )

    files["15-search-hits.svg"] = screen(
        "Search: hits",
        map_base(),
        status_bar(),
        map_controls(),
        dropdown(HITS, selected=0),
        search_dock("apotek", placeholder=False, focused=True),
        nav_bar(),
    )

    files["16-search-empty.svg"] = screen(
        "Search: empty",
        map_base(),
        status_bar(),
        map_controls(),
        dropdown([], extra="Ništa nije pronađeno", extra_color="#6A655C"),
        search_dock("xyzzy", placeholder=False, focused=True),
        nav_bar(),
    )

    files["17-search-offline.svg"] = screen(
        "Search: offline",
        map_base(),
        status_bar(),
        map_controls(),
        dropdown([], extra="Nema veze sa serverom"),
        search_dock("apotek", placeholder=False),
        nav_bar(),
    )

    files["18-search-unavailable.svg"] = screen(
        "Search: unavailable",
        map_base(),
        status_bar(),
        map_controls(),
        dropdown([], extra="Pretraga privremeno nedostupna"),
        search_dock("apotek", placeholder=False),
        nav_bar(),
    )

    kbd_y = 530
    dock_kbd = kbd_y - 10 - SEARCH_H
    files["19-search-keyboard.svg"] = screen(
        "Search: keyboard open",
        map_base(),
        status_bar(),
        attribution(dock_kbd - 12),
        dropdown(HITS[:3], selected=0, dock_y=dock_kbd),
        search_dock("apotek", placeholder=False, focused=True, dock_y=dock_kbd),
        keyboard(kbd_y),
    )

    files["20-search-selected-org.svg"] = screen(
        "Search selected: organization",
        map_base(close=True),
        teardrop(200, 360),
        status_bar(),
        map_controls(sheet_h=org_h),
        sheet(
            org_h,
            sheet_header(y_org, "Apoteka Benu", "Apoteka") + org_fields,
        ),
        search_dock("apotek", placeholder=False),
        nav_bar(),
    )

    files["21-search-selected-address.svg"] = screen(
        "Search selected: address",
        map_base(close=True),
        highlight_building(),
        teardrop(121, 446),
        status_bar(),
        map_controls(sheet_h=half_h),
        sheet(
            half_h,
            sheet_header(y_half, "Bulevar oslobođenja 47", "3 organizacije")
            + org_row(16, y_half + 96, "Apoteka Benu", "Apoteka")
            + org_row(16, y_half + 148, "Kafić Veliki", "Kafić")
            + org_row(16, y_half + 200, "Banka Intesa", "Banka"),
        ),
        search_dock("bulevar 47", placeholder=False),
        nav_bar(),
    )

    files["22-search-multi.svg"] = screen(
        "Search multi: category on map",
        map_base(),
        teardrop(126, 248),
        teardrop(180, 560),
        teardrop(300, 430),
        teardrop(250, 340),
        status_bar(),
        map_controls(sheet_h=half_h),
        sheet(
            half_h,
            sheet_header(y_half, "Apoteka", "4 rezultata na karti")
            + org_row(16, y_half + 96, "Apoteka Benu", "240 m")
            + org_row(16, y_half + 148, "Apoteka Lilly", "410 m")
            + org_row(16, y_half + 200, "Apoteka Janković", "680 m"),
        ),
        search_dock("apoteka", placeholder=False),
        nav_bar(),
    )

    files["23-building-closed.svg"] = screen(
        "Building: closed, highlight cleared",
        map_base(close=True),
        poi_sparse(),
        status_bar(),
        map_controls(),
        search_dock(),
        nav_bar(),
    )

    for name, svg in files.items():
        path = OUT / name
        path.write_text(svg, encoding="utf-8", newline="\n")
        raw = path.read_bytes()
        bad = [b for b in raw if b < 32 and b not in (9, 10, 13)]
        if bad:
            raise SystemExit(f"{name}: control chars {bad[:5]}")

    overview = OUT / "01-browse-idle.svg"
    (Path(__file__).resolve().parent / "map-screen.svg").write_bytes(overview.read_bytes())
    print(f"wrote {len(files)} files to {OUT}")


if __name__ == "__main__":
    build()
