#!/usr/bin/env python3
"""Runtime regressions; optional stock-asset cases: DOCUMENTS_APK=/path/to/17.1.32.apk python3 this_file.py."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "extensions/colorosdocuments/src/main/java/org/ungoogled/colorosdocuments"

HARNESS = r'''
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import java.io.*;
import org.ungoogled.colorosdocuments.MonoWidths;
import org.ungoogled.colorosdocuments.Templates;

public class DocumentsRuntimeTest {
    public static class BaseMetrics {
        private float monospacedWidth = -2f;
        private char monospacedWidthC;
        public float getReferenceCharWidth(char c) {
            if (c == 'ㅂ') return 25f;
            if (c == 'ㄴ') return 26f;
            if (c == 'ㄹ') return 28f;
            return 41f;
        }
        float cached() { return monospacedWidth; }
        char seed() { return monospacedWidthC; }
        void seed(char c) { monospacedWidth = getReferenceCharWidth(c); monospacedWidthC = c; }
    }
    public static class SpreadsheetMetrics extends BaseMetrics {}
    public static class DerivedSpreadsheetMetrics extends SpreadsheetMetrics {}

    static void equal(float expected, float actual) {
        if (expected != actual) throw new AssertionError("expected " + expected + ", got " + actual);
    }
    static void metrics(BaseMetrics m) {
        for (char c : new char[] {'ㅂ', 'ㄴ', 'ㄹ'}) {
            m.seed(c);
            MonoWidths.clearJamoSeed(m, c);
            equal(-2f, m.cached());
            if (m.seed() != 0) throw new AssertionError("seed character not cleared");
            equal(m.getReferenceCharWidth(c), MonoWidths.widthForChar(m, c, m.cached()));
        }
        // A full syllable immediately after an invalidated jamo must never receive UNSET or the jamo's 25.
        equal(41f, MonoWidths.widthForChar(m, '가', m.cached()));
        m.seed('가');
        MonoWidths.clearJamoSeed(m, '가');
        equal(41f, m.cached());
        equal(41f, MonoWidths.widthForChar(m, '中', m.cached()));
        equal(12f, MonoWidths.widthForChar(m, 'A', 12f));
        equal(26f, MonoWidths.widthForChar(m, 'ㄴ', m.cached()));
        equal(41f, m.cached());
    }
    static void unchanged(Path p) throws Exception {
        byte[] before = Files.readAllBytes(p);
        if (!p.toString().equals(Templates.localize(p.toString()))) throw new AssertionError("path changed");
        if (!Arrays.equals(before, Files.readAllBytes(p))) throw new AssertionError("user document was rewritten");
    }
    static void userDocument(Path p) throws Exception {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(p))) {
            out.putNextEntry(new ZipEntry("word/styles.xml"));
            out.write("<w:styles><w:rFonts w:eastAsia=\"宋体\"/></w:styles>".getBytes("UTF-8"));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("word/document.xml"));
            out.write("<w:document>USER CONTENT</w:document>".getBytes("UTF-8"));
            out.closeEntry();
        }
        unchanged(p);
        // Same size as a stock asset must still require the matching digest.
        try (RandomAccessFile padded = new RandomAccessFile(p.toFile(), "rw")) { padded.setLength(12276); }
        unchanged(p);
    }
    static String xml(Path p) throws Exception {
        StringBuilder text = new StringBuilder();
        try (ZipFile zip = new ZipFile(p.toFile())) {
            for (ZipEntry e : Collections.list(zip.entries())) {
                if (!e.getName().endsWith(".xml")) continue;
                try (InputStream in = zip.getInputStream(e)) { text.append(new String(in.readAllBytes(), "UTF-8")); }
            }
        }
        return text.toString();
    }
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.KOREA);
        switch (args[0]) {
            case "metrics":
                metrics(new BaseMetrics()); metrics(new SpreadsheetMetrics()); metrics(new DerivedSpreadsheetMetrics());
                break;
            case "user": userDocument(Path.of(args[1])); break;
            case "stock": {
                Path p = Path.of(args[1]);
                Templates.localize(p.toString());
                String xml = xml(p);
                if (!xml.contains("sans-serif") || xml.contains("<charset val=\"134\"/>"))
                    throw new AssertionError("stock template not localized: " + p);
                unchanged(p);
                break;
            }
            case "failure": unchanged(Path.of(args[1])); break;
            case "locale":
                for (String[] row : new String[][] {{"ko-KR", "개  |  "}, {"ja-JP", "件  |  "},
                        {"zh-CN", "项  |  "}, {"en-US", "items  |  "}}) {
                    Locale.setDefault(Locale.forLanguageTag(row[0]));
                    if (!Templates.folderCountSuffix().equals(row[1])) throw new AssertionError(row[0]);
                }
                break;
            case "french": {
                Locale.setDefault(Locale.FRANCE);
                Path p = Path.of(args[1]); Templates.localize(p.toString());
                if (!xml(p).contains("fr-FR")) throw new AssertionError("French locale lost");
                break;
            }
            default: throw new AssertionError(args[0]);
        }
    }
}
'''

class DocumentsRuntimeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix="documents-runtime-")
        cls.directory = Path(cls.temporary.name)
        log = cls.directory / "android/util/Log.java"
        log.parent.mkdir(parents=True)
        log.write_text("package android.util; public class Log { public static int i(String t,String s){return 0;} "
                       "public static int w(String t,String s,Throwable e){return 0;} }")
        harness = cls.directory / "DocumentsRuntimeTest.java"
        harness.write_text(HARNESS)
        cls.classes = cls.directory / "classes"
        subprocess.run(["javac", "-d", str(cls.classes), str(log), str(harness),
                        str(JAVA / "MonoWidths.java"), str(JAVA / "Templates.java")], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def run_case(self, case, *args):
        subprocess.run(["java", "-cp", str(self.classes), "DocumentsRuntimeTest", case, *map(str, args)], check=True)

    def stock(self, name, output):
        apk = os.environ.get("DOCUMENTS_APK")
        if not apk:
            self.skipTest("set DOCUMENTS_APK to the original supported 17.1.32 APK")
        with zipfile.ZipFile(apk) as archive:
            output.write_bytes(archive.read("assets/template/" + name))
        return output

    def test_inherited_cache_and_jamo_to_syllable(self):
        self.run_case("metrics")

    def test_user_document_with_chinese_styles_is_unchanged(self):
        self.run_case("user", self.directory / "user.docx")

    def test_count_suffix_uses_current_locale(self):
        self.run_case("locale")

    def test_stock_office_templates_and_idempotence(self):
        for asset, suffix in [("word/template.dotx", ".docx"), ("excel/template.xltx", ".xlsx"),
                              ("ppt/template.potx", ".pptx")]:
            with self.subTest(asset=asset):
                self.run_case("stock", self.stock(asset, self.directory / ("new" + suffix)))

    def test_non_asian_language_tag_is_preserved(self):
        self.run_case("french", self.stock("word/template.dotx", self.directory / "french.docx"))

    @unittest.skipIf(os.geteuid() == 0, "root bypasses directory write permissions")
    def test_unwritable_directory_preserves_original_and_cleans_up(self):
        folder = self.directory / "readonly"
        folder.mkdir()
        original = self.stock("word/template.dotx", folder / "new.docx")
        folder.chmod(0o555)
        try:
            self.run_case("failure", original)
            self.assertEqual([original.name], [p.name for p in folder.iterdir()])
        finally:
            folder.chmod(0o755)

if __name__ == "__main__":
    unittest.main(verbosity=2)
