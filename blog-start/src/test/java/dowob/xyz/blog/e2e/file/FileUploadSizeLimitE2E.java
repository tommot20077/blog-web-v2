package dowob.xyz.blog.e2e.file;

import com.fasterxml.jackson.databind.JsonNode;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.e2e.config.AbstractE2ETest;
import dowob.xyz.blog.e2e.support.AuthHelper;
import dowob.xyz.blog.e2e.support.DatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上傳大小上限的真 server E2E（FILE-05、A6b）。
 *
 * <p>MockMvc 的 multipart 會繞過 MultipartResolver／Tomcat 的大小檢查，無法驗證
 * {@code spring.servlet.multipart.*} 與 {@code MaxUploadSizeExceededException} 的處理，
 * 因此這裡以 {@code RANDOM_PORT} 的真實 Tomcat + {@link HttpClient} 實際送出超限 body。</p>
 *
 * <ul>
 *   <li>約 19MB 的文章圖片可上傳（上限 20MB）。</li>
 *   <li>超過 20MB 的文章圖片回 {@code FILE_TOO_LARGE}（A0402）、HTTP 400。</li>
 *   <li>約 6MB 的頭像回 {@code FILE_TOO_LARGE}（頭像維持 5MB）。</li>
 *   <li>比 swallow（32MB）小、但比 max-request-size（21MB）大的請求（約 28MB）仍拿到 400，而不是被 Tomcat 斷線。</li>
 * </ul>
 *
 * <p>記憶體：超限案例的 body 以串流即時產生（只有 JPEG magic bytes 後接零），不在測試端配置 MB 級 byte[]；
 * 合法的約 19MB PNG 先寫入暫存檔再以檔案串流送出。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("File 上傳大小上限 E2E（真 server，FILE-05／A6b）")
class FileUploadSizeLimitE2E extends AbstractE2ETest {

    private static final int MB = 1024 * 1024;

    @LocalServerPort
    private int port;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private AuthHelper authHelper;

    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
    }

    /** 產生指定長度的內容串流：JPEG magic bytes 開頭、其餘為零（只用來測大小，不是可解碼的圖片） */
    private static InputStream jpegLikeStream(long size) {
        return new InputStream() {
            private long position;

            @Override
            public int read() {
                if (position >= size) {
                    return -1;
                }
                long p = position++;
                return p == 0 ? 0xFF : p == 1 ? 0xD8 : p == 2 ? 0xFF : p == 3 ? 0xE0 : 0;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (position >= size) {
                    return -1;
                }
                int n = (int) Math.min(len, size - position);
                for (int i = 0; i < n; i++) {
                    b[off + i] = (byte) read();
                }
                return n;
            }
        };
    }

    /** 約 19MB、不可壓縮的真實 PNG，寫入暫存檔（避免測試端長期持有 byte[]） */
    private static Path noisePngFile() throws Exception {
        BufferedImage img = new BufferedImage(2540, 2540, BufferedImage.TYPE_INT_RGB);
        Random rnd = new Random(7);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                img.setRGB(x, y, rnd.nextInt(0x1000000));
            }
        }
        Path file = Files.createTempFile("e2e-noise-", ".png");
        ImageIO.write(img, "png", file.toFile());
        return file;
    }

    /**
     * 以串流組出 multipart 請求並送出；Content-Length 已知，body 不整份放進記憶體。
     *
     * @param contentLength 檔案內容長度
     * @param content       檔案內容串流供應者（可重複呼叫）
     */
    private HttpResponse<String> upload(String token, String usageType, String filename, String contentType,
                                        long contentLength, java.util.function.Supplier<InputStream> content) throws Exception {
        String boundary = "----e2e" + UUID.randomUUID();
        byte[] prefix = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"usageType\"\r\n\r\n" + usageType + "\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        long total = prefix.length + contentLength + suffix.length;
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/files/upload"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(
                        () -> new SequenceInputStream(new SequenceInputStream(
                                new ByteArrayInputStream(prefix), content.get()), new ByteArrayInputStream(suffix))), total))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String authorToken(String suffix) throws Exception {
        return authHelper.createUserWithRole("size-" + suffix + "@test.com", "Password123!",
                "size" + suffix, "Size" + suffix, Role.AUTHOR);
    }

    @Test
    @DisplayName("約 19MB 的合法文章圖片 PNG 可上傳（A6b：上限 20MB）")
    void upload_articleImageAround19Mb_succeeds() throws Exception {
        Path png = noisePngFile();
        try {
            long size = Files.size(png);
            assertThat(size).isBetween(18L * MB, 20L * MB);

            HttpResponse<String> response = upload(authorToken("ok"), "ARTICLE_CONTENT", "ok.png", "image/png", size, () -> {
                try {
                    return Files.newInputStream(png);
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(objectMapper.readTree(response.body()).path("code").asText()).isEqualTo("00000");
        } finally {
            Files.deleteIfExists(png);
        }
    }

    @Test
    @DisplayName("文章圖片約 21MB（超過 20MB 上限）→ HTTP 400 + FILE_TOO_LARGE(A0402)")
    void upload_articleImageOverLimit_returnsFileTooLarge() throws Exception {
        HttpResponse<String> response = upload(authorToken("big"), "ARTICLE_CONTENT", "big.jpg", "image/jpeg",
                21L * MB, () -> jpegLikeStream(21L * MB));

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.path("code").asText()).isEqualTo("A0402");
    }

    @Test
    @DisplayName("頭像約 6MB（頭像維持 5MB 上限）→ HTTP 400 + FILE_TOO_LARGE(A0402)")
    void upload_avatarOverFiveMb_returnsFileTooLarge() throws Exception {
        HttpResponse<String> response = upload(authorToken("avatar"), "AVATAR", "av.jpg", "image/jpeg",
                6L * MB, () -> jpegLikeStream(6L * MB));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(objectMapper.readTree(response.body()).path("code").asText()).isEqualTo("A0402");
    }

    @Test
    @DisplayName("約 28MB（大於 max-request-size 21MB、小於 swallow 32MB）→ 仍拿到 HTTP 400 + A0402，Tomcat 不得直接斷線")
    void upload_betweenRequestSizeAndSwallow_stillReturnsFileTooLargeNotConnectionReset() throws Exception {
        HttpResponse<String> response = upload(authorToken("swallow"), "ARTICLE_CONTENT", "huge.jpg", "image/jpeg",
                28L * MB, () -> jpegLikeStream(28L * MB));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(objectMapper.readTree(response.body()).path("code").asText()).isEqualTo("A0402");
    }
}
