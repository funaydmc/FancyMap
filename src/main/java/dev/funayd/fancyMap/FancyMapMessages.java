package dev.funayd.fancyMap;

public final class FancyMapMessages {
    public static final String PREFIX = "§8[§bFancyMap§8] §r";

    private FancyMapMessages() {
    }

    public static String text(String message) {
        return PREFIX + message;
    }

    public static String debug(String message) {
        return text("§8[Debug] §7" + message);
    }
}
