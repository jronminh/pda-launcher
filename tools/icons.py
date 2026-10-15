#!/usr/bin/env python3
"""Write every line icon of the app, res/drawable/ic_*.xml, from one table.

One style for all: a 24 x 24 viewport, a white stroke 1.5 wide with round
joins and caps, no fill (the icon is drawn on pure black). The shapes keep to
about 3..21, so they look the same size side by side. Change an icon here and
run `python3 tools/icons.py`; do not edit the XML by hand.
"""
import math
import os

def circle(cx, cy, r):
    return f"M{cx - r:g},{cy}a{r:g},{r:g} 0 1,0 {2 * r:g},0a{r:g},{r:g} 0 1,0 {-2 * r:g},0"

def dot(cx, cy):
    return circle(cx, cy, 0.6)

def gear():
    """Eight square teeth on a ring, and the hub."""
    pts = []
    for k in range(8):
        a = k * 45
        for ang, r in ((a - 16, 6.8), (a - 9, 9.2), (a + 9, 9.2), (a + 16, 6.8)):
            t = math.radians(ang)
            pts.append((12 + r * math.cos(t), 12 + r * math.sin(t)))
    path = "M" + "L".join(f"{x:.2f},{y:.2f}" for x, y in pts) + "Z"
    return path + " " + circle(12, 12, 3)

ICONS = {
    # Files: kinds and places
    "folder":   "M3,6h6l2,2h10v11h-18z",
    "file":     "M6,3h8l5,5v13h-13z M14,3v5h5",
    "doc":      "M6,3h8l5,5v13h-13z M14,3v5h5 M9,12h6 M9,15h6 M9,18h4",
    "image":    "M3,5h18v14h-18z M3,16l5,-5l4,4l3,-3l6,6 " + circle(16, 9, 1.5),
    "video":    "M3,6h13v12h-13z M16,10l5,-3v10l-5,-3",
    "audio":    "M9,17V6L19,4V15 " + circle(6, 17, 3) + " " + circle(16, 15, 3),
    "recent":   circle(12, 12, 9) + " M12,7v5l3,3",
    "storage":  "M5,3h11l4,4v14h-15z M9,3v4 M12,3v4 M15,3v4",
    "download": "M12,4v11 M7,10l5,5l5,-5 M5,20h14",
    # Header and pager actions
    "search":   circle(10, 10, 6) + " M14.5,14.5l5.5,5.5",
    "sort":     "M4,6h16 M4,12h11 M4,18h6",
    "more":     dot(12, 5) + " " + dot(12, 12) + " " + dot(12, 19),
    "back":     "M15,5l-7,7l7,7",
    "forward":  "M9,5l7,7l-7,7",
    "close":    "M6,6l12,12 M18,6l-12,12",
    "check":    "M5,12.5l4.5,4.5l9.5,-10",
    # Today: tasks and the inbox
    "add":       "M12,5v14 M5,12h14",
    "task":      circle(12, 12, 8),
    "task_done": circle(12, 12, 8) + " M8,12.5l3,3l5,-6",
    "inbox":     "M3,13h5l1.5,3h5l1.5,-3h5 M3,13l3,-8h12l3,8v6h-18z",
    # The selection's actions in Files
    "copy":     "M8,8h12v12h-12z M16,8v-4h-12v12h4",
    "move":     "M3,6h6l2,2h10v11h-18z M8,13.5h8 M13,10.5l3,3l-3,3",
    "delete":   "M4,7h16 M9,7v-3h6v3 M6,7l1,14h10l1,-14 M10,11v6 M14,11v6",
    "share":    circle(18, 5, 2.5) + " " + circle(6, 12, 2.5) + " " + circle(18, 19, 2.5)
                + " M8.2,10.8l7.6,-4.6 M8.2,13.2l7.6,4.6",
    "paste":    "M8,4h-3v17h14v-17h-3 M8,3h8v3h-8z",
    "lock":     "M5,11h14v10h-14z M8,11v-4a4,4 0 0,1 8,0v4",
    # The tiles of Applications: a need each
    "phone":    "M5,4h4l2,5l-2.5,1.5a11,11 0 0,0 5,5l1.5,-2.5l5,2v4a2,2 0 0,1 -2,2a16,16 0 0,1 -15,-15a2,2 0 0,1 2,-2z",
    "message":  "M4,5h16v11h-9l-5,4v-4h-2z M8,10.5h0.01 M12,10.5h0.01 M16,10.5h0.01",
    "camera":   "M3,8h4l2,-3h6l2,3h4v11h-18z " + circle(12, 13, 3.5),
    "mail":     "M3,6h18v12h-18z M3,6l9,7l9,-7",
    "clock":    circle(12, 13, 8) + " M12,9v4l2.5,2.5 M5,4l-2,2 M19,4l2,2",
    "calc":     "M6,3h12v18h-12z M8.5,6h7v3h-7z " + " ".join(dot(x, y) for x in (9, 12, 15) for y in (12.5, 15.5, 18.5)),
    "store":    "M4,8h16l-1,12h-14z M9,8v-1a3,3 0 0,1 6,0v1",
    # The viewers
    "play":     "M7,5l12,7l-12,7z",
    "pause":    "M7,5h3v14h-3z M14,5h3v14h-3z",
    "prev":     "M18,6l-9,6l9,6z M6,6v12",
    "next":     "M6,6l9,6l-9,6z M18,6v12",
    "eye":      "M2,12c3,-6 17,-6 20,0c-3,6 -17,6 -20,0z " + circle(12, 12, 3),
    "info":     circle(12, 12, 9) + " M12,11v6 " + dot(12, 7.5),
    # Views
    "view_details": "M4,5h4v4h-4z M11,6h9 M11,8h6 M4,15h4v4h-4z M11,16h9 M11,18h6",
    "view_list":    "M4,6h2 M9,6h11 M4,12h2 M9,12h11 M4,18h2 M9,18h11",
    "view_grid":    "M4,4h6v6h-6z M14,4h6v6h-6z M4,14h6v6h-6z M14,14h6v6h-6z",
    "briefcase": "M3,8h18v11h-18z M9,8v-3h6v3 M3,13h18 M11,12h2v2h-2z",
    # Settings tiles
    "home":      "M4,11l8,-7l8,7 M6,9v11h12v-11 M10,20v-6h4v6",
    "apps":      " ".join(circle(x, y, 3) for x, y in ((7, 7), (17, 7), (7, 17), (17, 17))),
    "wifi":      "M2,9a15,15 0 0,1 20,0 M5,12.5a10,10 0 0,1 14,0 M8.5,16a5,5 0 0,1 7,0 " + dot(12, 19.5),
    "bluetooth": "M7,7l10,10l-5,4v-18l5,4l-10,10",
    "display":   "M3,4h18v12h-18z M8,20h8 M12,16v4",
    "sound":     "M4,9h4l5,-4v14l-5,-4h-4z M16,9a4,4 0 0,1 0,6 M18.5,6.5a8,8 0 0,1 0,11",
    "bell":      "M6,17v-6a6,6 0 0,1 12,0v6l2,2h-16z M10,21h4",
    "battery":   "M3,7h16v10h-16z M19,10h2v4h-2 M6,10h6v4h-6z",
    "package":   "M12,3l8,4.5v9l-8,4.5l-8,-4.5v-9z M4,7.5l8,4.5l8,-4.5 M12,12v9",
    "location":  "M12,21c0,0 -7,-6 -7,-11a7,7 0 0,1 14,0c0,5 -7,11 -7,11z " + circle(12, 10, 2),
    "account":   circle(12, 8, 4) + " M4,21a8,8 0 0,1 16,0",
    "calendar":  "M4,5h16v15h-16z M4,9h16 M8,3v4 M16,3v4",
    "globe":     circle(12, 12, 9) + " M3,12h18 M12,3a14,14 0 0,1 0,18 M12,3a14,14 0 0,0 0,18",
    "code":      "M8,8l-4,4l4,4 M16,8l4,4l-4,4 M14,5l-4,14",
    "gear":      gear(),
}

TEMPLATE = """<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <!-- Written by tools/icons.py: edit the table there, not this file. -->
    <path
        android:strokeColor="#FFFFFF"
        android:strokeWidth="1.5"
        android:strokeLineJoin="round"
        android:strokeLineCap="round"
        android:pathData="{path}" />
</vector>
"""

def main():
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "res", "drawable")
    for f in os.listdir(out):
        if f.startswith("ic_") and f.endswith(".xml"):
            os.remove(os.path.join(out, f))   # an icon dropped from the table goes too
    for name, path in sorted(ICONS.items()):
        with open(os.path.join(out, f"ic_{name}.xml"), "w") as fh:
            fh.write(TEMPLATE.format(path=path))
    print(f"wrote {len(ICONS)} icons to {os.path.normpath(out)}")

if __name__ == "__main__":
    main()
