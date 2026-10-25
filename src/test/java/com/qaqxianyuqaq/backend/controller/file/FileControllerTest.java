package com.qaqxianyuqaq.backend.controller.file;
import com.qaqxianyuqaq.backend.domain.entity.ChallengeFile;
import com.qaqxianyuqaq.backend.domain.enums.FileKind;
import com.qaqxianyuqaq.backend.service.challenge.FileStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import java.io.ByteArrayInputStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
/** 控制器测试只替代服务接口；存储/权限另由真实 Docker 回归覆盖。 */
class FileControllerTest {
    @Test void legacyMalformedMimeFallsBackToSafeDownload() throws Exception {
        var service=mock(FileStorageService.class);var file=file();
        when(service.requireDownloadableFile("f",null)).thenReturn(file);
        when(service.openDownload(file,0,3)).thenReturn(new ByteArrayInputStream(new byte[]{1,2,3}));
        var response=new FileController(service).download("f",null,null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
        try(var bytes=response.getBody().getInputStream()) {assertThat(bytes.readAllBytes()).containsExactly(1,2,3);}
    }
    @Test void multipleAndOutOfBoundsRangesAreRejectedWithoutReadingObject() {
        var service=mock(FileStorageService.class);var file=file();
        when(service.requireDownloadableFile("f",null)).thenReturn(file);
        for(String range:java.util.List.of("bytes=0-1,2-2","bytes=99-100")) {
            var response=new FileController(service).download("f",null,range);
            assertThat(response.getStatusCode().value()).isEqualTo(416);
            assertThat(response.getHeaders().getFirst("Content-Range")).isEqualTo("bytes */3");
        }
        verify(service,never()).openDownload(any(),anyLong(),anyLong());
    }
    private ChallengeFile file() {var file=new ChallengeFile();file.setKind(FileKind.ATTACHMENT);file.setSizeBytes(3L);file.setOriginalName("中文.txt");file.setContentType("invalid");return file;}
}
