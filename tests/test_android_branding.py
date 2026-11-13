"""SDK-free branding contracts; runtime behavior is covered by native tests."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "android/app/src/main"
ANDROID = "{http://schemas.android.com/apk/res/android}"


class AndroidBrandingTests(unittest.TestCase):
    def test_launcher_label_is_music(self):
        app = ET.parse(MAIN / "AndroidManifest.xml").getroot().find("application")
        assert app is not None
        self.assertEqual("Music", app.get(ANDROID + "label"))

    def test_car_browser_root_is_music(self):
        source = (MAIN / "java/com/everyonesoftware/audio/playback/CarLibrary.kt").read_text(encoding="utf-8")
        self.assertIn('folder(CarMediaIds.ROOT, "Music")', source)

    def test_no_old_brand_in_native_user_facing_strings(self):
        for path in (MAIN / "java").rglob("*.kt"):
            for value in re.findall(r'"([^"\n]*)"', path.read_text(encoding="utf-8")):
                with self.subTest(path=path.name, value=value):
                    self.assertNotRegex(value, r"\bAudio\b|\bAUDIO\b|EveryoneAudio")

    def test_existing_installation_identity_and_signing_names_are_preserved(self):
        build = (ROOT / "android/app/build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn('applicationId = "com.everyonesoftware.audio"', build)
        self.assertIn('namespace = "com.everyonesoftware.audio"', build)
        for name in ("AUDIO_KEYSTORE_PATH", "AUDIO_KEYSTORE_PASSWORD", "AUDIO_KEY_ALIAS", "AUDIO_KEY_PASSWORD"):
            self.assertIn(f'environmentVariable("{name}")', build)
        store = (MAIN / "java/com/everyonesoftware/audio/data/AudioStore.kt").read_text(encoding="utf-8")
        self.assertIn('"audio.db"', store)


if __name__ == "__main__":
    unittest.main()
