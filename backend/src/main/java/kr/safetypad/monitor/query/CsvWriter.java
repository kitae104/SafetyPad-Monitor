package kr.safetypad.monitor.query;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

/** CSV for Korean Excel: UTF-8 BOM, CRLF, every text cell quoted and neutralised against formula injection. */
public final class CsvWriter {
    public static final char BOM = '﻿';

    private CsvWriter() {}

    /** Text cells beginning with = + - @ TAB CR (even after spaces) could execute as formulas in spreadsheets. */
    public static String sanitize(String v) {
        if (v == null || v.isEmpty()) return "";
        String t = v.stripLeading();
        char c = t.isEmpty() ? ' ' : t.charAt(0);
        boolean risky = c == '=' || c == '+' || c == '-' || c == '@' || v.charAt(0) == '\t' || v.charAt(0) == '\r';
        return risky ? "'" + v : v;
    }

    public static String textCell(String v) {
        return '"' + sanitize(v).replace("\"", "\"\"") + '"';
    }

    /** Machine-generated numeric cells are written unquoted and are never sanitised (negative numbers stay numbers). */
    public static String numberCell(Object v) {
        return v == null ? "" : v.toString();
    }

    public static void writeRow(Writer w, List<String> cells) throws IOException {
        w.write(String.join(",", cells));
        w.write("\r\n");
    }
}
