package com.example.clogger;

import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Property;

import com.example.clogger.model.Report;

/** Listens to the game's log while logging: warnings and errors grouped by logger, plus a few INFO lines (kicks). */
final class LogTap extends AbstractAppender {
    private static final String NAME = "CharsGameLogger";
    private final Session session;
    private final Map<String, Report.LogGroup> groups = new HashMap<>();
    private final Map<String, String> modOfLogger = new HashMap<>();
    long lines;
    long cantKeepUp;

    private LogTap(Session s) {
        super(NAME, null, null, true, Property.EMPTY_ARRAY);
        this.session = s;
    }

    static LogTap attach(Session s) {
        try {
            LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
            Configuration cfg = ctx.getConfiguration();
            LogTap tap = new LogTap(s);
            tap.start();
            cfg.getRootLogger().addAppender(tap, Level.INFO, null);
            return tap;
        } catch (Throwable t) {
            s.unavailable("log capture: " + t);
            return null;
        }
    }

    void detach() {
        try {
            LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
            ctx.getConfiguration().getRootLogger().removeAppender(NAME);
            stop();
        } catch (Throwable ignored) { }
    }

    @Override
    public void append(LogEvent e) {
        try {
            Level lv = e.getLevel();
            lines++;
            String msg = e.getMessage() == null ? "" : e.getMessage().getFormattedMessage();
            if (msg == null) msg = "";
            if (lv.isMoreSpecificThan(Level.WARN)) {
                if (msg.startsWith("Can't keep up!")) { // the server itself says it fell behind
                    cantKeepUp++;
                    session.event("Server says: " + msg);
                }
                group(e, msg, lv);
            } else if (msg.contains("lost connection") || msg.contains("Disconnecting") || msg.contains("was kicked") || msg.contains("Kicked")) {
                session.event("Log: " + trim(msg, 200));
            }
        } catch (Throwable ignored) { }
    }

    private static String trim(String s, int n) {
        s = s.replace('\n', ' ').replace('\r', ' ');
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    private synchronized void group(LogEvent e, String msg, Level lv) {
        String logger = e.getLoggerName() == null ? "(root)" : e.getLoggerName();
        Report.LogGroup g = groups.get(logger);
        Throwable thrown = e.getThrown();
        if (g == null) {
            if (groups.size() > 300) return;
            g = new Report.LogGroup();
            g.logger = logger;
            g.mod = modFor(logger, thrown);
            g.first = trim(msg, 220);
            if (thrown != null) g.firstThrowable = trim(thrown.toString(), 200);
            groups.put(logger, g);
        } else if (g.mod.equals("?") && thrown != null) g.mod = modFor(logger, thrown);
        if (lv.isMoreSpecificThan(Level.FATAL)) g.fatals++;
        else if (lv.isMoreSpecificThan(Level.ERROR)) g.errors++;
        else g.warns++;
        String key = trim(msg.replaceAll("\\d+", "#"), 110);
        if (g.messages.size() < 30 || g.messages.containsKey(key)) g.messages.merge(key, 1L, Long::sum);
    }

    private String modFor(String logger, Throwable thrown) {
        String cached = modOfLogger.get(logger);
        if (cached != null && !cached.equals("?")) return cached;
        ModMap mm = session.mods;
        String r = "?";
        if (mm != null) {
            String c = mm.classify(logger, null);
            if (!Session.isBase(c) && !c.equals("(unknown)")) r = c;
            if (r.equals("?") && thrown != null) {
                for (StackTraceElement st : thrown.getStackTrace()) {
                    String m = mm.classify(st.getClassName(), st.getMethodName());
                    if (!Session.isBase(m) && !m.equals("(unknown)")) { r = m; break; }
                }
            }
            if (r.equals("?")) {
                String norm = logger.toLowerCase().replaceAll("[^a-z0-9]", "");
                for (String id : mm.modIds()) {
                    String n = id.replaceAll("[^a-z0-9]", "");
                    if (n.length() >= 4 && norm.contains(n)) { r = id; break; }
                }
            }
            if (r.equals("?") && Session.isBase(c)) r = c;
        }
        modOfLogger.put(logger, r);
        return r;
    }

    synchronized void fill(Report r) {
        r.logLinesSeen = lines;
        r.logs.addAll(groups.values().stream().map(g -> {
            Report.LogGroup c = new Report.LogGroup();
            c.logger = g.logger; c.mod = g.mod; c.warns = g.warns; c.errors = g.errors; c.fatals = g.fatals; c.first = g.first; c.firstThrowable = g.firstThrowable;
            c.messages.putAll(g.messages);
            return c;
        }).toList());
        if (cantKeepUp > 0) r.counters.put("\"Can't keep up!\" warnings from the server", cantKeepUp);
    }

    @SuppressWarnings("unused")
    private static Appender unused() { return null; }
}
