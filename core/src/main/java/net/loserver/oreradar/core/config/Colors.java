package net.loserver.oreradar.core.config;

/** {@code #RRGGBB} colours as the configs write them (the '#' is optional on input, as in 1.0). */
public final class Colors {
    private Colors() {}

    public static boolean isColor(String s) {
        if (s == null) return false;
        String hex = s.startsWith("#") ? s.substring(1) : s;
        if (hex.length() != 6) return false;
        for (int i = 0; i < 6; i++) {
            if (Character.digit(hex.charAt(i), 16) < 0) return false;
        }
        return true;
    }

    /** 0xRRGGBB, or fallback if s is not a colour. */
    public static int parse(String s, int fallback) {
        return isColor(s) ? Integer.parseInt(s.startsWith("#") ? s.substring(1) : s, 16) : fallback;
    }

    public static String format(int rgb) {
        return String.format("#%06X", rgb & 0xFFFFFF);
    }
}
