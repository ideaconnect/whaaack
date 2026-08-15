"""Assemble and pre-flight the Play Games Services Game Stats upload.

    python tools/build_game_stats_zip.py          # validate, then write the ZIP
    python tools/build_game_stats_zip.py --check  # validate only

Reads assets/game-stats/ and writes assets/game-stats/GameStats.zip, ready for the Play
Console's "Set up stats" upload. assets/game-stats/PlayerGameEvent.csv is uploaded
separately, at the "Set up events" step, and is validated here too because the stats
config is only meaningful against it.

Everything below is a rule from developer.android.com/games/pgs/integrate-gamestats,
checked here rather than discovered in the console. That is the whole point of this
file: console validation is a slow round trip, it reports one problem at a time, and
Game Stats **cannot be deleted once published** — so a stat that names the wrong
property is not a mistake you get to take back. Every check names the rule it enforces
so a future failure is actionable rather than mysterious.

Note the one rule this cannot check: whether a stat *means* what its description says.
The console will happily accept a MAX over the wrong property.
"""

from __future__ import annotations

import argparse
import csv
import io
import re
import sys
import zipfile
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
STATS = ROOT / "assets" / "game-stats"
ICONS = STATS / "icons"
OUT = STATS / "GameStats.zip"

EVENTS_CSV = "PlayerGameEvent.csv"
REPETITIVE_CSV = "RepetitiveStatsConfig.csv"
PROGRESSION_CSV = "ProgressionStatConfig.csv"
LOCALIZATIONS_CSV = "StatLocalizations.csv"

# Column order, verbatim from each format section's "in the following order" line. The
# console reads these files **positionally**, so this is the part that actually matters —
# and it is where the documentation is least reliable, because every one of these sections
# also carries a field table that disagrees with its own order line.
#
# ProgressionStatConfig is the trap: its field table documents an "Event Name" column that
# the order line and the worked example both omit. Including it shifts every later column
# by one, and the console then reads the description as the icon filename and reports a
# missing icon named after a sentence. Learned the hard way; hence CHECKS the header here.
EVENTS_COLUMNS = ["Event Name", "Property Name", "Property Type"]
REPETITIVE_COLUMNS = [
    "Stat Id", "Event Name", "Event Property Name", "Aggregation Type",
    "Stat Display Name", "Stat Description", "Filter Property", "Filter Operator",
    "Filter Value", "Is Competitive", "Min limit", "Max limit", "Icon File Name",
    "Good value direction", "Unit",
]
PROGRESSION_COLUMNS = [
    "Stat Id", "Event Property Name", "Stat Display Name", "Stat Description",
    "Icon File Name", "Good value direction", "Unit",
]
LOCALIZATIONS_COLUMNS = [
    "Stat Id", "Stat Display Name", "Locale",
    "Localized Stat Display Name", "Localized Stat Description",
]

# The progression stat has no Event Name column because it can only ever be built on the
# console's predefined event.
PROGRESSION_EVENT = "progressUpdate"

# Not a typo: the two stat files really do differ here, 500 against 50.
MAX_REPETITIVE_DESCRIPTION = 500
MAX_PROGRESSION_DESCRIPTION = 50
MAX_DISPLAY_NAME = 50

NAME_RE = re.compile(r"^[A-Za-z0-9_]+$")
PROPERTY_TYPES = {"INT64", "DOUBLE", "STRING", "BOOL"}
NUMERIC_TYPES = {"INT64", "DOUBLE"}
AGGREGATIONS = {"SUM", "MAX", "MIN", "COUNT"}
DIRECTIONS = {"INCREASING", "DECREASING"}
OPERATORS = {"=", "!=", ">", "<", ">=", "<="}

# The complete set, from the "Unit specification" table on /games/pgs/gamestats. Worth
# stating in full because the *prose* on the same page offers "SECOND" as an example and the
# worked example row uses "seconds", and neither exists: there is no time unit. Both were
# rejected by the console with "Nieprawidlowa jednostka" naming only the row.
#
# So a duration stat has no unit and displays as a bare number, which is why the display
# names here say what they are counting.
UNITS = {
    "", "UNITLESS", "NONE",
    "%", "PERCENTAGE", "PERCENT",
    "CENTIMETER", "METER", "KILOMETER", "FOOT", "INCH", "MILE", "YARD",
    "MILLIMETER", "NANOMETER",
    "KILOMETER_PER_HOUR", "METER_PER_SECOND", "MILE_PER_HOUR",
}

# Reserved by the console for property names — they read like the keywords of whatever
# query language the filters compile into.
RESERVED = {"id", "count", "key", "value", "where", "from", "and", "or", "true", "false"}

MAX_EVENTS = 20
MAX_PROPERTIES_PER_EVENT = 20
MAX_STATS = 50
MAX_ZIP_FILES = 53
MAX_ZIP_BYTES = 10 * 1024 * 1024
MAX_ICON_BYTES = 1024 * 1024
ICON_SIZE = (512, 512)
ICON_SUFFIXES = {".png", ".jpg", ".jpeg"}


class Problems:
    def __init__(self) -> None:
        self.items: list[str] = []

    def check(self, ok: bool, message: str) -> bool:
        if not ok:
            self.items.append(message)
        return ok


def table(path: Path, columns: list[str], p: Problems) -> list[dict[str, str]]:
    """Rows keyed by [columns], after checking the file really is in that shape.

    Deliberately not `csv.DictReader`: that keys off whatever header the file happens to
    carry, so it would cheerfully validate a file whose columns are in an order the console
    will read differently — which is the one mistake that cannot be seen in the output.
    """
    with path.open(encoding="utf-8-sig", newline="") as f:
        raw = [row for row in csv.reader(f) if any(cell.strip() for cell in row)]
    if not raw:
        return []

    header = [cell.strip() for cell in raw[0]]
    if not p.check(header == columns,
                   f"{path.name}: header is {header}, expected exactly {columns} - "
                   f"the console reads these files positionally"):
        return []

    out: list[dict[str, str]] = []
    for i, row in enumerate(raw[1:], start=2):
        if not p.check(len(row) == len(columns),
                       f"{path.name} line {i}: {len(row)} cells, expected {len(columns)}"):
            continue
        out.append({k: v.strip() for k, v in zip(columns, row)})
    return out


def validate_events(p: Problems) -> dict[str, str]:
    """Returns {property name: type} for the single declared event, checking as it goes."""
    path = STATS / EVENTS_CSV
    if not p.check(path.exists(), f"{EVENTS_CSV} is missing"):
        return {}

    data = table(path, EVENTS_COLUMNS, p)
    p.check(bool(data), f"{EVENTS_CSV} needs at least one row")

    by_event: dict[str, dict[str, str]] = {}
    for i, row in enumerate(data, start=2):
        event, prop, kind = row.get("Event Name", ""), row.get("Property Name", ""), row.get("Property Type", "")
        where = f"{EVENTS_CSV} line {i}"
        p.check(len(event) <= 50 and bool(NAME_RE.match(event)),
                f"{where}: event name '{event}' must be <=50 chars of letters, numbers, underscores")
        p.check(len(prop) <= 50 and bool(NAME_RE.match(prop)),
                f"{where}: property name '{prop}' must be <=50 chars of letters, numbers, underscores")
        p.check(prop.lower() not in RESERVED,
                f"{where}: property name '{prop}' is reserved by the console")
        p.check(kind in PROPERTY_TYPES,
                f"{where}: property type '{kind}' must be one of {sorted(PROPERTY_TYPES)} (case-sensitive)")
        slot = by_event.setdefault(event, {})
        p.check(prop not in slot, f"{where}: property '{prop}' declared twice on '{event}'")
        slot[prop] = kind

    p.check(len(by_event) <= MAX_EVENTS, f"{EVENTS_CSV}: {len(by_event)} events, limit is {MAX_EVENTS}")
    for event, props in by_event.items():
        p.check(len(props) <= MAX_PROPERTIES_PER_EVENT,
                f"{EVENTS_CSV}: event '{event}' has {len(props)} properties, limit is {MAX_PROPERTIES_PER_EVENT}")
        if event == "progressUpdate":
            p.check(props.get("currentProgress") in {"INT64", "STRING"},
                    f"{EVENTS_CSV}: progressUpdate must declare currentProgress as INT64 or STRING")

    return {f"{e}.{k}": v for e, props in by_event.items() for k, v in props.items()}


def validate_stats(p: Problems, declared: dict[str, str]) -> tuple[list[str], list[str], list[str]]:
    """Checks both stat configs. Returns (icon filenames, stat ids, display names)."""
    icons: list[str] = []
    ids: list[str] = []
    names: list[str] = []

    present = [n for n in (REPETITIVE_CSV, PROGRESSION_CSV) if (STATS / n).exists()]
    p.check(bool(present), f"need at least one of {REPETITIVE_CSV} or {PROGRESSION_CSV}")

    for name in present:
        repetitive = name == REPETITIVE_CSV
        columns = REPETITIVE_COLUMNS if repetitive else PROGRESSION_COLUMNS
        max_description = MAX_REPETITIVE_DESCRIPTION if repetitive else MAX_PROGRESSION_DESCRIPTION
        data = table(STATS / name, columns, p)
        p.check(bool(data), f"{name} is present but empty - omit the file instead")

        for i, row in enumerate(data, start=2):
            where = f"{name} line {i}"
            stat_id = row["Stat Id"]
            display = row["Stat Display Name"]
            # Only the repetitive file names its event; a progression stat can be built on
            # nothing but the predefined one, so the column does not exist.
            event = row["Event Name"] if repetitive else PROGRESSION_EVENT
            prop = row["Event Property Name"]
            icon = row["Icon File Name"]

            p.check(len(stat_id) <= 100 and bool(NAME_RE.match(stat_id)),
                    f"{where}: stat id '{stat_id}' must be <=100 chars of letters, numbers, underscores")
            p.check(bool(display) and len(display) <= MAX_DISPLAY_NAME,
                    f"{where}: display name '{display}' must be present and <={MAX_DISPLAY_NAME} chars")
            p.check(len(row["Stat Description"]) <= max_description,
                    f"{where}: description is {len(row['Stat Description'])} characters, "
                    f"the limit in {name} is {max_description}")
            p.check(f"{event}.{prop}" in declared,
                    f"{where}: '{event}.{prop}' is not declared in {EVENTS_CSV}")
            p.check(row["Good value direction"] in DIRECTIONS,
                    f"{where}: good value direction must be INCREASING or DECREASING")
            p.check(bool(icon), f"{where}: icon file name is required")
            # Cheap canary for a column-order mistake: when the columns are shifted, the cell
            # the console reads as the icon is prose, and this says so in one line instead of
            # the console reporting a missing icon file named after a sentence.
            p.check(Path(icon).suffix.lower() in ICON_SUFFIXES,
                    f"{where}: icon '{icon[:40]}' does not look like a filename - "
                    f"check the column order against {columns}")

            unit = row["Unit"]
            kind = declared.get(f"{event}.{prop}")
            p.check(unit.upper() in UNITS,
                    f"{where}: unit '{unit}' is not one of {sorted(UNITS - {''})} "
                    f"(or blank). There is no time unit - the docs' 'SECOND' does not exist")
            if unit and kind:
                p.check(kind in NUMERIC_TYPES,
                        f"{where}: unit '{unit}' set on a {kind} property; only INT64/DOUBLE may carry a unit")

            if repetitive:
                p.check(row["Aggregation Type"] in AGGREGATIONS,
                        f"{where}: aggregation must be one of {sorted(AGGREGATIONS)}")

                competitive = row["Is Competitive"].upper()
                p.check(competitive in {"TRUE", "FALSE"},
                        f"{where}: 'Is Competitive' must be true or false")
                if competitive == "TRUE":
                    p.check(bool(row["Min limit"]) and bool(row["Max limit"]),
                            f"{where}: min and max limit are required when the stat is competitive")

                filter_prop = row["Filter Property"]
                filter_op = row["Filter Operator"]
                filter_val = row["Filter Value"]
                if filter_prop or filter_op or filter_val:
                    p.check(bool(filter_prop) and bool(filter_op) and bool(filter_val),
                            f"{where}: a filter needs all three of property, operator and value")
                    p.check(f"{event}.{filter_prop}" in declared,
                            f"{where}: filter property '{filter_prop}' is not declared on '{event}'")
                    p.check(filter_op in OPERATORS,
                            f"{where}: filter operator '{filter_op}' must be one of {sorted(OPERATORS)}")
            else:
                p.check(len(data) == 1,
                        f"{name}: {len(data)} rows - only one progression stat is allowed")

            icons.append(icon)
            ids.append(stat_id)
            names.append(display)

    p.check(len(ids) <= MAX_STATS, f"{len(ids)} stats configured, limit is {MAX_STATS}")
    for label, values in (("stat id", ids), ("display name", names)):
        duplicates = {v for v in values if values.count(v) > 1}
        p.check(not duplicates, f"duplicate {label}(s): {sorted(duplicates)} - each must be unique")

    return icons, ids, names


def validate_localizations(p: Problems, ids: list[str], names: list[str]) -> None:
    path = STATS / LOCALIZATIONS_CSV
    if not path.exists():
        return
    for i, row in enumerate(table(path, LOCALIZATIONS_COLUMNS, p), start=2):
        where = f"{LOCALIZATIONS_CSV} line {i}"
        p.check(row["Stat Id"] in ids, f"{where}: unknown stat id '{row['Stat Id']}'")
        p.check(row["Stat Display Name"] in names,
                f"{where}: display name must match the one in the stat config")
        p.check(bool(row["Locale"]), f"{where}: locale is required")
        p.check(len(row["Localized Stat Display Name"]) <= MAX_DISPLAY_NAME,
                f"{where}: localized display name exceeds {MAX_DISPLAY_NAME} characters")
        p.check(len(row["Localized Stat Description"]) <= MAX_REPETITIVE_DESCRIPTION,
                f"{where}: localized description exceeds {MAX_REPETITIVE_DESCRIPTION} characters")


def validate_icons(p: Problems, referenced: list[str]) -> list[Path]:
    if not p.check(ICONS.is_dir(), f"{ICONS.relative_to(ROOT)} is missing - run generate_stat_icons.py"):
        return []

    on_disk = sorted(f for f in ICONS.iterdir() if f.is_file())
    names = {f.name for f in on_disk}

    for icon in referenced:
        p.check(icon in names, f"icon '{icon}' is referenced by a stat but not in {ICONS.name}/")
    for f in on_disk:
        # The console rejects images nothing points at, so an icon left behind by a stat
        # that was renamed fails the upload rather than being ignored.
        p.check(f.name in referenced, f"icon '{f.name}' is in {ICONS.name}/ but no stat references it")
        p.check(f.suffix.lower() in ICON_SUFFIXES,
                f"icon '{f.name}' must be .png, .jpg or .jpeg")
        size = f.stat().st_size
        p.check(size <= MAX_ICON_BYTES,
                f"icon '{f.name}' is {size / 1024:.0f} KB, over the 1 MB cap")
        try:
            with Image.open(f) as im:
                p.check(im.size == ICON_SIZE,
                        f"icon '{f.name}' is {im.size[0]}x{im.size[1]}, must be exactly 512x512")
        except Exception as e:  # noqa: BLE001 — an unreadable icon is a problem, whatever the cause
            p.check(False, f"icon '{f.name}' could not be read: {e}")

    return on_disk


def build(icons: list[Path]) -> bytes:
    """Everything in the archive root — the console rejects subdirectories."""
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as z:
        for name in (REPETITIVE_CSV, PROGRESSION_CSV, LOCALIZATIONS_CSV):
            path = STATS / name
            if path.exists():
                z.write(path, arcname=name)
        for icon in icons:
            z.write(icon, arcname=icon.name)
    return buffer.getvalue()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="validate without writing the ZIP")
    args = parser.parse_args()

    p = Problems()
    declared = validate_events(p)
    referenced, ids, names = validate_stats(p, declared)
    validate_localizations(p, ids, names)
    icons = validate_icons(p, referenced)

    payload = build(icons)
    with zipfile.ZipFile(io.BytesIO(payload)) as z:
        entries = z.infolist()
    uncompressed = sum(e.file_size for e in entries)
    p.check(len(entries) <= MAX_ZIP_FILES, f"{len(entries)} files in the ZIP, limit is {MAX_ZIP_FILES}")
    p.check(uncompressed <= MAX_ZIP_BYTES,
            f"ZIP is {uncompressed / 1024 / 1024:.1f} MB uncompressed, limit is 10 MB")

    if p.items:
        print(f"{len(p.items)} problem(s):", file=sys.stderr)
        for item in p.items:
            print(f"  - {item}", file=sys.stderr)
        return 1

    print(f"{EVENTS_CSV}: {len(declared)} declared propert{'y' if len(declared) == 1 else 'ies'}")
    print(f"stats: {len(ids)} ({', '.join(ids)})")
    print(f"ZIP:   {len(entries)} files, {uncompressed / 1024:.0f} KB uncompressed")

    if args.check:
        print("checks passed; --check given, so nothing written")
        return 0

    OUT.write_bytes(payload)
    print(f"wrote {OUT.relative_to(ROOT)}  ({len(payload) / 1024:.0f} KB)")
    print(f"upload {EVENTS_CSV} at 'Set up events', then this ZIP at 'Set up stats'")
    return 0


if __name__ == "__main__":
    sys.exit(main())
