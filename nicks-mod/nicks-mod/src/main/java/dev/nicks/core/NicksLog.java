package dev.nicks.core;

/** Tiny logging facade so the core package has no logging dependency. The Fabric layer installs an SLF4J sink. */
public final class NicksLog {
    public interface Sink { void log(String level, String message, Throwable t); }

    private static volatile Sink sink = (level, msg, t) -> {
        System.err.println("[Nicks/" + level + "] " + msg);
        if (t != null) t.printStackTrace();
    };
    private static volatile boolean debug;

    private NicksLog() {}

    public static void setSink(Sink s) { sink = s; }
    public static void setDebug(boolean enabled) { debug = enabled; }
    public static boolean debugEnabled() { return debug; }

    public static void info(String m) { sink.log("INFO", m, null); }
    public static void warn(String m) { sink.log("WARN", m, null); }
    public static void warn(String m, Throwable t) { sink.log("WARN", m, t); }
    public static void error(String m, Throwable t) { sink.log("ERROR", m, t); }
    public static void debug(String m) { if (debug) sink.log("DEBUG", m, null); }
}
