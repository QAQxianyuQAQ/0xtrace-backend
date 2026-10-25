package com.qaqxianyuqaq.backend.util;
import com.qaqxianyuqaq.backend.exception.*;
import org.springframework.web.multipart.MultipartFile;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
/** 只解码白名单图片；输入走内存管道，不落盘、不允许读取网络和本地文件。 */
public final class CoverValidator {
    private static final Semaphore DECODERS=new Semaphore(2);
    private CoverValidator() {}
    public static void validate(MultipartFile file) {
        byte[] bytes;
        try { bytes=file.getBytes(); } catch (java.io.IOException error) { throw invalid(); }
        String format=detect(bytes), mime=file.getContentType();
        String actual=switch(format) {
            case "png_pipe" -> "image/png"; case "jpeg_pipe" -> "image/jpeg";
            case "webp_pipe" -> "image/webp"; case "mov" -> "image/avif"; default -> "";
        };
        if (actual.isEmpty() || !actual.equalsIgnoreCase(mime)) throw invalid();
        if (!DECODERS.tryAcquire()) throw new StorageException(ErrorCode.STORAGE_ERROR,"图片校验繁忙，请稍后重试");
        Process process=null;
        try (var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            process=new ProcessBuilder("ffmpeg","-v","error","-xerror","-max_alloc","67108864",
                    "-protocol_whitelist","pipe","-f",format,"-max_pixels","16777216",
                    "-threads","1","-i","pipe:0","-frames:v","1","-threads","1",
                    "-f","rawvideo","-pix_fmt","rgba","pipe:1")
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            final Process child=process;
            Future<?> input=executor.submit(() -> { try(var out=child.getOutputStream()) { out.write(bytes); } return null; });
            Future<Long> output=executor.submit(() -> { try(var in=child.getInputStream()) { return in.transferTo(java.io.OutputStream.nullOutputStream()); } });
            try {
            if (!child.waitFor(15,TimeUnit.SECONDS)) { child.destroyForcibly(); throw invalid(); }
            input.get(1,TimeUnit.SECONDS);
            if (child.exitValue()!=0 || output.get(1,TimeUnit.SECONDS)<4) throw invalid();
            } finally { child.destroyForcibly(); }
        } catch (StorageException error) { throw error; }
        catch (Exception error) { if (error instanceof InterruptedException) Thread.currentThread().interrupt(); throw invalid(); }
        finally { if(process!=null) process.destroyForcibly(); DECODERS.release(); }
    }
    private static String detect(byte[] b) {
        if (b.length<16) return "";
        if (b[0]==(byte)137 && new String(b,1,3,StandardCharsets.US_ASCII).equals("PNG")) return "png_pipe";
        if (b[0]==(byte)255 && b[1]==(byte)216 && b[2]==(byte)255) return "jpeg_pipe";
        if (new String(b,0,4,StandardCharsets.US_ASCII).equals("RIFF") && new String(b,8,4,StandardCharsets.US_ASCII).equals("WEBP")) return "webp_pipe";
        if (new String(b,4,4,StandardCharsets.US_ASCII).equals("ftyp")) {
            int size=java.nio.ByteBuffer.wrap(b,0,4).getInt();
            if (size>=16 && size<=b.length && size<=1024) {
                for(int i=8;i+4<=size;i+=4) {
                    String brand=new String(b,i,4,StandardCharsets.US_ASCII);
                    if (brand.equals("avif") || brand.equals("avis")) return "mov";
                }
            }
        }
        return "";
    }
    private static StorageException invalid() { return new StorageException(ErrorCode.UNSUPPORTED_FILE_TYPE,"封面不是可安全解码的 PNG/JPEG/WebP/AVIF 图片，或超过 1600 万像素"); }
}
