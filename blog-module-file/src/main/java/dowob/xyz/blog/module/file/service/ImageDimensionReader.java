package dowob.xyz.blog.module.file.service;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Optional;

/**
 * 只讀圖片 header 取得寬高的工具（FILE-04：防 decompression bomb）。
 *
 * <p>JPEG／PNG／GIF 委派 JDK {@link ImageIO} 的 {@link ImageReader#getWidth(int)}，只解析 header、不解碼像素。
 * JDK 沒有 WebP reader，因此 WebP 由本類別直接解析 RIFF header（VP8／VP8L／VP8X 三種 chunk），
 * 不引入額外的 ImageIO 外掛。上傳端與縮圖端共用本類別，避免兩邊對「寬高」的認定不一致。</p>
 *
 * <p>讀不到寬高（檔案不是可辨識的圖片、header 被截斷）一律回傳 empty，不拋例外；呼叫端自行決定如何處理。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
public final class ImageDimensionReader {

    /** WebP 解析所需的最少位元組數（RIFF 12 + chunk header 8 + 最長的 VP8X payload 到高度為止 10） */
    private static final int WEBP_MIN_HEADER = 30;

    private ImageDimensionReader() {
    }

    /**
     * 圖片寬高。
     *
     * @param width  寬（像素）
     * @param height 高（像素）
     */
    public record Dimensions(long width, long height) {

        /**
         * 總像素數。
         *
         * @return 寬 × 高
         */
        public long pixels() {
            return width * height;
        }
    }

    /**
     * 讀取圖片寬高（只讀 header，不解碼像素）。
     *
     * @param data 圖片位元組；可為 null
     * @return 寬高；無法辨識或 header 不完整時為 empty
     */
    public static Optional<Dimensions> read(byte[] data) {
        if (data == null || data.length == 0) {
            return Optional.empty();
        }
        if (isWebp(data)) {
            return readWebp(data);
        }
        return readWithImageIo(data);
    }

    /**
     * 以 ImageIO 讀取 JPEG／PNG／GIF header。
     *
     * @param data 圖片位元組
     * @return 寬高；無對應 reader 或 header 損毀時為 empty
     */
    private static Optional<Dimensions> readWithImageIo(byte[] data) {
        try (ImageInputStream iis = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                return Optional.empty();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis);
                return Optional.of(new Dimensions(reader.getWidth(0), reader.getHeight(0)));
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            log.debug("無法讀取圖片尺寸: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static boolean isWebp(byte[] d) {
        return d.length >= 12
                && ascii(d, 0, 4).equals("RIFF")
                && ascii(d, 8, 4).equals("WEBP");
    }

    /**
     * 解析 WebP 的第一個 chunk（VP8／VP8L／VP8X）取得寬高。
     *
     * @param d WebP 位元組（已確認 RIFF／WEBP 簽章）
     * @return 寬高；header 不完整或 chunk 類型不明時為 empty
     */
    private static Optional<Dimensions> readWebp(byte[] d) {
        if (d.length < WEBP_MIN_HEADER) {
            return Optional.empty();
        }
        String chunk = ascii(d, 12, 4);
        switch (chunk) {
            case "VP8X":
                /* 擴充格式：24 位元 (寬-1)、(高-1)，位於 chunk payload 偏移 4 與 7 */
                return Optional.of(new Dimensions(le(d, 24, 3) + 1L, le(d, 27, 3) + 1L));
            case "VP8L":
                /* 無損格式：簽章 0x2f 後 14 位元 (寬-1)、14 位元 (高-1) */
                if ((d[20] & 0xff) != 0x2f) {
                    return Optional.empty();
                }
                long bits = le(d, 21, 4);
                return Optional.of(new Dimensions((bits & 0x3fff) + 1L, ((bits >> 14) & 0x3fff) + 1L));
            case "VP8 ":
                /* 有損格式：3 位元組 frame tag + 起始碼 9d 01 2a 後，各 16 位元（低 14 位元有效）寬、高 */
                if ((d[23] & 0xff) != 0x9d || d[24] != 0x01 || (d[25] & 0xff) != 0x2a) {
                    return Optional.empty();
                }
                return Optional.of(new Dimensions(le(d, 26, 2) & 0x3fff, le(d, 28, 2) & 0x3fff));
            default:
                return Optional.empty();
        }
    }

    private static String ascii(byte[] d, int offset, int length) {
        return new String(d, offset, length, StandardCharsets.US_ASCII);
    }

    private static long le(byte[] d, int offset, int length) {
        long value = 0;
        for (int i = length - 1; i >= 0; i--) {
            value = (value << 8) | (d[offset + i] & 0xff);
        }
        return value;
    }
}
