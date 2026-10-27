import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "render_legal", Path(__file__).resolve().parent / "render-legal.py")
rl = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rl)

B = rl.BANNER + "\n\n"


class RenderLegal(unittest.TestCase):
    def test_renders_headings_lists_links(self):
        out = rl.render(B + "# Title\n\n## Part\n\n- one\n- two\n\nSee [site](https://example.org/x).\n")
        self.assertIn("<h1>Title</h1>", out)
        self.assertIn("<h2>Part</h2>", out)
        self.assertIn("<ul><li>one</li><li>two</li></ul>", out)
        self.assertIn('<a href="https://example.org/x">site</a>', out)

    def test_escapes_html(self):
        out = rl.render(B + "text <script>alert(1)</script>\n\n[x](javascript:alert(1))\n")
        self.assertNotIn("<script>", out)
        self.assertIn("&lt;script&gt;", out)
        self.assertNotIn('href="javascript', out)

    def test_banner_present(self):
        with self.assertRaises(rl.MissingBanner):
            rl.render("# No banner\n")

    def test_hash_without_space_is_text(self):
        self.assertIn("<p>#foo</p>", rl.render(B + "#foo\n"))

    def test_table(self):
        out = rl.render(B + "| A | B |\n|---|---|\n| 1 | 2 |\n")
        self.assertIn("<th>A</th>", out)
        self.assertIn("<td>2</td>", out)


if __name__ == "__main__":
    unittest.main()
