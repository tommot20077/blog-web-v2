package dowob.xyz.blog.module.file.support;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 測試用圖片產生器：以「小檔案、大寬高」的合法圖片，以及只含 header 的 WebP，測試像素上限（FILE-04）。
 *
 * @author Yuan
 * @version 1.0
 */
public final class TestImages {

    private TestImages() {
    }

    /**
     * 產生指定寬高的純色 PNG（1-bit 灰階，記憶體約 寬×高/8 位元組，壓縮後僅數十 KB）。
     *
     * @param width  寬
     * @param height 高
     * @return 合法 PNG 位元組
     * @throws IOException 編碼失敗
     */
    public static byte[] solidPng(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /**
     * 產生只有 header 的 WebP（VP8X 擴充格式，寬高各 24 位元，可表示超過 16384 的尺寸）。
     *
     * @param width  寬（1～2^24）
     * @param height 高（1～2^24）
     * @return WebP 位元組（30 位元組，僅含 RIFF 與 VP8X chunk header）
     */
    public static byte[] webpVp8x(int width, int height) {
        byte[] b = riff("VP8X", 10);
        b[20] = 0x08;
        put24(b, 24, width - 1);
        put24(b, 27, height - 1);
        return b;
    }

    /**
     * 產生只有 header 的 WebP（VP8L 無損格式，寬高各 14 位元）。
     *
     * @param width  寬（1～16384）
     * @param height 高（1～16384）
     * @return WebP 位元組
     */
    public static byte[] webpVp8l(int width, int height) {
        byte[] b = riff("VP8L", 5);
        b[20] = 0x2f;
        long bits = (width - 1L) | ((height - 1L) << 14);
        for (int i = 0; i < 4; i++) {
            b[21 + i] = (byte) (bits >> (8 * i));
        }
        return b;
    }

    /**
     * 產生只有 header 的 WebP（VP8 有損格式，寬高各 14 位元）。
     *
     * @param width  寬（1～16383）
     * @param height 高（1～16383）
     * @return WebP 位元組
     */
    public static byte[] webpVp8(int width, int height) {
        byte[] b = riff("VP8 ", 10);
        b[20] = 0x10;
        b[23] = (byte) 0x9d;
        b[24] = 0x01;
        b[25] = 0x2a;
        b[26] = (byte) width;
        b[27] = (byte) (width >> 8);
        b[28] = (byte) height;
        b[29] = (byte) (height >> 8);
        return b;
    }

    private static byte[] riff(String fourCc, int chunkSize) {
        byte[] b = new byte[30];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, b, 0, 4);
        b[4] = 22;
        System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, b, 8, 4);
        System.arraycopy(fourCc.getBytes(StandardCharsets.US_ASCII), 0, b, 12, 4);
        b[16] = (byte) chunkSize;
        return b;
    }

    private static void put24(byte[] b, int offset, int value) {
        b[offset] = (byte) value;
        b[offset + 1] = (byte) (value >> 8);
        b[offset + 2] = (byte) (value >> 16);
    }
}
