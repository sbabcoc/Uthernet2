package com.nordstrom.uthernet.apple;

/**
 * A test stand-in for the Videx VideoTerm, written from
 * ReplicApple2Plus's HARDWARE-REFERENCE.md section 3 (the real card model
 * needs Videx's ROM images, which aren't in source control): the CRTC's
 * register select and data at even and odd offsets of $C0B0-$C0BF, VRAM
 * page selection by bits 2-3 of any access there, the 2K VRAM seen through
 * $CC00-$CDFF while the card owns the $C800 space -- claimed by an access
 * to its $C300 page, released by an access to $CFFF -- and, in its $C300
 * page, the four Pascal 1.1 protocol bytes of firmware 2.4.
 */
final class VideoTermDouble {

    final int[] registers = new int[16];
    final int[] vram = new int[2048];
    private int selected;
    private int page;
    private boolean ownsC800;

    int readIo(int offset) {
        page = (offset >> 2) & 3;
        return (offset & 1) != 0 && (selected == 14 || selected == 15) ? registers[selected] : 0;
    }

    void writeIo(int offset, int value) {
        page = (offset >> 2) & 3;
        if ((offset & 1) == 0) {
            selected = value & 0x1F;
        } else if (selected < 16) {
            registers[selected] = value & 0xFF;
        }
    }

    int readSlotSpace(int address) {
        if (address >= 0xC300 && address < 0xC400) {
            ownsC800 = true;
            return switch (address & 0xFF) {
                case 0x05 -> 0x38;
                case 0x07 -> 0x18;
                case 0x0B -> 0x01;
                case 0x0C -> 0x82;
                default -> 0;
            };
        }
        if (address == 0xCFFF) {
            ownsC800 = false;
        }
        if (ownsC800 && address >= 0xCC00 && address < 0xCE00) {
            return vram[page * 512 + (address - 0xCC00)];
        }
        return 0;
    }

    void writeSlotSpace(int address, int value) {
        if (address >= 0xC300 && address < 0xC400) {
            ownsC800 = true;
        } else if (address == 0xCFFF) {
            ownsC800 = false;
        } else if (ownsC800 && address >= 0xCC00 && address < 0xCE00) {
            vram[page * 512 + (address - 0xCC00)] = value & 0xFF;
        }
    }

    /** @return the display start address, from R12/R13 */
    int start() {
        return ((registers[12] & 0x3F) << 8 | registers[13]) & 0x7FF;
    }

    /** @return the character shown at row, column */
    char at(int row, int col) {
        return (char) vram[(start() + row * 80 + col) & 0x7FF];
    }

    /** @return the cursor's VRAM address, from R14/R15 */
    int cursor() {
        return ((registers[14] & 0x3F) << 8 | registers[15]) & 0x7FF;
    }
}
