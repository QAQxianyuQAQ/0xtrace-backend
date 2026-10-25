package com.qaqxianyuqaq.backend.util;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import com.qaqxianyuqaq.backend.domain.dto.contestant.ContestantCreateDTO;
import jakarta.validation.Validation;
/** 非 ASCII 密码和表格导出的共享信任边界测试。 */
class ReliabilityValidationTest {
    @Test void passwordUsesUtf8Bytes() {
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            var validator=factory.getValidator();
            assertThat(validator.validate(new ContestantCreateDTO("a","x".repeat(72),null,null,null,null))).isEmpty();
            assertThat(validator.validate(new ContestantCreateDTO("a","汉".repeat(24),null,null,null,null))).isEmpty();
            assertThat(validator.validate(new ContestantCreateDTO("a","汉".repeat(25),null,null,null,null))).isNotEmpty();
        }
    }
    @Test void csvNeutralizesFormulaPrefixesWithoutLosingQuotes() {
        for(String value:java.util.List.of("=1+1","+1","-1","@SUM(1)","  =1","\t=1","\r=1","\n=1"))
            assertThat(CsvUtil.parse(CsvUtil.write(java.util.List.of(java.util.List.of(value)))).getFirst().getFirst()).isEqualTo("'"+value);
        assertThat(CsvUtil.parse(CsvUtil.write(java.util.List.of(java.util.List.of("中文, \"引号\"")))).getFirst().getFirst()).isEqualTo("中文, \"引号\"");
    }
}
