package org.ungoogled.colorosdocuments;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Rewrites a new document's template for the phone's own language, at the moment the app copies the template out of its
 * assets.
 *
 * The templates ship with Chinese defaults: Word and Excel name 宋体, PowerPoint leaves its east-asian slot empty and
 * repeats 宋体 in every slide master and layout, and all three tag their runs "zh-CN". A phone in another language then
 * either draws the text in a face it does not have, or shapes it with the wrong east-asian face and gets the widths
 * wrong. The patch rewrites the templates while building the APK, but that runs on the machine that builds the patch --
 * its locale is not the phone's, so a bundle built in English tagged every Korean document "en".
 *
 * Doing the rewrite here instead makes it depend on the phone: Android's current locale decides the face, the language
 * tag and the Excel charset, and the same APK serves every language. Files that are not templates (PDF, TXT) are left
 * alone, as are templates already written for this locale.
 */
public final class Templates {
    private Templates() {
    }

    /**
     * Rewrites the template at [path] for the phone's language and returns the same path, or [path] unchanged when it
     * is not an Office template or cannot be rewritten (the app then uses it as it shipped).
     */
    public static String localize(String path) {
        if (path == null) return null;
        String name = path.toLowerCase(Locale.ROOT);
        // The app renames the template on the way out: template.dotx becomes 새 문서.docx, template.xltx becomes
        // 새 문서.xlsx, template.potx becomes 새 문서.pptx. The package inside is still the template, so the extension
        // check has to cover both the template's own suffix and the document suffix it is copied to.
        boolean office = name.endsWith(".dotx") || name.endsWith(".xltx") || name.endsWith(".potx")
            || name.endsWith(".docx") || name.endsWith(".xlsx") || name.endsWith(".pptx");
        android.util.Log.i("ColorOSDocuments", "Templates.localize: path=" + path + " office=" + office);
        if (!office) return path;

        File file = new File(path);
        if (!file.isFile()) {
            android.util.Log.w("ColorOSDocuments", "Templates.localize: not a file: " + path);
            return path;
        }

        // Only a package that still carries the shipping Chinese defaults is rewritten. An ordinary document the user
        // opens goes through this same method (they share the temp directory), and its own fonts must be left alone.
        if (!isUntouchedTemplate(file)) {
            android.util.Log.i("ColorOSDocuments", "Templates.localize: not a stock template, left alone");
            return path;
        }

        Locale locale = Locale.getDefault();
        String tag = eastAsianLanguage(locale);
        if (alreadyLocalized(file, tag)) {
            android.util.Log.i("ColorOSDocuments", "Templates.localize: already " + tag);
            return path;
        }

        try {
            rewrite(file, locale);
            android.util.Log.i("ColorOSDocuments", "Templates.localize: rewrote for " + tag);
            return path;
        } catch (Exception e) {
            android.util.Log.w("ColorOSDocuments", "template left as shipped: " + path, e);
            return path;
        }
    }

    /**
     * Whether [file] is still the package the app ships, never opened or edited. The app shares one temp directory
     * between the templates it copies and the documents it opens, so the check has to be on the content: a stock
     * template still names a Chinese face or the Chinese language, which no document this patch wrote does.
     */
    private static boolean isUntouchedTemplate(File file) {
        try (ZipFile zip = new ZipFile(file)) {
            String[][] marks = {
                { "word/styles.xml", "w:eastAsia=\"宋体\"" },
                { "xl/styles.xml", "<charset val=\"134\"/>" },
                { "ppt/slideMasters/slideMaster1.xml", "lang=\"zh-CN\"" },
            };
            for (String[] mark : marks) {
                ZipEntry entry = zip.getEntry(mark[0]);
                if (entry == null) continue;
                return read(zip.getInputStream(entry)).contains(mark[1]);
            }
        } catch (Exception ignored) {
            // Unreadable: treat it as a document, not a template, and leave it alone.
        }
        return false;
    }

    /** Whether the part that carries this format's language tag already names [tag]. */
    private static boolean alreadyLocalized(File file, String tag) {
        try (ZipFile zip = new ZipFile(file)) {
            // Each format keeps its default in a different part; whichever the package has decides.
            String[][] marks = {
                { "word/styles.xml", "w:eastAsia=\"" + tag + "\"" },
                { "xl/styles.xml", "<charset val=\"" + charsetFor(Locale.getDefault()) + "\"/>" },
                { "ppt/slideMasters/slideMaster1.xml", "lang=\"" + tag + "\"" },
            };
            for (String[] mark : marks) {
                ZipEntry entry = zip.getEntry(mark[0]);
                if (entry == null) continue;
                return read(zip.getInputStream(entry)).contains(mark[1]);
            }
        } catch (Exception ignored) {
            // Unreadable: let the rewrite try, it fails the same way and the caller keeps the shipped file.
        }
        return false;
    }

    private static void rewrite(File file, Locale locale) throws Exception {
        String face = faceFor(locale);
        String tag = eastAsianLanguage(locale);
        int charset = charsetFor(locale);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipFile zip = new ZipFile(file); ZipOutputStream out = new ZipOutputStream(buffer)) {
            for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
                byte[] bytes = readBytes(zip.getInputStream(entry));
                String part = entry.getName();
                if (part.endsWith(".xml") && isTextPart(part)) {
                    String xml = new String(bytes, StandardCharsets.UTF_8);
                    bytes = rewritePart(part, xml, face, tag, charset).getBytes(StandardCharsets.UTF_8);
                }
                out.putNextEntry(new ZipEntry(part));
                out.write(bytes);
                out.closeEntry();
            }
        }

        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(buffer.toByteArray());
        }
    }

    /** Whether [part] holds the text styles a template's defaults live in. */
    private static boolean isTextPart(String part) {
        return part.startsWith("word/") || part.startsWith("xl/") || part.startsWith("ppt/");
    }

    private static String rewritePart(String part, String xml, String face, String tag, int charset) {
        String out = xml;

        // Word and PowerPoint name Chinese faces outright.
        for (String chinese : CHINESE_FACES) {
            out = out.replace("w:eastAsia=\"" + chinese + "\"", "w:eastAsia=\"" + face + "\"");
            out = out.replace("<w:font w:name=\"" + chinese + "\">", "<w:font w:name=\"" + face + "\">");
            out = out.replace("<a:latin typeface=\"" + chinese + "\"", "<a:latin typeface=\"" + face + "\"");
            out = out.replace("<a:ea typeface=\"" + chinese + "\"", "<a:ea typeface=\"" + face + "\"");
            out = out.replace("<a:cs typeface=\"" + chinese + "\"", "<a:cs typeface=\"" + face + "\"");
            out = out.replace("<name val=\"" + chinese + "\"/>", "<name val=\"" + face + "\"/>");
        }

        // The script slots a theme carries per writing system: the Hangul and Han entries decide what the app draws.
        out = out.replaceAll("<a:font script=\"Hang\" typeface=\"[^\"]*\"", "<a:font script=\"Hang\" typeface=\"" + face + "\"");
        out = out.replaceAll("<a:font script=\"Hans\" typeface=\"[^\"]*\"", "<a:font script=\"Hans\" typeface=\"" + face + "\"");
        out = out.replaceAll("<a:font script=\"Jpan\" typeface=\"[^\"]*\"", "<a:font script=\"Jpan\" typeface=\"" + face + "\"");

        // Empty east-asian slots (PowerPoint's theme) and the theme references.
        out = out.replace("<a:ea typeface=\"\"", "<a:ea typeface=\"" + face + "\"");
        out = out.replace("<a:ea typeface=\"+mn-ea\"", "<a:ea typeface=\"" + face + "\"");
        out = out.replace("<a:ea typeface=\"+mj-ea\"", "<a:ea typeface=\"" + face + "\"");
        out = out.replace("<a:latin typeface=\"\"", "<a:latin typeface=\"" + face + "\"");
        out = out.replace("<a:cs typeface=\"\"", "<a:cs typeface=\"" + face + "\"");

        // Word's document default and every run language.
        out = out.replace(" w:eastAsiaTheme=\"minorEastAsia\"", "").replace(" w:eastAsiaTheme=\"majorEastAsia\"", "");
        out = out.replace("w:eastAsia=\"zh-CN\"", "w:eastAsia=\"" + tag + "\"");
        out = out.replace("w:eastAsia=\"en-US\"", "w:eastAsia=\"" + tag + "\"");

        // PowerPoint / DrawingML run languages.
        out = out.replace("lang=\"zh-CN\"", "lang=\"" + tag + "\"");
        out = out.replace("lang=\"en-US\"", "lang=\"" + tag + "\"");
        out = out.replace("altLang=\"zh-CN\"", "altLang=\"" + tag + "\"");
        out = out.replace("altLang=\"en-US\"", "altLang=\"" + tag + "\"");

        // Excel's per-font charset: 134 is GB2312, which marks the cells Simplified Chinese.
        out = out.replace("<charset val=\"134\"/>", "<charset val=\"" + charset + "\"/>");

        return out;
    }

    private static final String[] CHINESE_FACES = { "宋体", "等线", "等线 Light", "新細明體", "SimSun", "DengXian" };

    /**
     * The face a new document should use on this phone. Android resolves "sans-serif" through the phone's own fallback
     * chain, which ends in the face it has for the language, so one name covers every language and keeps the document
     * meaningful when it is opened elsewhere. Simplified Chinese keeps 宋体, which its phones do carry.
     */
    private static String faceFor(Locale locale) {
        if ("zh".equals(locale.getLanguage())) {
            String country = locale.getCountry();
            if (!"TW".equals(country) && !"HK".equals(country)) return "宋体";
        }
        return "sans-serif";
    }

    /** The BCP 47 tag Office expects in a run's east-asian slot. */
    private static String eastAsianLanguage(Locale locale) {
        String language = locale.getLanguage();
        if ("ko".equals(language)) return "ko-KR";
        if ("ja".equals(language)) return "ja-JP";
        if ("zh".equals(language)) {
            String country = locale.getCountry();
            return ("TW".equals(country) || "HK".equals(country)) ? "zh-TW" : "zh-CN";
        }
        return "en-US";
    }

    /** The Windows charset number that matches the language (129 Hangul, 128 Shift-JIS, 136 Big5, 134 GB2312). */
    private static int charsetFor(Locale locale) {
        String language = locale.getLanguage();
        if ("ko".equals(language)) return 129;
        if ("ja".equals(language)) return 128;
        if ("zh".equals(language)) {
            String country = locale.getCountry();
            return ("TW".equals(country) || "HK".equals(country)) ? 136 : 134;
        }
        return 0;
    }

    private static String read(InputStream in) throws Exception {
        return new String(readBytes(in), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(InputStream in) throws Exception {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int count;
            while ((count = stream.read(chunk)) > 0) out.write(chunk, 0, count);
            return out.toByteArray();
        }
    }
}
