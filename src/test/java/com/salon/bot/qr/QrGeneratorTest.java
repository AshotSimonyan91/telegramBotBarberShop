package com.salon.bot.qr;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

public class QrGeneratorTest {

    @TempDir
    Path dir;

    /** Читает deep link из PNG с QR-кодом. */
    public static String decode(Path png) throws Exception {
        BufferedImage image = ImageIO.read(png.toFile());
        return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(
                new BufferedImageLuminanceSource(image)))).getText();
    }

    @Test
    void generatesReadable1000pxQrWithCaption() throws Exception {
        System.setProperty("java.awt.headless", "true");
        Path png = dir.resolve("qr.png");
        String url = QrGenerator.link("AshotSimonyanBot", "salon_1");

        QrGenerator.write(url, png);

        BufferedImage image = ImageIO.read(png.toFile());
        assertThat(image.getWidth()).isEqualTo(1000);
        assertThat(image.getHeight()).isEqualTo(1000);
        assertThat(decode(png)).isEqualTo("https://t.me/AshotSimonyanBot?start=salon_1");

        boolean captionDrawn = false;
        for (int x = 0; x < 1000 && !captionDrawn; x++) {
            for (int y = 1000 - QrGenerator.CAPTION_HEIGHT; y < 1000; y++) {
                if ((image.getRGB(x, y) & 0xFFFFFF) < 0x808080) {
                    captionDrawn = true;
                    break;
                }
            }
        }
        assertThat(captionDrawn).as("подпись под QR-кодом").isTrue();
        assertThat(image.getRGB(0, 0) & 0xFFFFFF).as("белые поля").isEqualTo(0xFFFFFF);
    }
}
