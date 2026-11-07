; Keyboard input, with lowercase from the shift-key mod: a wire from the
; Shift key to pushbutton 2 ($C063), which reads bit 7 clear while Shift is
; held. The II+ keyboard itself only sends uppercase, so a letter typed
; without Shift becomes lowercase. On a II+ without the mod, an idle
; pushbutton 2 also reads bit 7 clear, so letters stay uppercase.

.export kbd_avail, kbd_get

KBD     = $C000
KBDSTRB = $C010
PB2     = $C063

.code

; C set if a key is waiting.
kbd_avail:
        lda KBD
        asl
        rts

; Waits for a key; returns it in A, bit 7 clear.
kbd_get:
        lda KBD
        bpl kbd_get
        sta KBDSTRB
        and #$7F
        cmp #'A'
        bcc @done
        cmp #'Z'+1
        bcs @done
        bit PB2
        bpl @done           ; Shift held (or no mod): leave it uppercase
        ora #$20
@done:  rts
