import re, sys, urllib.request, io
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

NAMES = """arrow_back arrow_forward arrow_upward battery_charging_full battery_saver bluetooth
cell_tower chat check check_circle close done_all e911_emergency filter_list forward_media
g_translate graphic_eq group group_add hearing home hub link location_on lock memory memory_alt
mic mic_double near_me network_ping offline_bolt offline_pin person phone_android play_arrow
play_circle podcasts radar radio_button_unchecked record_voice_over replay reply router rule
search sensors settings signal_cellular_alt slow_motion_video smartphone speed ssid_chart star
straighten support_agent sync sync_alt timelapse translate tune verified verified_user vibration
volume_off volume_up warning wifi wifi_find wifi_tethering""".split()

BASE = ("https://raw.githubusercontent.com/google/material-design-icons/master/symbols/web/"
        "{n}/materialsymbolsoutlined/{n}_24px.svg")

def camel(s):
    return "".join(p.capitalize() for p in s.split("_"))

ok, bad = {}, []
for n in NAMES:
    try:
        req = urllib.request.Request(BASE.format(n=n), headers={"User-Agent":"curl/8"})
        svg = urllib.request.urlopen(req, timeout=60).read().decode()
        ds = re.findall(r'<path[^>]*\sd="([^"]+)"', svg)
        vb = re.search(r'viewBox="([-\d.]+)\s+([-\d.]+)\s+([-\d.]+)\s+([-\d.]+)"', svg)
        if not ds or not vb:
            bad.append(n); continue
        ok[n] = (ds, tuple(float(x) for x in vb.groups()))
    except Exception as e:
        bad.append(f"{n} ({e})")

print(f"fetched {len(ok)}/{len(NAMES)}; failed: {bad}", file=sys.stderr)

out = io.StringIO()
out.write('''package com.itantra.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Material Symbols Outlined, as vectors — GENERATED, do not hand-edit.
 *
 * Regenerate with tools/gen-material-icons.py, which fetches the canonical SVG for each
 * glyph from google/material-design-icons and emits this file.
 *
 * ## Why generated rather than the icon font
 *
 * The Stitch design specifies Material Symbols. Shipping the variable font would be ~7 MB
 * for seventy glyphs, cannot be tinted per-use as cleanly, and needs codepoint lookups at
 * every call site. These are the same artwork as vectors: a few KB in total, tintable, and
 * crisp at the 96 dp the talk button needs.
 *
 * ## The viewBox
 *
 * Material Symbols are authored in a 960-unit box with a **negative Y origin**
 * (`viewBox="0 -960 960 960"`). Compose's ImageVector has no viewBox offset, so every path
 * sits inside a group translated down by 960. Without that translation each icon renders
 * entirely off-screen, which looks exactly like a missing icon.
 */
object MsIcons {

''')

for n in NAMES:
    if n not in ok: continue
    ds, (vx, vy, vw, vh) = ok[n]
    out.write(f'    val {camel(n)}: ImageVector by lazy {{ icon(\n')
    out.write(f'        "{camel(n)}", {vw}f, {vh}f, {-vy}f,\n')
    for d in ds:
        out.write(f'        "{d}",\n')
    out.write('    ) }\n\n')

out.write('''    /** Every glyph, by its Material Symbols name — for data-driven lookups. */
    val byName: Map<String, ImageVector> by lazy {
        mapOf(
''')
for n in NAMES:
    if n in ok:
        out.write(f'            "{n}" to {camel(n)},\n')
out.write('''        )
    }

    private fun icon(
        name: String,
        vw: Float,
        vh: Float,
        shiftY: Float,
        vararg pathData: String,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = vw,
        viewportHeight = vh,
    ).apply {
        // See the class note: the negative-Y viewBox is compensated here.
        addGroup(name = name, translationY = shiftY)
        pathData.forEach {
            addPath(
                pathData = PathParser().parsePathString(it).toNodes(),
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.EvenOdd,
            )
        }
        clearGroup()
    }.build()
}
''')
print(out.getvalue())
