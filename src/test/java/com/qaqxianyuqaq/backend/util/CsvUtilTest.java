package com.qaqxianyuqaq.backend.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** CSV 工具单元测试：覆盖引号转义与换行兼容。 */
class CsvUtilTest {

    @Test
    @DisplayName("解析带引号、逗号与 CRLF 的 CSV 内容")
    void shouldParseQuotedCsv() {
        String content = "username,password,team,note\r\nfield_1,secret123,\"North, Signal\",\"备注\"\r\n";

        List<List<String>> rows = CsvUtil.parse(content);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsExactly("username", "password", "team", "note");
        assertThat(rows.get(1)).containsExactly("field_1", "secret123", "North, Signal", "备注");
    }

    @Test
    @DisplayName("导出时对包含逗号的字段自动加引号")
    void shouldEscapeWhenWriting() {
        String csv = CsvUtil.write(List.of(List.of("username", "team"), List.of("field_2", "A, B")));

        assertThat(csv).contains("\"A, B\"");
    }
}
