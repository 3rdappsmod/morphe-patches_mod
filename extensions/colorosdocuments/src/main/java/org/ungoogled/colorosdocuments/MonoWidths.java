package org.ungoogled.colorosdocuments;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Gives Hangul compatibility jamo their own advance instead of the one shared by every CJK character.
 *
 * `o.v()` classifies U+3100..DFFF -- which covers both CJK ideographs and Hangul -- as one class, and
 * `EIOFontMetrics.getMonospacedCharWidth` keeps a single advance for that class in the `monospacedWidth` field. The
 * first character to arrive fixes the value and every later character is laid out with it. Measured on the device,
 * ㅂ seeds 25.0 and then ㄴ (26.0) and ㄹ (28.0) are still given 25.0, so a composing jamo run is drawn narrower than
 * its glyphs and the letters overlap.
 *
 * The fix is deliberately narrow: a compatibility jamo takes its own `getReferenceCharWidth`, and a jamo that seeded
 * the field puts it back to "unset" so the next CJK character seeds it correctly. Everything else -- ideographs,
 * precomposed syllables, Latin, punctuation, the 0x3000 and 0xA4B3 special cases -- goes through the original code
 * untouched.
 *
 * Reflection is used so the injected smali passes only registers that already exist: `p0` (the metrics object),
 * `p1` (the char) and the width register. Three earlier builds broke the verifier by writing into a live register.
 */
public final class MonoWidths {
    private MonoWidths() {
    }

    private static final float UNSET = -2.0f;

    private static final ClassValue<Access> ACCESS = new ClassValue<Access>() {
        @Override
        protected Access computeValue(Class<?> type) {
            return new Access(type);
        }
    };

    private static final class Access {
        final Field width;
        final Field widthChar;
        final Method reference;

        Access(Class<?> type) {
            width = field(type, "monospacedWidth");
            widthChar = field(type, "monospacedWidthC");
            reference = method(type, "getReferenceCharWidth", char.class);
        }

        private static Field field(Class<?> type, String name) {
            // SSFontMetrics inherits these fields from EIOFontMetrics. getDeclaredField on the
            // concrete subclass alone silently disables seed clearing in the spreadsheet editor.
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                try {
                    Field field = current.getDeclaredField(name);
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchFieldException ignored) {
                    // Continue with the superclass, including private inherited fields.
                } catch (SecurityException e) {
                    return null;
                }
            }
            return null;
        }

        private static Method method(Class<?> type, String name, Class<?>... parameters) {
            try {
                Method method = type.getMethod(name, parameters);
                method.setAccessible(true);
                return method;
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    /** U+3131..U+318E: the compatibility jamo an IME sends while a syllable is being composed. */
    public static boolean isJamo(char c) {
        return c >= '\u3131' && c <= '\u318E';
    }

    /**
     * Called where the original reads the shared advance. A jamo is measured on its own; every other character keeps
     * the value the original code produced.
     */
    public static float widthForChar(Object metrics, char c, float cached) {
        // A jamo is never uniform: measured at one size they come out 23, 25, 26, 27, 28, and reusing one character's
        // advance for its neighbours draws the narrow ones too wide and the wide ones too narrow.
        //
        // The unset value needs the same treatment for a different reason. `clearJamoSeed` runs right after a jamo seeds
        // the shared field and resets it to -2.0f, and the very next read of that field is this call's `cached` -- even
        // when the character being measured now is an ideograph or a precomposed syllable. Passing -2.0f on to the width
        // conversion yields a negative advance, which is clamped to zero, so every such character is drawn on one spot.
        if (isJamo(c) || cached == UNSET) {
            Access access = ACCESS.get(metrics.getClass());
            if (access.reference == null) return cached;
            try {
                return (Float) access.reference.invoke(metrics, c);
            } catch (Throwable ignored) {
                return cached;
            }
        }
        return cached;
    }

    /**
     * Called right after the original seeds the shared advance, so a jamo does not become the value that ideographs and
     * precomposed syllables inherit.
     *
     * Two things had to change from the first version. It only cleared when `monospacedWidthC` already equalled the
     * character being seeded, but the spreadsheet metrics never writes that field, so there the check never passed and
     * nothing was ever cleared. And the clear has to be harmless to the read that follows it: `widthForChar` treats the
     * resulting -2.0f as "unset" and measures the character in hand, so the reset can no longer produce a zero advance.
     */
    public static void clearJamoSeed(Object metrics, char c) {
        if (!isJamo(c)) return;
        Access access = ACCESS.get(metrics.getClass());
        if (access.width == null) return;
        try {
            access.width.setFloat(metrics, UNSET);
            if (access.widthChar != null) access.widthChar.setChar(metrics, '\0');
        } catch (Throwable ignored) {
            // Leave the field alone; a stale seed is the original behaviour, not a crash.
        }
    }
}
