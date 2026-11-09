package org.ungoogled.colorosdocuments;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
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
 * wrong. Rewriting at patch-build time would use the build machine's locale rather than the phone's.
 *
 * Doing the rewrite here instead makes it depend on the phone: One sans-serif family supplies the glyphs; Android's current locale decides the language
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
    public static synchronized String localize(String path) {
        if (path == null) return null;
        String name = path.toLowerCase(Locale.ROOT);
        // The app renames the template on the way out: template.dotx becomes 새 문서.docx, template.xltx becomes
        // 새 문서.xlsx, template.potx becomes 새 문서.pptx. The package inside is still the template, so the extension
        // check has to cover both the template's own suffix and the document suffix it is copied to.
        boolean office = name.endsWith(".dotx") || name.endsWith(".xltx") || name.endsWith(".potx")
            || name.endsWith(".docx") || name.endsWith(".xlsx") || name.endsWith(".pptx");
        if (!office) return path;

        File file = new File(path);
        if (!file.isFile() || !isUntouchedTemplate(file)) return path;
        Locale locale = Locale.getDefault();
        String tag = eastAsianLanguage(locale);

        try {
            rewrite(file, locale);
            android.util.Log.i("ColorOSDocuments", "Templates.localize: rewrote for " + tag);
            return path;
        } catch (Exception e) {
            android.util.Log.w("ColorOSDocuments", "template left as shipped: " + path, e);
            return path;
        }
    }

    // Exact assets in the supported 17.1.32 APK, before FileHelper renames them to docx/xlsx/pptx.
    // A Chinese style or language alone also appears in user documents and must never authorize a rewrite.
    private static final String[] TEMPLATE_SHA256 = {
        "d8aa53569dfb9587e53f598a5146d22c7445982aca1ca5f466c0a70466735d46",
        "5d114ffd12f6b09dcb3cd8b3f7498e1f1bb5034c137ec1984cafef2676f9628c",
        "88f23bd4e4a3f5563183d540cf4b1bf3fe3b727af080e821ced2b66a0c4ae829",
    };

    private static boolean isUntouchedTemplate(File file) {
        long length = file.length();
        if (length != 12276 && length != 5745 && length != 34599) return false;
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            for (String expected : TEMPLATE_SHA256) {
                if (expected.contentEquals(hex)) return true;
            }
        } catch (Exception ignored) {
            // Unknown or unreadable file: preserve it unchanged.
        }
        return false;
    }

    private static void rewrite(File file, Locale locale) throws Exception {
        String face = faceFor();
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

        // Prepare and sync a sibling file first. If replacement is unsupported or fails,
        // keep the original template rather than risking a partially written Office package.
        File temporary = File.createTempFile("danpoong-template-", ".tmp", file.getAbsoluteFile().getParentFile());
        try {
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                out.write(buffer.toByteArray());
                out.getFD().sync();
            }
            Files.move(temporary.toPath(), file.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            temporary.delete();
        }
    }

    /** Whether [part] holds the text styles a template's defaults live in. */
    private static boolean isTextPart(String part) {
        return part.startsWith("word/") || part.startsWith("xl/") || part.startsWith("ppt/");
    }

    private static String rewritePart(String part, String xml, String face, String tag, int charset) {
        String out = xml;
        // Bind all Word scripts explicitly; otherwise theme references keep different Latin fonts.
        out = out.replaceAll("<w:rFonts\\b[^>]*/>", "<w:rFonts w:ascii=\"" + face
            + "\" w:hAnsi=\"" + face + "\" w:eastAsia=\"" + face + "\" w:cs=\"" + face + "\"/>");
        out = out.replaceAll("(<a:(?:latin|ea|cs)\\b[^>]*\\btypeface=\")[^\"]*(\")", "$1" + face + "$2");
        out = out.replaceAll("(<a:font\\b[^>]*\\btypeface=\")[^\"]*(\")", "$1" + face + "$2");
        // Drop the old face's PANOSE/pitch hints; DrawingML charset is a signed Windows charset byte.
        out = out.replaceAll("(<a:(?:latin|ea|cs|font)\\b[^>]*?)\\s+panose=\"[^\"]*\"", "$1");
        out = out.replaceAll("(<a:(?:latin|ea|cs|font)\\b[^>]*?)\\s+pitchFamily=\"[^\"]*\"", "$1");
        out = out.replaceAll("(<a:(?:latin|ea|cs|font)\\b[^>]*\\bcharset=\")[^\"]*(\")",
            "$1" + (byte) charset + "$2");
        if (part.equals("xl/styles.xml")) {
            out = out.replaceAll("<name val=\"[^\"]*\"\\s*/>", "<name val=\"" + face + "\"/>");
        }
        out = out.replace("w:eastAsia=\"zh-CN\"", "w:eastAsia=\"" + tag + "\"");
        out = out.replace("w:eastAsia=\"en-US\"", "w:eastAsia=\"" + tag + "\"");
        out = out.replace("w:val=\"en-US\"", "w:val=\"" + tag + "\"");
        out = out.replace("lang=\"zh-CN\"", "lang=\"" + tag + "\"");
        out = out.replace("lang=\"en-US\"", "lang=\"" + tag + "\"");
        out = out.replace("altLang=\"zh-CN\"", "altLang=\"" + tag + "\"");
        out = out.replace("altLang=\"en-US\"", "altLang=\"" + tag + "\"");
        out = out.replace("<charset val=\"134\"/>", "<charset val=\"" + charset + "\"/>");
        return out;
    }

    /** One Android family for all three formats; Android supplies script-specific fallback glyphs. */
    private static String faceFor() {
        return "sans-serif";
    }

    /** The BCP 47 tag Office expects in a run's east-asian slot. */
    private static String eastAsianLanguage(Locale locale) {
        String language = locale.getLanguage();
        if ("ko".equals(language)) return "ko-KR";
        if ("ja".equals(language)) return "ja-JP";
        if ("zh".equals(language)) {
            return usesTraditionalChinese(locale) ? "zh-TW" : "zh-CN";
        }
        String tag = locale.toLanguageTag();
        return tag.isEmpty() || "und".equals(tag) ? "en-US" : tag;
    }

    /** The Windows charset number that matches the language (129 Hangul, 128 Shift-JIS, 136 Big5, 134 GB2312). */
    private static int charsetFor(Locale locale) {
        String language = locale.getLanguage();
        if ("ko".equals(language)) return 129;
        if ("ja".equals(language)) return 128;
        if ("zh".equals(language)) {
            return usesTraditionalChinese(locale) ? 136 : 134;
        }
        return 0;
    }

    private static boolean usesTraditionalChinese(Locale locale) {
        if ("Hant".equals(locale.getScript())) return true;
        if ("Hans".equals(locale.getScript())) return false;
        String country = locale.getCountry();
        return "TW".equals(country) || "HK".equals(country) || "MO".equals(country);
    }

    /** The folder count suffix uses the current device language, not the patch-building machine's. */
    public static String folderCountSuffix() {
        switch (Locale.getDefault().getLanguage()) {
            case "ko": return "개  |  ";
            case "ja": return "件  |  ";
            case "zh": return usesTraditionalChinese(Locale.getDefault()) ? "項  |  " : "项  |  ";
            default: return "items  |  ";
        }
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
