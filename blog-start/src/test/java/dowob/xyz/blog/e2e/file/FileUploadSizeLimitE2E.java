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
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上傳大小上限的真 server E2E（FILE-05）。
 *
 * <p>MockMvc 的 multipart 會繞過 MultipartResolver／Tomcat 的大小檢查，無法驗證
 * {@code spring.servlet.multipart.*} 與 {@code MaxUploadSizeExceededException} 的處理，
 * 因此這裡以 {@code RANDOM_PORT} 的真實 Tomcat + {@link HttpClient} 實際送出超限 body。</p>
 *
 * <ul>
 *   <li>5MB 以內的合法圖片可上傳（框架預設 1MB 時此案例會失敗）。</li>
 *   <li>超過 5MB 的檔案回 {@code FILE_TOO_LARGE}（A0402）、HTTP 400。</li>
 *   <li>約 8MB 的誤傳也要拿到 400，而不是被 Tomcat 斷線（{@code server.tomcat.max-swallow-size}，預設 2MB）。</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("File 上傳大小上限 E2E（真 server，FILE-05）")
class FileUploadSizeLimitE2E extends AbstractE2ETest {

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

    /** 以 JPEG magic bytes 開頭、其餘填充的假檔案（只用來測大小，不需要是可解碼的圖片） */
    private static byte[] jpegLikeOfSize(int size) {
        byte[] bytes = new byte[size];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        bytes[3] = (byte) 0xE0;
        return bytes;
    }

    /** 約 4MB、不可壓縮的真實 PNG */
    private static byte[] noisePng() throws Exception {
        BufferedImage img = new BufferedImage(1100, 1100, BufferedImage.TYPE_INT_RGB);
        Random rnd = new Random(7);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                img.setRGB(x, y, rnd.nextInt(0x1000000));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private HttpResponse<String> upload(String token, String filename, String contentType, byte[] content) throws Exception {
        String boundary = "----e2e" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"usageType\"\r\n\r\nARTICLE_CONTENT\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(content);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/files/upload"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String authorToken(String suffix) throws Exception {
        return authHelper.createUserWithRole("size-" + suffix + "@test.com", "Password123!",
                "size" + suffix, "Size" + suffix, Role.AUTHOR);
    }

    @Test
    @DisplayName("約 4MB 的合法 PNG 可上傳（超過框架預設 1MB、未超過 5MB）")
    void upload_withinFiveMb_succeeds() throws Exception {
        byte[] png = noisePng();
        assertThat(png.length).isBetween(2_000_000, 5_000_000);

        HttpResponse<String> response = upload(authorToken("ok"), "ok.png", "image/png", png);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(response.body()).path("code").asText()).isEqualTo("00000");
    }

    @Test
    @DisplayName("約 6MB（超過 5MB 上限）→ HTTP 400 + FILE_TOO_LARGE(A0402)")
    void upload_slightlyOverLimit_returnsFileTooLarge() throws Exception {
        HttpResponse<String> response = upload(authorToken("six"), "big.jpg", "image/jpeg", jpegLikeOfSize(6 * 1024 * 1024));

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.path("code").asText()).isEqualTo("A0402");
    }

    @Test
    @DisplayName("約 8MB 的誤傳 → 仍拿到 HTTP 400 + A0402（Tomcat 不得因 swallow 上限直接斷線）")
    void upload_farOverLimit_stillReturnsFileTooLargeNotConnectionReset() throws Exception {
        HttpResponse<String> response = upload(authorToken("eight"), "huge.jpg", "image/jpeg", jpegLikeOfSize(8 * 1024 * 1024));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(objectMapper.readTree(response.body()).path("code").asText()).isEqualTo("A0402");
    }
}
