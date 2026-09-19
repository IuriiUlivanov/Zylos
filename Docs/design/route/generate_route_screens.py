# -*- coding: utf-8 -*-
"""Generate 2GIS-like route mockups — v2 bottom tab bar + full-width route panel."""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from generate_screens import (  # noqa: E402
    H,
    NAV_H,
    W,
    T,
    attribution,
    defs,
    highlight_building,
    map_base,
    nav_bar,
    screen,
    sheet,
    sheet_header,
    status_bar,
    teardrop,
)

OUT = Path(__file__).resolve().parent

TAB_H = 56
TAB_Y = H - NAV_H - TAB_H
PANEL_GAP = 8
SEARCH_PANEL_H = 52
MODE_ROW_H = 36
ROUTE_ROW_H = 44
ROUTE_PANEL_PAD = 12
ROUTE_PANEL_H = ROUTE_PANEL_PAD + ROUTE_ROW_H + 8 + ROUTE_ROW_H + 8 + MODE_ROW_H + ROUTE_PANEL_PAD
ROUTE_PANEL_Y = TAB_Y - ROUTE_PANEL_H
SEARCH_PANEL_Y = TAB_Y - PANEL_GAP - SEARCH_PANEL_H
SHEET_BOTTOM_SEARCH = SEARCH_PANEL_Y - PANEL_GAP
SHEET_BOTTOM_ROUTE = ROUTE_PANEL_Y - PANEL_GAP

SIDE_BTN = 40
SWAP_BTN = 36
SWAP_GAP = 8
ROW_INSET = 8
INP_X = ROW_INSET + SIDE_BTN + 8
MAP_X = W - ROW_INSET - SWAP_BTN - SWAP_GAP - SIDE_BTN
SWAP_X = MAP_X + SIDE_BTN + SWAP_GAP

STATUS_H = 36
MAP_HALF_Y = H // 2
ROUTE_SHEET_STEP1_H = 80
ROUTE_SHEET_STEP2_H = 280
ROUTE_SHEET_STEP3_H = ROUTE_PANEL_Y - STATUS_H - 8
ROUTE_CARD_W = W - 32
ROUTE_CARD_GAP = 12
ROUTE_CARD_X0 = (W - ROUTE_CARD_W) // 2

ROUTE_VARIANTS = [
    {
        "duration": 25,
        "transfers": 1,
        "legs": [
            {"mode": "walk", "time": 6},
            {"mode": "bus", "type": "Autobus", "num": "7A", "time": 15, "color": "#E30613"},
            {"mode": "walk", "time": 4},
        ],
    },
    {
        "duration": 31,
        "transfers": 1,
        "legs": [
            {"mode": "walk", "time": 8},
            {"mode": "bus", "type": "Autobus", "num": "11", "time": 18, "color": "#1565C0"},
            {"mode": "walk", "time": 5},
        ],
    },
]


def _route_fields_ready(from_val: str, to_val: str) -> bool:
    return bool(from_val.strip()) and bool(to_val.strip())


def bottom_tab_bar(
    *,
    active: str = "search",
    route_ready: bool = False,
    route_loading: bool = False,
) -> str:
    y = TAB_Y
    h = TAB_H
    half = W / 2
    search_active = active == "search"
    route_active = active == "route"
    s_fg = "#00B341" if search_active else "#6A655C"
    s_ind = f'<rect x="0" y="{y + h - 3}" width="{int(half)}" height="3" fill="#00B341"/>' if search_active else ""

    if route_ready and route_active:
        op = ' opacity="0.72"' if route_loading else ""
        spinner = ""
        if route_loading:
            spinner = f"""
    <path d="M {half + half/2} {y + 18} A 10 10 0 1 1 {half + half/2 - 1} {y + 18}" fill="none" stroke="#FFFFFF" stroke-width="2.2" stroke-linecap="round"/>"""
        right = f"""
    <rect x="{int(half)}" y="{y}" width="{int(half)}" height="{h}" fill="#00B341"{op}/>
    {spinner}
    <text x="{half + half/2}" y="{y + 32}" text-anchor="middle" font-family="Roboto, Noto Sans, sans-serif" font-size="13" font-weight="600" fill="#FFFFFF">{T("Napravi rutu")}</text>"""
    else:
        r_fg = "#00B341" if route_active else "#6A655C"
        r_ind = f'<rect x="{int(half)}" y="{y + h - 3}" width="{int(half)}" height="3" fill="#00B341"/>' if route_active else ""
        right = f"""
    {r_ind}
    <path d="M {half + 90} {y + 16} L {half + 106} {y + 24} L {half + 90} {y + 32} Z" fill="none" stroke="{r_fg}" stroke-width="2" stroke-linejoin="round"/>
    <circle cx="{half + 88}" cy="{y + 16}" r="2.5" fill="{r_fg if route_active else "#6A655C"}"/>
    <circle cx="{half + 108}" cy="{y + 32}" r="2.5" fill="#B42318"/>
    <text x="{half + half/2}" y="{y + 46}" text-anchor="middle" font-family="Roboto, Noto Sans, sans-serif" font-size="11" font-weight="{"600" if route_active else "400"}" fill="{r_fg}">{T("Ruta")}</text>"""

    return f"""
  <g>
    <rect x="0" y="{y}" width="{W}" height="{h}" fill="#FFFFFF"/>
    <line x1="0" y1="{y}" x2="{W}" y2="{y}" stroke="#E4DDD3"/>
    <line x1="{half}" y1="{y + 8}" x2="{half}" y2="{y + h - 8}" stroke="#E4DDD3"/>
    {s_ind}
    <circle cx="97" cy="{y + 22}" r="10" fill="none" stroke="{s_fg}" stroke-width="2"/>
    <line x1="104" y1="{y + 29}" x2="109" y2="{y + 34}" stroke="{s_fg}" stroke-width="2" stroke-linecap="round"/>
    <text x="97" y="{y + 46}" text-anchor="middle" font-family="Roboto, Noto Sans, sans-serif" font-size="11" font-weight="{"600" if search_active else "400"}" fill="{s_fg}">{T("Pretraga")}</text>
    {right}
  </g>"""


def search_panel(
    query: str = "",
    *,
    placeholder: bool = True,
    focused: bool = False,
) -> str:
    x, y, w, h = 16, SEARCH_PANEL_Y, 358, SEARCH_PANEL_H
    if placeholder and not query:
        field = f'<text x="{x + 48}" y="{y + 32}" font-family="Roboto, Noto Sans, sans-serif" font-size="16" fill="#6A655C">{T("Pretraga…")}</text>'
        trailing = ""
    else:
        field = f'<text x="{x + 48}" y="{y + 32}" font-family="Roboto, Noto Sans, sans-serif" font-size="16" fill="#1F1F1F">{T(query)}</text>'
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


def _loc_btn(x: int, y: int, *, enabled: bool = False) -> str:
    col = "#00B341" if enabled else "#6A655C"
    op = "" if enabled else ' opacity="0.38"'
    return f"""
    <rect x="{x}" y="{y}" width="{SIDE_BTN}" height="{SIDE_BTN}" rx="10" fill="#F3EFE8"{op}/>
    <circle cx="{x + 20}" cy="{y + 20}" r="7" fill="none" stroke="{col}" stroke-width="1.8"/>
    <circle cx="{x + 20}" cy="{y + 20}" r="2.5" fill="{col}"/>"""


def _map_pick_btn(x: int, y: int, *, active: bool = False) -> str:
    bg = "#E7F8EC" if active else "#F3EFE8"
    return f"""
    <rect x="{x}" y="{y}" width="{SIDE_BTN}" height="{SIDE_BTN}" rx="10" fill="{bg}"/>
    <path d="M {x + 14} {y + 12} C {x + 22} {y + 12} {x + 26} {y + 20} {x + 20} {y + 30} C {x + 14} {y + 20} {x + 10} {y + 12} {x + 14} {y + 12} Z" fill="none" stroke="#6A655C" stroke-width="1.6"/>
    <circle cx="{x + 20}" cy="{y + 19}" r="2" fill="#6A655C"/>"""


def _route_input(x: int, y: int, w: int, text: str, ph: str, *, active: bool = False) -> str:
    fill = "#1F1F1F" if text else "#6A655C"
    bg = "#E7F8EC" if active else "#FFFFFF"
    stroke = "#00B341" if active else "#E4DDD3"
    return f"""
    <rect x="{x}" y="{y}" width="{w}" height="{ROUTE_ROW_H}" rx="10" fill="{bg}" stroke="{stroke}" stroke-width="1"/>
    <text x="{x + 12}" y="{y + 28}" font-family="Roboto, Noto Sans, sans-serif" font-size="14" fill="{fill}">{T(text if text else ph)}</text>"""


def _swap_btn(row1_y: int, row2_y: int, *, highlighted: bool = False) -> str:
    x = SWAP_X
    y = row1_y
    h = row2_y + SIDE_BTN - row1_y
    cx = x + SWAP_BTN / 2
    cy = y + h / 2
    bg = "#E7F8EC" if highlighted else "#F3EFE8"
    stroke = "#00B341" if highlighted else "#6A655C"
    return f"""
    <rect x="{x}" y="{y}" width="{SWAP_BTN}" height="{h}" rx="10" fill="{bg}"/>
    <path d="M {cx} {cy - 16} L {cx} {cy + 16}" stroke="{stroke}" stroke-width="2.5" stroke-linecap="round"/>
    <path d="M {cx - 4} {cy - 12} L {cx} {cy - 16} L {cx + 4} {cy - 12}" fill="none" stroke="{stroke}" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"/>
    <path d="M {cx - 4} {cy + 12} L {cx} {cy + 16} L {cx + 4} {cy + 12}" fill="none" stroke="{stroke}" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"/>"""


def _mode_icons(y: int, *, mode: str = "transit") -> str:
    walk_active = mode == "walk"
    tr_active = mode == "transit"
    walk_bg = "#E7F8EC" if walk_active else "none"
    tr_bg = "#E7F8EC" if tr_active else "none"
    walk_st = "#00B341" if walk_active else "#6A655C"
    tr_st = "#00B341" if tr_active else "#6A655C"
    cx1, cx2 = 120, 270
    return f"""
    <line x1="0" y1="{y}" x2="{W}" y2="{y}" stroke="#E4DDD3"/>
    <rect x="{cx1 - 22}" y="{y + 4}" width="44" height="28" rx="8" fill="{walk_bg}"/>
    <circle cx="{cx1 - 6}" cy="{y + 18}" r="3" fill="{walk_st}"/>
    <path d="M {cx1 - 10} {y + 24} L {cx1 - 2} {y + 16} L {cx1 + 6} {y + 24}" fill="none" stroke="{walk_st}" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
    <rect x="{cx2 - 22}" y="{y + 4}" width="44" height="28" rx="8" fill="{tr_bg}"/>
    <rect x="{cx2 - 12}" y="{y + 12}" width="24" height="12" rx="3" fill="none" stroke="{tr_st}" stroke-width="1.8"/>
    <circle cx="{cx2 - 6}" cy="{y + 26}" r="2.5" fill="{tr_st}"/>
    <circle cx="{cx2 + 6}" cy="{y + 26}" r="2.5" fill="{tr_st}"/>"""


def route_panel(
    *,
    from_val: str = "",
    to_val: str = "",
    from_ph: str = "Odakle?",
    to_ph: str = "Kuda?",
    active_field: str | None = None,
    mode: str = "transit",
    loc_enabled: bool = False,
    map_pick_active: str | None = None,
    swap_highlight: bool = False,
    embedded: bool = False,
) -> str:
    y = ROUTE_PANEL_Y
    h = ROUTE_PANEL_H
    row1_y = y + ROUTE_PANEL_PAD
    row2_y = row1_y + ROUTE_ROW_H + 8
    mode_y = row2_y + ROUTE_ROW_H + 8
    inp_w = MAP_X - 8 - INP_X

    from_row = _loc_btn(ROW_INSET, row1_y, enabled=loc_enabled)
    from_row += _route_input(INP_X, row1_y, inp_w, from_val, from_ph, active=active_field == "from")
    from_row += _map_pick_btn(MAP_X, row1_y, active=map_pick_active == "from")

    to_row = _route_input(INP_X, row2_y, inp_w, to_val, to_ph, active=active_field == "to")
    to_row += _map_pick_btn(MAP_X, row2_y, active=map_pick_active == "to")
    swap = _swap_btn(row1_y, row2_y, highlighted=swap_highlight)

    shell = ""
    if not embedded:
        shell = f"""
    <rect x="0" y="{y}" width="{W}" height="{h}" fill="#FFFFFF"/>
    <line x1="0" y1="{y}" x2="{W}" y2="{y}" stroke="#E4DDD3"/>"""

    return f"""
  <g>{shell}
    {from_row}
    {to_row}
    {swap}
    {_mode_icons(mode_y, mode=mode)}
  </g>"""


def route_tab_bar(from_val: str = "", to_val: str = "", *, active: str = "route", loading: bool = False) -> str:
    return bottom_tab_bar(
        active=active,
        route_ready=_route_fields_ready(from_val, to_val),
        route_loading=loading,
    )


def route_dropdown(
    rows: list[dict],
    *,
    header: str | None = None,
    selected: int | None = None,
) -> str:
    """Full-width flush dropdown — same rows as search, attached to route panel top."""
    row_h = 52
    head_h = 28 if header else 0
    n = len(rows)
    h = max(56, head_h + n * row_h + 8)
    bottom = ROUTE_PANEL_Y
    top = bottom - h
    parts = [f'<rect x="0" y="{top}" width="{W}" height="{h}" fill="#FFFFFF"/>']
    cy = top + 10
    if header:
        parts.append(
            f'<text x="16" y="{cy + 14}" font-family="Roboto, Noto Sans, sans-serif" font-size="12" font-weight="600" fill="#6A655C">{T(header)}</text>'
        )
        cy += head_h
    for i, row in enumerate(rows):
        if selected == i:
            parts.append(f'<rect x="8" y="{cy}" width="{W - 16}" height="{row_h - 2}" rx="8" fill="#E7F8EC"/>')
        kind = row.get("kind", "ORG")
        kind_bg = "#E7F8EC" if kind == "ORG" else "#EEF1F4"
        kind_fg = "#00B341" if kind == "ORG" else "#5B6B7A"
        parts.append(f'<rect x="16" y="{cy + 14}" width="36" height="18" rx="9" fill="{kind_bg}"/>')
        parts.append(
            f'<text x="34" y="{cy + 27}" text-anchor="middle" font-family="Roboto, sans-serif" font-size="9" font-weight="700" fill="{kind_fg}">{kind}</text>'
        )
        parts.append(
            f'<text x="60" y="{cy + 22}" font-family="Roboto, Noto Sans, sans-serif" font-size="15" fill="#1F1F1F">{T(row["label"])}</text>'
        )
        if row.get("sub"):
            parts.append(
                f'<text x="60" y="{cy + 38}" font-family="Roboto, Noto Sans, sans-serif" font-size="12" fill="#6A655C">{T(row["sub"])}</text>'
            )
        if i < n - 1:
            parts.append(f'<line x1="16" y1="{cy + row_h}" x2="{W - 16}" y2="{cy + row_h}" stroke="#E4DDD3"/>')
        cy += row_h
    return f"<g>{''.join(parts)}</g>"


def map_controls_v2(*, bottom_ui: int | None = None, sheet_h: int = 0) -> str:
    if bottom_ui is None:
        bottom_ui = SEARCH_PANEL_H + TAB_H + PANEL_GAP
    panel_top = TAB_Y - bottom_ui + TAB_H
    if bottom_ui >= ROUTE_PANEL_H + TAB_H:
        panel_top = ROUTE_PANEL_Y
    base_y = panel_top - 36 - sheet_h
    cy = base_y
    parts = [
        f"""
  <g filter="url(#sh-btn)">
    <circle cx="352" cy="{cy}" r="22" fill="#FFFFFF"/>
    <circle cx="352" cy="{cy}" r="7" fill="none" stroke="#00B341" stroke-width="2"/><circle cx="352" cy="{cy}" r="2.5" fill="#00B341"/>
  </g>""",
        f"""
  <g filter="url(#sh-btn)">
    <circle cx="352" cy="{cy - 54}" r="22" fill="#FFFFFF"/>
    <text x="352" y="{cy - 47}" text-anchor="middle" font-family="Roboto, sans-serif" font-size="22" fill="#1F1F1F">-</text>
  </g>""",
        f"""
  <g filter="url(#sh-btn)">
    <circle cx="352" cy="{cy - 104}" r="22" fill="#FFFFFF"/>
    <text x="352" y="{cy - 96}" text-anchor="middle" font-family="Roboto, sans-serif" font-size="20" fill="#1F1F1F">+</text>
  </g>""",
        attribution(panel_top - 10 - sheet_h),
    ]
    return "\n".join(parts)


def route_line(*, variant: int = 0) -> str:
    walk_style = 'stroke="#5B6B7A" stroke-width="4" stroke-dasharray="6 5" fill="none"'
    if variant == 0:
        walk1 = "M 118 340 L 130 310 L 145 285"
        transit = "M 145 285 L 180 245 L 230 205 L 280 175"
        walk2 = "M 280 175 L 295 158 L 305 148"
        t_style = 'stroke="#E30613" stroke-width="7" fill="none" stroke-linecap="round"'
        badge = ("7A", "#E30613", 198, 228)
    else:
        walk1 = "M 100 355 L 115 325 L 130 300"
        transit = "M 130 300 L 165 265 L 220 220 L 275 190"
        walk2 = "M 275 190 L 290 172 L 302 158"
        t_style = 'stroke="#1565C0" stroke-width="7" fill="none" stroke-linecap="round"'
        badge = ("11", "#1565C0", 185, 248)
    bx, by, bl, bc = badge[2], badge[3], badge[0], badge[1]
    return f"""
  <g>
    <path d="{walk1}" {walk_style}/>
    <path d="{transit}" {t_style}/>
    <path d="{walk2}" {walk_style}/>
    <rect x="{bx}" y="{by}" width="28" height="18" rx="4" fill="#FFFFFF" stroke="{bc}" stroke-width="1.2"/>
    <text x="{bx + 14}" y="{by + 13}" text-anchor="middle" font-family="Roboto, sans-serif" font-size="11" font-weight="700" fill="{bc}">{bl}</text>
    <circle cx="118" cy="340" r="9" fill="#00B341" stroke="#FFFFFF" stroke-width="2.5"/>
    <circle cx="118" cy="340" r="4" fill="#FFFFFF"/>
    <circle cx="305" cy="148" r="9" fill="#B42318" stroke="#FFFFFF" stroke-width="2.5"/>
    <circle cx="305" cy="148" r="4" fill="#FFFFFF"/>
  </g>"""


def map_pick_crosshair(cx: int, cy: int, label: str) -> str:
    return f"""
  <g>
    <line x1="{cx - 18}" y1="{cy}" x2="{cx + 18}" y2="{cy}" stroke="#1F1F1F" stroke-width="2"/>
    <line x1="{cx}" y1="{cy - 18}" x2="{cx}" y2="{cy + 18}" stroke="#1F1F1F" stroke-width="2"/>
    <circle cx="{cx}" cy="{cy}" r="14" fill="none" stroke="#00B341" stroke-width="2"/>
    <text x="{cx}" y="{cy + 36}" text-anchor="middle" font-family="Roboto, sans-serif" font-size="11" fill="#1F1F1F">{T(label)}</text>
  </g>"""


def _route_variant_dots(y: int, count: int, active: int) -> str:
    if count <= 1:
        return ""
    parts = []
    for i in range(count):
        dot_x = W / 2 - (count - 1) * 6 + i * 12
        dot_fill = "#00B341" if i == active else "#D0CBC3"
        parts.append(f'<circle cx="{dot_x}" cy="{y}" r="3" fill="{dot_fill}"/>')
    return "".join(parts)


def _walk_icon(cx: int, cy: int) -> str:
    col = "#5B6B7A"
    return f"""
    <circle cx="{cx}" cy="{cy}" r="14" fill="#EEF1F4"/>
    <circle cx="{cx}" cy="{cy - 4}" r="3" fill="{col}"/>
    <path d="M {cx - 6} {cy + 6} L {cx} {cy - 1} L {cx + 6} {cy + 6}" fill="none" stroke="{col}" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>"""


def _transit_icon(cx: int, cy: int, num: str, color: str) -> str:
    bg = "#FCE8E8" if color == "#E30613" else "#E3EDFA"
    return f"""
    <circle cx="{cx}" cy="{cy}" r="14" fill="{bg}"/>
    <rect x="{cx - 10}" y="{cy - 5}" width="20" height="10" rx="2" fill="none" stroke="{color}" stroke-width="1.6"/>
    <text x="{cx}" y="{cy + 12}" text-anchor="middle" font-family="Roboto, sans-serif" font-size="8" font-weight="700" fill="{color}">{T(num)}</text>"""


def _variant_leg_row(card_x: int, row_y: int, leg: dict) -> str:
    cx = card_x + 22
    cy = row_y + 22
    time_txt = f'{leg["time"]} min'
    if leg["mode"] == "walk":
        row = _walk_icon(cx, cy)
        row += f'<text x="{card_x + 44}" y="{row_y + 26}" font-family="Roboto, Noto Sans, sans-serif" font-size="14" fill="#1F1F1F">{T("Pešačenje")}</text>'
        row += f'<text x="{card_x + ROUTE_CARD_W - 12}" y="{row_y + 26}" text-anchor="end" font-family="Roboto, sans-serif" font-size="14" font-weight="600" fill="#1F1F1F">{time_txt}</text>'
    else:
        color = leg.get("color", "#E30613")
        row = _transit_icon(cx, cy, leg["num"], color)
        row += f'<text x="{card_x + 44}" y="{row_y + 20}" font-family="Roboto, Noto Sans, sans-serif" font-size="14" fill="#1F1F1F">{T(leg["type"])}</text>'
        row += f'<text x="{card_x + 44}" y="{row_y + 36}" font-family="Roboto, sans-serif" font-size="12" fill="#6A655C">{T(leg["num"])}</text>'
        row += f'<text x="{card_x + ROUTE_CARD_W - 12}" y="{row_y + 28}" text-anchor="end" font-family="Roboto, sans-serif" font-size="14" font-weight="600" fill="#1F1F1F">{time_txt}</text>'
    row += f'<line x1="{card_x + 8}" y1="{row_y + 44}" x2="{card_x + ROUTE_CARD_W - 8}" y2="{row_y + 44}" stroke="#E4DDD3"/>'
    return row


def _variant_card(card_x: int, card_y: int, card_h: int, variant: dict, *, active: bool) -> str:
    stroke = "#00B341" if active else "#E4DDD3"
    sw = "2" if active else "1"
    transfers = variant["transfers"]
    tr_label = "bez presedanja" if transfers == 0 else f"{transfers} presedanje" if transfers == 1 else f"{transfers} presedanja"
    parts = [
        f'<rect x="{card_x}" y="{card_y}" width="{ROUTE_CARD_W}" height="{card_h}" fill="#FFFFFF" stroke="{stroke}" stroke-width="{sw}"/>',
        f'<text x="{card_x + 14}" y="{card_y + 28}" font-family="Roboto, Noto Sans, sans-serif" font-size="18" font-weight="650" fill="#1F1F1F">{variant["duration"]} min</text>',
        f'<text x="{card_x + 14}" y="{card_y + 48}" font-family="Roboto, sans-serif" font-size="12" fill="#6A655C">{T(tr_label)}</text>',
    ]
    ly = card_y + 58
    for leg in variant["legs"]:
        parts.append(_variant_leg_row(card_x, ly, leg))
        ly += 48
    return "".join(parts)


def _route_carousel(results_top: int, results_h: int, variants: list[dict], active: int, *, scroll: bool = False) -> str:
    dots_y = results_top + 36
    carousel_top = dots_y + 14
    card_h = results_top + results_h - carousel_top - 4
    clip_id = "route-carousel-clip"
    parts = [_route_variant_dots(dots_y, len(variants), active)]
    parts.append(f'<defs><clipPath id="{clip_id}"><rect x="0" y="{carousel_top}" width="{W}" height="{card_h}"/></clipPath></defs>')
    parts.append(f'<g clip-path="url(#{clip_id})">')
    for i, variant in enumerate(variants):
        cx = ROUTE_CARD_X0 + (i - active) * (ROUTE_CARD_W + ROUTE_CARD_GAP)
        parts.append(_variant_card(cx, carousel_top, card_h, variant, active=(i == active)))
    parts.append("</g>")
    if scroll:
        fade_y = results_top + results_h - 36
        parts.append(f"""
    <defs>
      <linearGradient id="route-scroll-fade" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0%" stop-color="#FFFFFF" stop-opacity="0"/>
        <stop offset="100%" stop-color="#FFFFFF" stop-opacity="0.95"/>
      </linearGradient>
    </defs>
    <rect x="0" y="{fade_y}" width="{W}" height="36" fill="url(#route-scroll-fade)"/>""")
    return "".join(parts)


def route_stack(
    results_h: int,
    *,
    step: str = "step2",
    from_label: str = "Trg Slobode",
    to_label: str = "Liman Park",
    active_variant: int = 0,
    variants: list[dict] | None = None,
    scroll: bool = False,
    **route_panel_kwargs,
) -> str:
    """Route results grow flush from from/to panel — one block, no gap, no corner radius."""
    variants = variants if variants is not None else ROUTE_VARIANTS
    results_top = ROUTE_PANEL_Y - results_h
    stack_h = results_h + ROUTE_PANEL_H
    parts = [
        f'<rect x="0" y="{results_top}" width="{W}" height="{stack_h}" fill="#FFFFFF"/>',
        f'<line x1="0" y1="{results_top}" x2="{W}" y2="{results_top}" stroke="#E4DDD3"/>',
        f'<rect x="177" y="{results_top + 10}" width="36" height="4" rx="2" fill="#D0CBC3"/>',
        f'<circle cx="358" cy="{results_top + 28}" r="16" fill="#F3EFE8"/>',
        f'<text x="358" y="{results_top + 33}" text-anchor="middle" font-family="Roboto, sans-serif" font-size="16" fill="#6A655C">{T("×")}</text>',
    ]
    if step == "step1":
        dots_y = results_top + 36
        parts.append(_route_variant_dots(dots_y, len(variants), active_variant))
        text_y = dots_y + (22 if len(variants) > 1 else 8)
        parts.append(
            f'<text x="16" y="{text_y}" font-family="Roboto, Noto Sans, sans-serif" font-size="15" font-weight="600" fill="#1F1F1F">{T(from_label)}</text>'
        )
        parts.append(
            f'<text x="16" y="{text_y + 18}" font-family="Roboto, sans-serif" font-size="12" fill="#6A655C">→ {T(to_label)}</text>'
        )
    else:
        parts.append(_route_carousel(results_top, results_h, variants, active_variant, scroll=scroll))
    parts.append(route_panel(embedded=True, **route_panel_kwargs))
    return f"<g>{''.join(parts)}</g>"


def org_card_open() -> str:
    org_h = 300
    y_org = SHEET_BOTTOM_SEARCH - org_h
    fields = f"""
    <text x="16" y="{y_org + 108}" font-family="Roboto, Noto Sans, sans-serif" font-size="13" fill="#6A655C">Telefon</text>
    <text x="108" y="{y_org + 108}" font-family="Roboto, Noto Sans, sans-serif" font-size="14" fill="#00B341">021 450 112</text>
    <text x="16" y="{y_org + 140}" font-family="Roboto, Noto Sans, sans-serif" font-size="13" fill="#6A655C">Adresa</text>
    <text x="108" y="{y_org + 140}" font-family="Roboto, Noto Sans, sans-serif" font-size="14" fill="#1F1F1F">{T("Bulevar oslobođenja 47")}</text>
    <text x="16" y="{y_org + 172}" font-family="Roboto, Noto Sans, sans-serif" font-size="13" fill="#6A655C">{T("Radno vreme")}</text>
    <text x="108" y="{y_org + 172}" font-family="Roboto, Noto Sans, sans-serif" font-size="14" fill="#1F1F1F">08:00-21:00</text>"""
    return sheet(org_h, sheet_header(y_org, "Apoteka Benu", "Apoteka") + fields)


def snackbar_v2(text: str) -> str:
    y = ROUTE_PANEL_Y - 52
    w = min(358, 28 + 7 * len(text))
    x = (W - w) / 2
    return f"""
  <g>
    <rect x="{x}" y="{y}" width="{w}" height="40" rx="20" fill="#2B2B2B"/>
    <text x="{W/2}" y="{y + 25}" text-anchor="middle" font-family="Roboto, Noto Sans, sans-serif" font-size="13" fill="#FFFFFF">{T(text)}</text>
  </g>"""


def build() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    route_ui = ROUTE_PANEL_H + TAB_H
    files: dict[str, str] = {}
    variants_multi = ROUTE_VARIANTS

    files["01-browse-search-tab.svg"] = screen(
        "Browse: search tab active (default)",
        map_base(),
        status_bar(),
        map_controls_v2(),
        search_panel(),
        bottom_tab_bar(active="search"),
        nav_bar(),
    )

    files["02-route-tab-empty.svg"] = screen(
        "Route tab: empty panel",
        map_base(),
        status_bar(),
        map_controls_v2(bottom_ui=route_ui),
        route_panel(),
        route_tab_bar(),
        nav_bar(),
    )

    files["03-route-from-search.svg"] = screen(
        "Route: From field + autocomplete",
        map_base(),
        status_bar(),
        map_controls_v2(bottom_ui=route_ui + 120),
        route_dropdown(
            [
                {"kind": "ADR", "label": "Trg Slobode", "sub": "Adresa"},
                {"kind": "ADR", "label": "Bulevar oslobođenja 47", "sub": "Adresa"},
            ],
            header="Od",
        ),
        route_panel(active_field="from"),
        route_tab_bar(),
        nav_bar(),
    )

    files["04-route-map-pick-to.svg"] = screen(
        "Route: map pick for To",
        map_base(close=True),
        map_pick_crosshair(280, 310, "Tapni tačku Do"),
        status_bar(),
        map_controls_v2(bottom_ui=route_ui),
        route_panel(from_val="Trg Slobode", active_field="to", map_pick_active="to"),
        route_tab_bar("Trg Slobode", ""),
        nav_bar(),
    )

    files["05-route-ready.svg"] = screen(
        "Route: both points — Napravi rutu tab",
        map_base(close=True),
        status_bar(),
        map_controls_v2(bottom_ui=route_ui),
        route_panel(from_val="Trg Slobode", to_val="Liman Park", mode="transit"),
        route_tab_bar("Trg Slobode", "Liman Park"),
        nav_bar(),
    )

    files["06-route-loading.svg"] = screen(
        "Route: build tapped — calculating",
        map_base(close=True),
        status_bar(),
        map_controls_v2(bottom_ui=route_ui),
        route_panel(from_val="Trg Slobode", to_val="Liman Park", mode="transit")
        + """
  <g>
    <circle cx="195" cy="380" r="22" fill="#FFFFFF" filter="url(#sh-btn)"/>
    <path d="M 195 358 A 22 22 0 1 1 194 358" fill="none" stroke="#00B341" stroke-width="3" stroke-linecap="round"/>
    <text x="195" y="420" text-anchor="middle" font-family="Roboto, sans-serif" font-size="13" fill="#6A655C">Prora&#269;unavam rutu…</text>
  </g>""",
        route_tab_bar("Trg Slobode", "Liman Park", loading=True),
        nav_bar(),
    )

    route_result_ui = route_ui + ROUTE_SHEET_STEP2_H
    route_kwargs = dict(from_val="Trg Slobode", to_val="Liman Park", mode="transit")

    files["07-route-result-peek.svg"] = screen(
        "Route result: step1 — from/to + dots",
        map_base(close=True),
        route_line(),
        status_bar(),
        map_controls_v2(bottom_ui=route_ui + ROUTE_SHEET_STEP1_H),
        route_stack(
            ROUTE_SHEET_STEP1_H,
            step="step1",
            variants=variants_multi,
            active_variant=0,
            **route_kwargs,
        ),
        route_tab_bar("Trg Slobode", "Liman Park"),
        nav_bar(),
    )

    files["08-route-result-half.svg"] = screen(
        "Route result: step2 — centered variant cards",
        map_base(close=True),
        route_line(variant=0),
        status_bar(),
        map_controls_v2(bottom_ui=route_result_ui),
        route_stack(
            ROUTE_SHEET_STEP2_H,
            step="step2",
            variants=variants_multi,
            active_variant=0,
            **route_kwargs,
        ),
        route_tab_bar("Trg Slobode", "Liman Park"),
        nav_bar(),
    )

    files["09-route-result-full.svg"] = screen(
        "Route result: step3 — expanded + scroll",
        map_base(close=True),
        route_line(variant=0),
        status_bar(),
        map_controls_v2(bottom_ui=route_ui + ROUTE_SHEET_STEP3_H),
        route_stack(
            ROUTE_SHEET_STEP3_H,
            step="step3",
            variants=variants_multi,
            active_variant=0,
            scroll=True,
            **route_kwargs,
        ),
        route_tab_bar("Trg Slobode", "Liman Park"),
        nav_bar(),
    )

    files["10-org-selected-search-tab.svg"] = screen(
        "Org selected — search tab (tap Ruta for route)",
        map_base(close=True),
        highlight_building(),
        teardrop(121, 446),
        status_bar(),
        map_controls_v2(sheet_h=300),
        org_card_open(),
        search_panel(),
        bottom_tab_bar(active="search"),
        nav_bar(),
    )

    files["11-org-switch-route-tab.svg"] = screen(
        "Org selected — route tab, To = address",
        map_base(close=True),
        teardrop(121, 446),
        status_bar(),
        map_controls_v2(bottom_ui=route_ui),
        route_panel(
            from_val="Moja lokacija",
            to_val="Bulevar oslobođenja 47",
            loc_enabled=True,
            mode="transit",
        ),
        route_tab_bar("Moja lokacija", "Bulevar oslobođenja 47"),
        nav_bar(),
    )

    files["12-error-outside-city.svg"] = screen(
        "Route error: outside city",
        map_base(),
        status_bar(),
        map_controls_v2(bottom_ui=route_ui),
        route_panel(from_val="Trg Slobode", to_val="Beograd", mode="transit"),
        snackbar_v2("Tačke moraju biti u Novom Sadu"),
        route_tab_bar("Trg Slobode", "Beograd"),
        nav_bar(),
    )

    files["13-error-no-route.svg"] = screen(
        "Route error: no path",
        map_base(close=True),
        snackbar_v2("Nije pronađena ruta"),
        status_bar(),
        map_controls_v2(bottom_ui=route_ui),
        route_panel(from_val="Trg Slobode", to_val="Liman Park", mode="transit"),
        route_tab_bar("Trg Slobode", "Liman Park"),
        nav_bar(),
    )

    files["14-error-timeout.svg"] = screen(
        "Route error: timeout",
        map_base(close=True),
        snackbar_v2("Ruta trenutno nije dostupna"),
        status_bar(),
        map_controls_v2(bottom_ui=route_ui),
        route_panel(from_val="Trg Slobode", to_val="Liman Park", mode="transit"),
        route_tab_bar("Trg Slobode", "Liman Park"),
        nav_bar(),
    )

    files["15-error-offline.svg"] = screen(
        "Route error: offline",
        map_base(),
        snackbar_v2("Potrebna je mreža za rutu"),
        status_bar(),
        map_controls_v2(bottom_ui=route_ui),
        route_panel(from_val="Trg Slobode", to_val="Liman Park"),
        route_tab_bar("Trg Slobode", "Liman Park"),
        nav_bar(),
    )

    files["16-search-tab-restored.svg"] = screen(
        "Route cleared — search tab",
        map_base(),
        status_bar(),
        map_controls_v2(),
        search_panel(),
        bottom_tab_bar(active="search"),
        nav_bar(),
    )

    files["17-tab-switch-search-to-route.svg"] = screen(
        "Tab switch: search → route",
        map_base(),
        status_bar(),
        map_controls_v2(bottom_ui=route_ui),
        route_panel(),
        route_tab_bar(),
        nav_bar(),
    )

    files["18-route-swap.svg"] = screen(
        "Route: swap From/To",
        map_base(close=True),
        status_bar(),
        map_controls_v2(bottom_ui=route_ui),
        route_panel(
            from_val="Liman Park",
            to_val="Trg Slobode",
            mode="transit",
            swap_highlight=True,
        ),
        route_tab_bar("Liman Park", "Trg Slobode"),
        nav_bar(),
    )

    for name, svg in files.items():
        (OUT / name).write_text(svg, encoding="utf-8", newline="\n")

    print(f"wrote {len(files)} files to {OUT}")


if __name__ == "__main__":
    build()
