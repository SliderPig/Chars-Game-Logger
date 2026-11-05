package com.example.clogger;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Minecraft's method names are scrambled (m_5703_) in the real game. A small bundled table (srg_methods.txt.gz,
 * lines like "m_5703_=tickServer") turns the common ones back into readable names.
 */
public final class Names {
    private static volatile Map<String, String> table;

    private Names() {}

    private static Map<String, String> table() {
        Map<String, String> t = table;
        if (t != null) return t;
        t = new HashMap<>();
        try (InputStream in = Names.class.getResourceAsStream("/srg_methods.txt.gz")) {
            if (in != null) {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        int eq = line.indexOf('=');
                        if (eq > 0) t.put(line.substring(0, eq), line.substring(eq + 1));
                    }
                }
            }
        } catch (Throwable ignored) { }
        table = t;
        return t;
    }

    public static boolean loaded() { return !table().isEmpty(); }

    /** Readable method name, or the scrambled one if unknown. */
    public static String method(String name) {
        if (name.length() > 3 && name.startsWith("m_") && name.endsWith("_")) {
            String n = table().get(name);
            if (n != null) return n;
        }
        return name;
    }
}
