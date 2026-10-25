package com.qaqxianyuqaq.backend.controller.file;
import com.qaqxianyuqaq.backend.domain.enums.FileKind;
import com.qaqxianyuqaq.backend.security.UserPrincipal;
import com.qaqxianyuqaq.backend.service.challenge.FileStorageService;
import org.springframework.http.*;
import org.springframework.core.io.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
/** 每次下载重新验证登录、角色、题目和赛事；不再发放可以转发的 S3 签名。 */
@RestController @RequestMapping("/api/v1/files")
public class FileController {
    private final FileStorageService service;
    public FileController(FileStorageService service) {this.service=service;}
    @GetMapping("/{fileId}")
    public ResponseEntity<Resource> download(@PathVariable String fileId,@AuthenticationPrincipal UserPrincipal principal,
            @RequestHeader(value="Range",required=false) String range) {
        var file=service.requireDownloadableFile(fileId,principal);
        long size=file.getSizeBytes(),start=0,end=size-1;
        HttpHeaders headers=new HttpHeaders();
        headers.setCacheControl("private, no-store");
        headers.set("X-Content-Type-Options","nosniff");
        headers.set("Content-Security-Policy","sandbox");
        headers.set("Accept-Ranges","bytes");
        if(range!=null) {
            try {
                var ranges=HttpRange.parseRanges(range);
                if(ranges.size()!=1 || size==0) throw new IllegalArgumentException();
                start=ranges.getFirst().getRangeStart(size);end=ranges.getFirst().getRangeEnd(size);
                if(start>=size || end<start) throw new IllegalArgumentException();
            } catch(IllegalArgumentException error) {
                headers.set("Content-Range","bytes */"+size);
                return ResponseEntity.status(416).headers(headers).build();
            }
            headers.set("Content-Range","bytes "+start+"-"+end+"/"+size);
        }
        headers.setContentLength(end-start+1);
        // 兼容升级前已存入的畸形 MIME，不能因此让合法旧附件无法下载。
        MediaType type=MediaType.APPLICATION_OCTET_STREAM;
        try { if(file.getContentType()!=null) {var parsed=MediaType.parseMediaType(file.getContentType());if(parsed.isConcrete())type=parsed;} }
        catch(IllegalArgumentException ignored) { }
        headers.setContentType(type);
        headers.setContentDisposition((file.getKind()==FileKind.COVER?ContentDisposition.inline():ContentDisposition.attachment())
                .filename(file.getOriginalName(),StandardCharsets.UTF_8).build());
        var resource=new InputStreamResource(service.openDownload(file,start,end-start+1));
        return ResponseEntity.status(range==null?200:206).headers(headers).body(resource);
    }
}
