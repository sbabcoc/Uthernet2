package com.nordstrom.uthernet.apple;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * What Soft-70 should show, worked out from the screen buffer and the font
 * source (src/apple/s70font.txt) rather than from the program's own tables,
 * and compared with the Hi-Res page pixel by pixel.
 */
final class Soft70View {

    /** Row 23's bottom scan line, where the position gauge goes. */
    static final int GAUGE_ROW = 23;
    static final int GAUGE_LINE = 7;
    /** The text marker's fields, after the signature and version. */
    static final int MARKER_COLUMNS = 8, MARKER_ROWS = 9, MARKER_VISIBLE = 10, MARKER_ENCODING = 11,
        MARKER_LEFT = 12, MARKER_BUFFER = 14;

    private static int[][] font;

    private Soft70View() {}

    /** @return the glyph rows of {@code c}, bit 0 the leftmost pixel; anything unprintable is a space */
    static int[] glyph(char c) throws IOException {
        if (font == null) {
            font = load(Path.of("src/apple/s70font.txt"));
        }
        return font[c >= 0x20 && c < 0x7F ? c - 0x20 : 0];
    }

    private static int[][] load(Path path) throws IOException {
        List<String> lines = new ArrayList<>();
        for (String line : Files.readAllLines(path)) {
            if (!line.isEmpty() && !line.startsWith(";")) {
                lines.add(line);
            }
        }
        int[][] glyphs = new int[lines.size() / 9][8];
        for (int g = 0; g < glyphs.length; g++) {
            for (int r = 0; r < 8; r++) {
                String row = lines.get(g * 9 + 1 + r);
                for (int p = 0; p < 3; p++) {
                    if (row.charAt(p) == '#') {
                        glyphs[g][r] |= 2 << p;      // pixels 1-3; pixel 0 is the gap
                    }
                }
            }
        }
        return glyphs;
    }

    /** @return the 4 pixels of window column {@code x} on scan line {@code line} of text row {@code row} */
    static int cellPixels(AppleMachine apple, int row, int line, int x) {
        int bit = (4 * x) % 7;
        int index = (4 * x) / 7;
        int pair = apple.hiresByte(row, line, index) & 0x7F;
        if (index + 1 < 40) {
            pair |= (apple.hiresByte(row, line, index + 1) & 0x7F) << 7;
        }
        return (pair >> bit) & 0x0F;
    }

    /** @return true if the cell at window column {@code x} of {@code row} shows {@code c} inverted */
    static boolean inverted(AppleMachine apple, int row, int x, char c) throws IOException {
        int[] g = glyph(c);
        for (int line = 0; line < 8; line++) {
            if (cellPixels(apple, row, line, x) != (g[line] ^ 0x0F)) {
                return false;
            }
        }
        return true;
    }

    /** @return the bar the gauge EORs onto row 23's bottom scan line, for the window at {@code left} */
    static int[] gaugeBar(int left) {
        int[] bar = new int[40];
        for (int b = 0; b < 40; b++) {
            bar[b] = b >= left / 2 && b < left / 2 + 35 ? 0x7F : (b % 2 == 0 ? 0x55 : 0x2A);
        }
        return bar;
    }

    /**
     * @return Hi-Res byte {@code index} of scan line {@code line} of text row
     * {@code row} as the buffer's glyphs draw it, with the window at {@code left}
     */
    static int drawnByte(AppleMachine apple, int left, int row, int line, int index) throws IOException {
        String text = apple.row(row);
        int value = 0;
        for (int bit = 0; bit < 7; bit++) {
            int pixel = index * 7 + bit;
            int x = pixel / 4;
            if (x < 70 && (glyph(text.charAt(left + x))[line] >> (pixel % 4) & 1) != 0) {
                value |= 1 << bit;
            }
        }
        return value;
    }

    /**
     * Checks that the Hi-Res page shows exactly the buffer's columns
     * {@code left} to {@code left + 69}: every cell its glyph, except that the
     * cell at the cursor may also be inverted (it blinks), and that every byte
     * has bit 7 clear. A {@code cursorRow} of -1 lets the cursor be anywhere:
     * any one cell may be inverted.
     *
     * @return null if it does, else the first difference
     */
    static String mismatch(AppleMachine apple, int left, int cursorRow, int cursorCol) throws IOException {
        boolean anyInverted = false;
        for (int row = 0; row < 24; row++) {
            String text = apple.row(row);
            for (int x = 0; x < 70; x++) {
                char c = text.charAt(left + x);
                int[] g = glyph(c);
                boolean cursor = cursorRow < 0 ? !anyInverted : row == cursorRow && left + x == cursorCol;
                boolean plain = true;
                for (int line = 0; line < 8; line++) {
                    plain &= cellPixels(apple, row, line, x) == g[line];
                }
                if (!plain && !(cursor && inverted(apple, row, x, c))) {
                    return "row " + row + ", column " + (left + x) + " ('" + c + "') isn't drawn as it should be";
                }
                anyInverted |= !plain;
            }
            for (int line = 0; line < 8; line++) {
                for (int index = 0; index < 40; index++) {
                    if ((apple.hiresByte(row, line, index) & 0x80) != 0) {
                        return "bit 7 set at row " + row + ", scan line " + line + ", byte " + index;
                    }
                }
            }
        }
        return null;
    }
}
