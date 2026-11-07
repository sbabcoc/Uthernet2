; Screen driver for the Videx VideoTerm (firmware 2.4 requires slot 3).
; It drives the card's hardware directly, as the VideoTerm manual's memory
; mapping section describes (ReplicApple2Plus HARDWARE-REFERENCE.md
; section 3): VRAM is 2K, seen 512 bytes at a time through $CC00-$CDFF in
; the card's $C800 space, with the page chosen by an access to $C0B0,
; $C0B4, $C0B8 or $C0BC; a character at column X, row Y of a screen that
; starts at VRAM address S is at (S + Y*80 + X) mod 2048. Scrolling moves
; S a line forward through CRTC R12/R13, the way the firmware scrolls.

.export vt_present, vt_init, vt_exit, vt_put, vt_clear_row, vt_scroll, vt_cursor, vt_hscroll, vt_clear_span
.import scr_row_lo, scr_row_hi
.importzp zp_d, zp_u0, zp_u1, zp_u2

VT_SLOT_ROM = $C300
VT_REG      = $C0B0         ; even offset: CRTC register select
VT_DATA     = $C0B1         ; odd offset: CRTC register data
VT_WINDOW   = $CC00
AN0_ON      = $C059         ; with the Soft Video Switch: 80 columns
AN0_OFF     = $C058
TEXT_ON     = $C051
FIRST_LINE  = $06FB         ; firmware scratch: screen start / 16 ($6F8 + slot)
CURSOR_H    = $057B         ; firmware scratch: cursor column ($578 + slot)
CURSOR_V    = $05FB         ; firmware scratch: cursor row ($5F8 + slot)

.bss
vt_start: .res 2            ; VRAM address of the top-left character

.rodata
; The CRTC values firmware 2.4's SETUP writes to R0-R15 (its table at $C8A1)
crtc_init:
        .byte $7B, $50, $5E, $29, $1B, $08, $18, $19
        .byte $00, $08, $E0, $08, $00, $00, $00, $00
; Pascal 1.1 protocol bytes at $C305/$C307/$C30B/$C30C in firmware 2.4
signature_offset: .byte $05, $07, $0B, $0C
signature_value:  .byte $38, $18, $01, $82
row80_lo:
.repeat 24, r
        .byte <(r*80)
.endrepeat
row80_hi:
.repeat 24, r
        .byte >(r*80)
.endrepeat

.code

; C set if slot 3 holds a VideoTerm with firmware 2.4.
vt_present:
        ldx #3
:       ldy signature_offset,x
        lda VT_SLOT_ROM,y
        cmp signature_value,x
        bne @no
        dex
        bpl :-
        sec
        rts
@no:    clc
        rts

vt_init:
        lda VT_SLOT_ROM     ; claim the $C800 space for slot 3
        ldx #0
:       stx VT_REG
        lda crtc_init,x
        sta VT_DATA
        inx
        cpx #16
        bne :-
        lda #0
        sta vt_start
        sta vt_start+1
        jsr clear_vram
        lda TEXT_ON
        lda AN0_ON
        rts

; Leaves the card as its firmware expects to find it: display at VRAM 0,
; cleared, cursor home; then back to the 40-column screen, which the
; firmware switches away from again with the next character it prints.
vt_exit:
        lda VT_SLOT_ROM
        ldx #12
        lda #0
        jsr set_register
        ldx #13
        lda #0
        jsr set_register
        ldx #14
        lda #0
        jsr set_register
        ldx #15
        lda #0
        jsr set_register
        ldx #10
        lda #$E0
        jsr set_register
        lda #0
        sta vt_start
        sta vt_start+1
        sta FIRST_LINE
        sta CURSOR_H
        sta CURSOR_V
        jsr clear_vram
        lda AN0_OFF
        rts

clear_vram:
        ldx #12
@page:  lda VT_REG,x        ; select page X/4
        lda #' '
        ldy #0
:       sta VT_WINDOW,y
        sta VT_WINDOW+$100,y
        iny
        bne :-
        dex
        dex
        dex
        dex
        bpl @page
        rts

; Writes A to CRTC register X.
set_register:
        stx VT_REG
        sta VT_DATA
        rts

; Points zp_d at row X, column Y's character in the VRAM window, with its
; page selected; leaves the full VRAM address in zp_u0 (low) and A (high).
locate:
        clc
        tya
        adc vt_start
        sta zp_u0
        lda vt_start+1
        adc #0
        sta zp_d+1
        clc
        lda zp_u0
        adc row80_lo,x
        sta zp_u0
        sta zp_d
        lda zp_d+1
        adc row80_hi,x
        and #$07            ; VRAM is 2K
        pha
        and #$06            ; page * 2
        asl                 ; page * 4
        tax
        lda VT_REG,x        ; select the page
        pla
        pha
        and #$01
        ora #>VT_WINDOW
        sta zp_d+1
        pla
        rts

; Shows row X, column Y of the screen buffer.
vt_put:
        lda scr_row_lo,x
        sta zp_d
        lda scr_row_hi,x
        sta zp_d+1
        lda (zp_d),y
        and #$7F            ; bit 7 would pick the alternate character set
        pha
        jsr locate
        lda VT_SLOT_ROM
        pla
        ldy #0
        sta (zp_d),y
        rts

vt_clear_row:
        ldy #0
        lda #80
        jmp fill

; Blanks row X from column Y up to (not including) column A.
vt_clear_span:
        sty zp_u1
        sec
        sbc zp_u1
        beq :+
        bcc :+
        jmp fill
:       rts

; Writes A (1-80) spaces from row X, column Y. The cells run on through
; VRAM, so the address is worked out once; the run is split only where it
; crosses into the next 512-byte page.
fill:   sta zp_u1           ; spaces left
        jsr locate
        lsr                 ; the page, from the address's high byte
        and #$03
        sta zp_u2
        lda VT_SLOT_ROM
@run:   lda zp_u1           ; this run: all that's left, or to the end of the page
        ldx zp_d+1
        cpx #>VT_WINDOW+1
        bne @go             ; in the page's first half: 256 or more to go
        ldx zp_d
        beq @go
        txa
        eor #$FF
        clc
        adc #1              ; 256 - offset within the second half
        cmp zp_u1
        bcc @go
        lda zp_u1
@go:    tax
        stx zp_u0
        lda #' '
        ldy #0
:       sta (zp_d),y
        iny
        dex
        bne :-
        lda zp_u1
        sec
        sbc zp_u0
        sta zp_u1
        beq @done
        lda zp_u2           ; on into the next page, wrapping at 2K
        clc
        adc #1
        and #$03
        sta zp_u2
        asl
        asl
        tax
        lda VT_REG,x
        lda #<VT_WINDOW
        sta zp_d
        lda #>VT_WINDOW
        sta zp_d+1
        jmp @run
@done:  rts

; The buffer has scrolled up a line: start the display a line later and
; blank the new bottom line.
vt_scroll:
        clc
        lda vt_start
        adc #80
        sta vt_start
        lda vt_start+1
        adc #0
        and #$07
        sta vt_start+1
        ldx #12
        jsr set_register
        ldx #13
        lda vt_start
        jsr set_register
        ldx #23
        jmp vt_clear_row

; Shows (A = 1) or hides (A = 0) the cursor at row X, column Y.
vt_cursor:
        cmp #0
        beq @hide
        jsr locate
        ldx #14
        jsr set_register
        ldx #15
        lda zp_u0
        jsr set_register
        ldx #10
        lda #$E0            ; firmware's block cursor, blinking at 1/32 field rate
        jmp set_register
@hide:  ldx #10
        lda #$20            ; R10 bits 6-5 = 01: no cursor
        jmp set_register

vt_hscroll:
        rts
