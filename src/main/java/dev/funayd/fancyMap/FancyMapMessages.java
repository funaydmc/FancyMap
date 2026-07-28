package dev.funayd.fancyMap;

/**
 * Shared message formatting for the plugin.
 */
public final class FancyMapMessages {
    public static final String PREFIX = "§8[§bFancyMap§8] §r";

    private FancyMapMessages() {
    }

    /**
     * Adds the shared prefix to a normal player message.
     *
     * @param message message body
     * @return prefixed message
     */
    public static String text(String message) {
        return PREFIX + message;
    }

    /**
     * Adds the shared prefix and debug color to a diagnostic message.
     *
     * @param message diagnostic body
     * @return prefixed debug message
     */
    public static String debug(String message) {
        return text("§8[Debug] §7" + message);
    }
    /**
     * Adds the shared plain-text prefix to a console diagnostic.
     *
     * @param message diagnostic body
     * @return prefixed console message
     */
    public static String consoleDebug(String message) {
        return "[FancyMap] [Debug] " + message;
    }
}
