package org.ungoogled.colorosdocuments;

/**
 * The font policy for documents that name a face the phone does not carry.
 *
 * A document written on a Chinese phone records 宋体 (and the other faces in {@link #MISSING}) in every run. The app's
 * own resolver, `emo.commonkit.font.l.P`, only knows the Windows/GDI names -- Droid Sans, SansSerif, Helvetica -- so a
 * Chinese face falls through to `Typeface.DEFAULT`. That alone would be harmless, but the app measures those glyphs
 * with a second, separate table: `emo.commonkit.font.s.c("宋体")` returns a hand-built width table for the Chinese face,
 * and `EIOFontMetrics.getReferenceCharWidth` prefers it over measuring the face that is actually drawn. The drawing
 * face and the measuring face then disagree, which is what makes the letters overlap.
 *
 * Both decisions have to move together. {@link #mapMissingFont} answers the family the resolver should use instead, and
 * {@link #widthTableFamily} answers the family the width table should be looked up for; feeding the width table a
 * family it does not know makes it answer null, so the app measures the real face instead. The document keeps its own
 * font names -- nothing is written back.
 *
 * Both answer "sans-serif". Android resolves that name through the phone's own fallback chain, which ends in the face
 * it has for the document's language: Noto Sans CJK KR on a Korean phone, the Chinese face on a Chinese one. So the
 * visible result stays right for every region, and the layout widths come from whatever is actually drawn.
 */
public final class FontCompat {
    private FontCompat() {
    }

    /**
     * Faces that ship with Chinese desktop Office and are recorded in the documents it writes. They carry no Hangul or
     * Japanese glyphs, so a phone from another region has nothing to draw them with.
     *
     * Only the ones this app's documents actually carry; the full set the width table knows also holds the 方正/华文/
     * 汉仪/文星 families, which Office does not write into a document by default.
     */
    private static final String[] MISSING = {
        "宋体",
        "新宋体",
        "仿宋",
        "黑体",
        "楷体",
        "宋体-PUA",
        "宋体-方正超大字符集",
        "国标宋体",
        "国标仿宋",
        "国标黑体",
        "国标楷体",
        "国标小标宋",
        "微软雅黑",
        "等线",
        "等线 Light",
    };

    /**
     * The family the app should draw [family] with, or [family] itself when it is not one of the faces above. Called
     * from `l.P`, which decides the {@code Typeface} for both the drawing pass and the measurement pass.
     */
    public static String mapMissingFont(String family) {
        return isMissing(family) ? "sans-serif" : family;
    }

    /**
     * The family to look a width table up for. Answering "sans-serif" for the faces above makes `s.c` answer null (it
     * has tables only for the Chinese names), which sends the app to `Paint` measurement of the face it actually draws,
     * so the layout widths and the glyphs come from the same font. Called at the top of `s.c`.
     */
    public static String widthTableFamily(String family) {
        return isMissing(family) ? "sans-serif" : family;
    }

    private static boolean isMissing(String family) {
        if (family == null || family.isEmpty()) return false;
        for (String missing : MISSING) {
            if (missing.equals(family)) return true;
        }
        return false;
    }
}
