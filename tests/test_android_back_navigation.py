"""SDK-free back-handler contracts; native dispatch is covered by AudioScreensTest."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "android/app/src/main/java/com/everyonesoftware/audio/ui/AudioScreens.kt"


class AndroidBackNavigationTests(unittest.TestCase):
    def setUp(self):
        source = SOURCE.read_text(encoding="utf-8")
        self.handler = source.split("    BackHandler(", 1)[1].split("    Surface(", 1)[0]
        self.enabled = self.handler.split("enabled = ", 1)[1].split(") {", 1)[0]

    def test_now_playing_handles_back_and_routes_to_library(self):
        self.assertNotRegex(self.enabled, r"state\.screen != Screen\.PLAYER\b")
        self.assertRegex(self.handler, r"Screen\.PLAYER\s*->\s*Screen\.PLAYLISTS\b")

    def test_library_and_welcome_leave_back_to_android(self):
        for screen in ("PLAYLISTS", "WELCOME"):
            with self.subTest(screen=screen):
                self.assertIn(f"state.screen != Screen.{screen}", self.enabled)

    def test_queue_and_update_return_routes_are_preserved(self):
        self.assertRegex(self.handler, r"Screen\.QUEUE\s*->\s*Screen\.PLAYER\b")
        self.assertRegex(self.handler, r"Screen\.UPDATES\s*->\s*state\.updatesReturnScreen\b")
        self.assertNotIn("actions.togglePlay", self.handler)
        self.assertNotIn("actions.pause", self.handler)


if __name__ == "__main__":
    unittest.main()
