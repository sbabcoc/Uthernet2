; One line of keyboard input, echoed on the screen.

.export line_input
.import kbd_get, scr_putc
.importzp zp_c, zp_t3

.bss
line_max: .res 1

.code

; Reads a line into the buffer at AX, at most Y characters, and ends it
; with a 0. RETURN accepts it (C clear); ESC abandons it (C set). The
; left arrow erases.
line_input:
        sta zp_c
        stx zp_c+1
        sty line_max
        lda #0
        sta zp_t3           ; characters so far
@key:   jsr kbd_get
        cmp #$0D
        beq @accept
        cmp #$1B
        beq @cancel
        cmp #$08
        beq @erase
        cmp #$20
        bcc @key
        ldy zp_t3
        cpy line_max
        bcs @key
        sta (zp_c),y
        inc zp_t3
        jsr scr_putc
        jmp @key
@erase: ldy zp_t3
        beq @key
        dec zp_t3
        lda #$08
        jsr scr_putc
        lda #' '
        jsr scr_putc
        lda #$08
        jsr scr_putc
        jmp @key
@accept:
        ldy zp_t3
        lda #0
        sta (zp_c),y
        clc
        rts
@cancel:
        sec
        rts
