package dowob.xyz.blog.module.file.service;

import dowob.xyz.blog.module.file.support.TestImages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ImageDimensionReader} 單元測試：只讀 header 取得寬高（FILE-04）。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ImageDimensionReader 單元測試")
class ImageDimensionReaderTest {

    @Test
    @DisplayName("read_png_returnsWidthAndHeight：大尺寸 PNG 不需解碼即可取得寬高")
    void read_png_returnsWidthAndHeight() throws Exception {
        var dims = ImageDimensionReader.read(TestImages.solidPng(12000, 9000));

        assertThat(dims).isPresent();
        assertThat(dims.get().width()).isEqualTo(12000);
        assertThat(dims.get().height()).isEqualTo(9000);
        assertThat(dims.get().pixels()).isEqualTo(108_000_000L);
    }

    @Test
    @DisplayName("read_jpeg_returnsWidthAndHeight")
    void read_jpeg_returnsWidthAndHeight() throws Exception {
        byte[] jpeg = getClass().getResourceAsStream("/test.jpg").readAllBytes();
        BufferedImage expected = ImageIO.read(new ByteArrayInputStream(jpeg));

        var dims = ImageDimensionReader.read(jpeg);

        assertThat(dims).isPresent();
        assertThat(dims.get().width()).isEqualTo(expected.getWidth());
        assertThat(dims.get().height()).isEqualTo(expected.getHeight());
    }

    @Test
    @DisplayName("read_gif_returnsWidthAndHeight")
    void read_gif_returnsWidthAndHeight() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_BYTE_INDEXED), "gif", out);

        var dims = ImageDimensionReader.read(out.toByteArray());

        assertThat(dims).isPresent();
        assertThat(dims.get().width()).isEqualTo(40);
        assertThat(dims.get().height()).isEqualTo(30);
    }

    @Test
    @DisplayName("read_webpVp8x_returnsWidthAndHeight：JDK ImageIO 無 WebP reader，改由自行解析 header")
    void read_webpVp8x_returnsWidthAndHeight() {
        var dims = ImageDimensionReader.read(TestImages.webpVp8x(30000, 3000));

        assertThat(dims).isPresent();
        assertThat(dims.get().width()).isEqualTo(30000);
        assertThat(dims.get().height()).isEqualTo(3000);
    }

    @Test
    @DisplayName("read_webpVp8l_returnsWidthAndHeight")
    void read_webpVp8l_returnsWidthAndHeight() {
        var dims = ImageDimensionReader.read(TestImages.webpVp8l(16384, 777));

        assertThat(dims).isPresent();
        assertThat(dims.get().width()).isEqualTo(16384);
        assertThat(dims.get().height()).isEqualTo(777);
    }

    @Test
    @DisplayName("read_webpVp8_returnsWidthAndHeight")
    void read_webpVp8_returnsWidthAndHeight() {
        var dims = ImageDimensionReader.read(TestImages.webpVp8(640, 480));

        assertThat(dims).isPresent();
        assertThat(dims.get().width()).isEqualTo(640);
        assertThat(dims.get().height()).isEqualTo(480);
    }

    @Test
    @DisplayName("read_truncatedWebp_returnsEmpty：header 不完整不得拋例外")
    void read_truncatedWebp_returnsEmpty() {
        byte[] full = TestImages.webpVp8x(100, 100);
        byte[] truncated = java.util.Arrays.copyOf(full, 22);

        assertThat(ImageDimensionReader.read(truncated)).isEmpty();
    }

    @Test
    @DisplayName("read_garbageOrNull_returnsEmpty")
    void read_garbageOrNull_returnsEmpty() {
        assertThat(ImageDimensionReader.read(new byte[]{1, 2, 3, 4, 5})).isEmpty();
        assertThat(ImageDimensionReader.read(new byte[0])).isEmpty();
        assertThat(ImageDimensionReader.read(null)).isEmpty();
    }
}
