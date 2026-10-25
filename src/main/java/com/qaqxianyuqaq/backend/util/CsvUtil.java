package com.qaqxianyuqaq.backend.util;

import java.util.ArrayList;
import java.util.List;

/**
 * CSV 解析与生成工具。
 *
 * <p>支持双引号包裹、转义引号与逗号换行，用于选手账号批量导入导出。</p>
 */
public final class CsvUtil {

    private CsvUtil() {
    }

    /** 解析 CSV 文本为行列表，自动兼容 CRLF 与 LF。 */
    public static List<List<String>> parse(String content) {
        List<List<String>> rows = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return rows;
        }
        List<String> currentRow = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        for (int index = 0; index < content.length(); index++) {
            char current = content.charAt(index);
            if (inQuotes) {
                if (current == '"') {
                    if (index + 1 < content.length() && content.charAt(index + 1) == '"') {
                        field.append('"');
                        index++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(current);
                }
                continue;
            }
            switch (current) {
                case '"' -> inQuotes = true;
                case ',' -> {
                    currentRow.add(field.toString());
                    field.setLength(0);
                }
                case '\r' -> {
                    // 忽略 CR，等待随后的 LF 结束行
                }
                case '\n' -> {
                    currentRow.add(field.toString());
                    field.setLength(0);
                    rows.add(currentRow);
                    currentRow = new ArrayList<>();
                }
                default -> field.append(current);
            }
        }
        if (!field.isEmpty() || !currentRow.isEmpty()) {
            currentRow.add(field.toString());
            rows.add(currentRow);
        }
        return rows;
    }

    /** 生成 CSV 文本，自动为包含特殊字符的字段加引号。 */
    public static String write(List<List<String>> rows) {
        StringBuilder builder = new StringBuilder();
        for (List<String> row : rows) {
            for (int index = 0; index < row.size(); index++) {
                if (index > 0) {
                    builder.append(',');
                }
                builder.append(escape(row.get(index)));
            }
            builder.append('\n');
        }
        return builder.toString();
    }

    /** 转义单个字段。 */
    public static String escape(String value) {
        String safe = value == null ? "" : value;
        // 表格软件可能忽略前导空白；引号只保护 CSV 结构，单引号才阻止公式求值。
        String leading = safe.stripLeading();
        if (safe.indexOf('\t') >= 0 || safe.indexOf('\r') >= 0 || safe.indexOf('\n') >= 0
                || (!leading.isEmpty() && "=+-@".indexOf(leading.charAt(0)) >= 0)) {
            safe = "'" + safe;
        }
        boolean needQuote = safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r");
        if (!needQuote) {
            return safe;
        }
        return '"' + safe.replace("\"", "\"\"") + '"';
    }
}
