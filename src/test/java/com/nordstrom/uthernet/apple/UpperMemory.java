package com.nordstrom.uthernet.apple;

import com.nordstrom.emulator.system.SlotCard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Stands in for the operating system's use of the slot-0 card: fills the
 * 16K that ProDOS or DOS 3.3 owns -- the Language Card's RAM, or the
 * Saturn's bank 1, which works the same way -- with a pattern, and checks
 * afterwards that it's untouched and that ROM reads are back on. The
 * card's switches are driven as software drives them: $C083 or $C08B
 * twice to read and write RAM (4K bank 2/A or 1/B at $D000), $C082 for
 * ROM reads.
 */
final class UpperMemory {

    private UpperMemory() {
    }

    static void fill(SlotCard card) {
        pattern(card, true);
    }

    static void assertUntouched(SlotCard card) {
        assertFalse(card.readUpperMemory(0).isPresent(), "ROM reads back on at exit");
        pattern(card, false);
    }

    private static void pattern(SlotCard card, boolean write) {
        for (int bank : new int[] {0x3, 0xB}) {        // $D000 bank 2/A, then 1/B; $E000-$FFFF with the first
            card.readIoSwitch(bank);
            card.readIoSwitch(bank);
            int end = bank == 0x3 ? 0x3000 : 0x1000;
            for (int offset = 0; offset < end; offset++) {
                int value = (offset * 7 + bank) & 0xFF;
                if (write) {
                    card.writeUpperMemory(offset, value);
                } else {
                    assertEquals(value, card.readUpperMemory(offset).orElse(-1),
                        "the operating system's RAM at offset $" + Integer.toHexString(offset));
                }
            }
        }
        card.readIoSwitch(0x2);
    }
}
