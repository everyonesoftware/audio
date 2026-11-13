#!/usr/bin/env python3
"""Static icon contracts; no SDK required. Does not simulate an Android Auto host.

References:
https://developer.android.com/develop/ui/compose/system/icon_design_adaptive
https://developer.android.com/training/cars/media/configure-manifest

Run from any directory: python scripts/test-android-icons.py
"""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1] / "android/app/src/main"
RES = ROOT / "res"
A = "{http://schemas.android.com/apk/res/android}"


def xml(relative):
    return ET.parse(ROOT / relative).getroot()


def rectangles(vector):
    """Read our deliberately simple closed, relative rectangle vector paths.

    Reject other syntax rather than silently skipping geometry during a refactor.
    """
    result = []
    pattern = r"M(-?[\d.]+),(-?[\d.]+)h([\d.]+)v([\d.]+)h(-[\d.]+)z"
    for path in vector.findall("path"):
        data = path.get(A + "pathData", "")
        matches = list(re.finditer(pattern, data))
        if "".join(m.group() for m in matches) != data:
            raise AssertionError(f"Unsupported vector geometry: {data}")
        for match in matches:
            x, y, width, height, closing = map(float, match.groups())
            if width != -closing:
                raise AssertionError("Rectangle must close")
            result.append((x, y, x + width, y + height))
    return result


class AndroidIconTest(unittest.TestCase):
    def test_manifest_launcher_and_round_icons(self):
        app = xml("AndroidManifest.xml").find("application")
        self.assertEqual(app.get(A + "icon"), "@mipmap/ic_launcher")
        self.assertEqual(app.get(A + "roundIcon"), "@mipmap/ic_launcher_round")

    def test_adaptive_layers_and_api33_monochrome(self):
        for qualifier in ("mipmap-anydpi-v26", "mipmap-anydpi-v33"):
            for name in ("ic_launcher", "ic_launcher_round"):
                with self.subTest(qualifier=qualifier, name=name):
                    icon = xml(f"res/{qualifier}/{name}.xml")
                    self.assertEqual(icon.tag, "adaptive-icon")
                    self.assertEqual(icon.find("background").get(A + "drawable"),
                                     "@color/audio_icon_background")
                    self.assertEqual(icon.find("foreground").get(A + "drawable"),
                                     "@drawable/ic_audio_foreground")
                    if qualifier.endswith("v33"):
                        self.assertEqual(icon.find("monochrome").get(A + "drawable"),
                                         "@drawable/ic_audio_monochrome")
                    else:
                        self.assertIsNone(icon.find("monochrome"))

    def test_equalizer_is_centered_inside_adaptive_safe_circle(self):
        shapes = []
        for name, color in (("ic_audio_foreground", "#C0D7AE"),
                            ("ic_audio_monochrome", "#FFFFFF")):
            with self.subTest(name=name):
                vector = xml(f"res/drawable/{name}.xml")
                self.assertEqual(vector.tag, "vector")
                for attr in ("width", "height"):
                    self.assertEqual(vector.get(A + attr), "108dp")
                for attr in ("viewportWidth", "viewportHeight"):
                    self.assertEqual(float(vector.get(A + attr)), 108)
                self.assertEqual(set(vector.attrib), {A + attr for attr in
                                 ("width", "height", "viewportWidth", "viewportHeight")})
                self.assertTrue(all(p.get(A + "fillColor") == color
                                    for p in vector.findall("path")))
                bars = rectangles(vector)
                self.assertEqual(len(bars), 4)
                self.assertEqual((min(b[0] for b in bars) + max(b[2] for b in bars)) / 2, 54)
                self.assertEqual((min(b[1] for b in bars) + max(b[3] for b in bars)) / 2, 54)
                self.assertGreaterEqual(max(b[3] for b in bars) - min(b[1] for b in bars), 48)
                for x0, y0, x1, y1 in bars:
                    for x in (x0, x1):
                        for y in (y0, y1):
                            self.assertLessEqual((x - 54) ** 2 + (y - 54) ** 2, 33 ** 2,
                                                 "Equalizer clipped by 66dp safe circle")
                shapes.append(bars)
        self.assertEqual(shapes[0], shapes[1])

    def test_dark_background_preserves_brand(self):
        colors = xml("res/values/icon_colors.xml")
        self.assertEqual(colors.find("color[@name='audio_icon_background']").text, "#1C211F")

    def test_car_attribution_uses_monochrome_notification_vector(self):
        app = xml("AndroidManifest.xml").find("application")
        entries = app.findall("meta-data[@" + A + "name='androidx.car.app.TintableAttributionIcon']")
        self.assertEqual(len(entries), 1, "Declare the documented app-level car attribution icon")
        self.assertEqual(entries[0].get(A + "resource"), "@drawable/ic_audio_notification")
        icon = xml("res/drawable/ic_audio_notification.xml")
        self.assertEqual(icon.tag, "vector")
        self.assertEqual(set(icon.attrib), {A + attr for attr in
                         ("width", "height", "viewportWidth", "viewportHeight")})
        for attr in ("width", "height"):
            self.assertEqual(icon.get(A + attr), "24dp")
        for attr in ("viewportWidth", "viewportHeight"):
            self.assertEqual(float(icon.get(A + attr)), 24)
        self.assertEqual(len(icon), 1, "Transparent background, no opaque tile or groups")
        self.assertEqual(icon[0].get(A + "fillColor"), "#FFFFFF")
        bars = rectangles(icon)
        self.assertEqual(len(bars), 4)
        for x0, y0, x1, y1 in bars:
            self.assertTrue(0 < x0 < x1 < 24 and 0 < y0 < y1 < 24)


if __name__ == "__main__":
    unittest.main(verbosity=2)
