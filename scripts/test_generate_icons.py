import importlib.util
import json
import os
import struct
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
_spec = importlib.util.spec_from_file_location("generate_icons", os.path.join(HERE, "generate-icons.py"))
gi = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(gi)


def png_info(data):
    assert data[:8] == b"\x89PNG\r\n\x1a\n", "not a PNG"
    w, h, depth, ctype = struct.unpack(">IIBB", data[16:26])
    return w, h, depth, ctype


def read(rel):
    with open(os.path.join(ROOT, rel), "rb") as f:
        return f.read()


class IconTests(unittest.TestCase):
    def test_ios_icon_is_1024_without_alpha(self):
        w, h, depth, ctype = png_info(read(gi.APPLE_SET + "/ios-1024.png"))
        self.assertEqual((w, h, depth), (1024, 1024, 8))
        self.assertEqual(ctype, 2)  # truecolour, no alpha channel

    def test_mac_sizes(self):
        for n in (16, 32, 64, 128, 256, 512, 1024):
            w, h, _, _ = png_info(read("%s/mac-%d.png" % (gi.APPLE_SET, n)))
            self.assertEqual((w, h), (n, n))

    def test_contents_json_matches_files(self):
        doc = json.loads(read(gi.APPLE_SET + "/Contents.json"))
        images = doc["images"]
        self.assertEqual(len(images), 11)
        ios = [i for i in images if i.get("platform") == "ios"]
        self.assertEqual(len(ios), 1)
        self.assertEqual(ios[0]["size"], "1024x1024")
        for i in images:
            data = read(gi.APPLE_SET + "/" + i["filename"])
            w, h, _, _ = png_info(data)
            pt = int(i["size"].split("x")[0])
            scale = int(i.get("scale", "1x")[0])
            self.assertEqual(w, pt * scale, i["filename"])
        mac = {(i["size"], i["scale"]) for i in images if i["idiom"] == "mac"}
        self.assertEqual(len(mac), 10)
        listed = {i["filename"] for i in images}
        on_disk = {f for f in os.listdir(os.path.join(ROOT, gi.APPLE_SET)) if f.endswith(".png")}
        self.assertEqual(listed, on_disk)

    def test_android_files(self):
        res = os.path.join(ROOT, gi.ANDROID_RES)
        for rel in gi.ANDROID_XML:
            self.assertTrue(os.path.isfile(os.path.join(res, rel)), rel)
        xml = read(gi.ANDROID_RES + "/mipmap-anydpi-v26/ic_launcher.xml").decode()
        self.assertIn("<monochrome", xml)
        for dpi, n in gi.ANDROID_DPI:
            for name in ("ic_launcher", "ic_launcher_round"):
                w, h, _, _ = png_info(read("%s/mipmap-%s/%s.png" % (gi.ANDROID_RES, dpi, name)))
                self.assertEqual((w, h), (n, n))
        w, h, _, _ = png_info(read("design/icon/play-store-512.png"))
        self.assertEqual((w, h), (512, 512))

    def test_svg_sources_exist(self):
        for name in ("icon.svg", "icon-foreground.svg", "icon-monochrome.svg"):
            text = read("design/icon/" + name).decode()
            self.assertIn("<svg", text)
            self.assertNotIn("<text", text)  # no letters in the icon

    def test_deterministic_and_committed_files_current(self):
        first = gi.build_all()
        second = gi.build_all()
        self.assertEqual(first, second)
        with tempfile.TemporaryDirectory() as tmp:
            gi.write_all(tmp)
            for rel, data in first.items():
                with open(os.path.join(tmp, rel), "rb") as f:
                    self.assertEqual(f.read(), data, rel)
        for rel, data in first.items():
            self.assertEqual(read(rel), data, "stale committed file: " + rel)


if __name__ == "__main__":
    unittest.main()
