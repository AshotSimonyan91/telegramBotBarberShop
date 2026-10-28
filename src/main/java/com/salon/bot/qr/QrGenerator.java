package com.salon.bot.qr;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import io.github.cdimascio.dotenv.Dotenv;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Генерирует PNG 1000x1000 с QR-кодом deep link на бота и подписью снизу.
 * <pre>
 * mvn -q compile exec:java -Dexec.args="--source=salon_1 --out=qr_salon_1.png"
 * </pre>
 */
public class QrGenerator {

    static final int SIZE = 1000;
    static final int CAPTION_HEIGHT = 130;
    static final String CAPTION = "Отсканируйте, чтобы записаться";
    private static final String DEFAULT_USERNAME = "AshotSimonyanBot";

    public static void main(String[] args) throws WriterException, IOException {
        System.setProperty("java.awt.headless", "true");
        String source = "salon_1";
        String out = null;
        for (String arg : args) {
            if (arg.startsWith("--source=")) {
                source = arg.substring("--source=".length());
            } else if (arg.startsWith("--out=")) {
                out = arg.substring("--out=".length());
            } else {
                System.err.println("Неизвестный аргумент: " + arg);
                System.err.println("Использование: --source=salon_1 --out=qr_salon_1.png");
                System.exit(1);
            }
        }
        if (!source.matches("[A-Za-z0-9_\\-]{1,64}")) {
            System.err.println("source может содержать только латиницу, цифры, _ и - (до 64 символов)");
            System.exit(1);
        }
        Path output = Path.of(out != null ? out : "qr_" + source + ".png");
        String url = link(botUsername(), source);

        write(url, output);
        System.out.println("QR-код для " + url + " сохранён в " + output.toAbsolutePath());
    }

    static String link(String username, String source) {
        return "https://t.me/" + username + "?start=" + source;
    }

    static String botUsername() {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        String username = dotenv.get("BOT_USERNAME", "");
        return username.isBlank() ? DEFAULT_USERNAME : username.replace("@", "").trim();
    }

    static void write(String url, Path output) throws WriterException, IOException {
        int qrSize = SIZE - CAPTION_HEIGHT;
        BitMatrix matrix = new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, qrSize, qrSize, Map.of(
                EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H,
                EncodeHintType.MARGIN, 4,
                EncodeHintType.CHARACTER_SET, "UTF-8"));
        BufferedImage qr = MatrixToImageWriter.toBufferedImage(matrix);

        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, SIZE, SIZE);
            g.drawImage(qr, (SIZE - qrSize) / 2, 0, null);

            g.setColor(Color.BLACK);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 48));
            FontMetrics metrics = g.getFontMetrics();
            int x = (SIZE - metrics.stringWidth(CAPTION)) / 2;
            int y = qrSize + (CAPTION_HEIGHT - metrics.getHeight()) / 2 + metrics.getAscent() - 15;
            g.drawString(CAPTION, x, y);
        } finally {
            g.dispose();
        }
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        ImageIO.write(image, "PNG", output.toFile());
    }
}
