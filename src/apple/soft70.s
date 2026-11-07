; Screen driver for a II+ without a VideoTerm: "Soft-70" text on Hi-Res
; page 1, 4-pixel cells, 70 columns of the 80-column buffer at a time.
; scr_hscroll switches between columns 0-69 and 10-79.
;
; Each text row is 8 Hi-Res lines; row R's top line is at
; $2000 + (R mod 8) * $80 + (R / 8) * $28, the same layout as the text
; page, and each line below is $400 further on. Column X's 4 pixels start
; at pixel 4X, which is bit (4X mod 7) of byte (4X / 7): a cell can
; straddle two bytes.

.export s70_init, s70_exit, s70_put, s70_clear_row, s70_scroll, s70_cursor, s70_hscroll, s70_clear_span
.import scr_row_lo, scr_row_hi
.import s70_font, s70_shift_lo, s70_shift_hi, s70_keep_lo, s70_keep_hi
.import s70_col_byte, s70_col_shift
.importzp zp_d, zp_e, zp_f, zp_u0, zp_u1, zp_u2

HIRES   = $2000
TXTCLR  = $C050
TXTSET  = $C051
MIXCLR  = $C052
LOWSCR  = $C054
LORES   = $C056
HIRES_ON = $C057
WINDOW  = 70
MAX_OFFSET = 80 - WINDOW

.bss
offset: .res 1              ; buffer column shown at the left edge
span_first: .res 1          ; s70_clear_span: first and last byte, and the
span_last:  .res 1          ; bits of each to keep
keep_first: .res 1
keep_last:  .res 1

.rodata
line_lo:
.repeat 24, r
        .byte <(HIRES + (r .mod 8) * $80 + (r / 8) * $28)
.endrepeat
line_hi:
.repeat 24, r
        .byte >(HIRES + (r .mod 8) * $80 + (r / 8) * $28)
.endrepeat

; the bits below bit 0-7
below:  .byte $00, $01, $03, $07, $0F, $1F, $3F, $7F

.code

s70_init:
        lda #0
        sta offset
        lda #<HIRES
        sta zp_d
        lda #>HIRES
        sta zp_d+1
        ldx #$20
        lda #0
        tay
:       sta (zp_d),y
        iny
        bne :-
        inc zp_d+1
        dex
        bne :-
        lda HIRES_ON
        lda LOWSCR
        lda MIXCLR
        lda TXTCLR
        rts

s70_exit:
        lda TXTSET
        lda LORES
        rts

; Points zp_e at the top Hi-Res line of the cell for row X, screen column
; Y - offset; returns C set if that column isn't in the window, else the
; cell's shift in zp_u1.
cell:
        tya
        sec
        sbc offset
        bcc @out
        cmp #WINDOW
        bcs @out
        tay
        clc
        lda line_lo,x
        adc s70_col_byte,y
        sta zp_e
        lda line_hi,x
        adc #0
        sta zp_e+1
        lda s70_col_shift,y
        sta zp_u1
        clc
        rts
@out:   sec
        rts

; Draws row X, column Y of the screen buffer.
s70_put:
        lda scr_row_lo,x
        sta zp_d
        lda scr_row_hi,x
        sta zp_d+1
        lda (zp_d),y
        sta zp_u0
        jsr cell
        bcs @done
        lda zp_u0           ; glyph address: font + (char - $20) * 8
        sec
        sbc #$20
        cmp #$7F-$20
        bcc :+
        lda #0              ; anything unprintable draws as a space
:       ldx #0
        stx zp_f+1
        asl
        rol zp_f+1
        asl
        rol zp_f+1
        asl
        rol zp_f+1
        adc #<s70_font
        sta zp_f
        lda zp_f+1
        adc #>s70_font
        sta zp_f+1
        lda zp_u1           ; shift * 16 indexes the shift tables
        asl
        asl
        asl
        asl
        sta zp_u2
        lda #0
        sta zp_u0           ; scan line 0-7
@line:  ldy zp_u0
        lda (zp_f),y
        ora zp_u2
        tax
        ldy #0
        lda (zp_e),y
        ldy zp_u1
        and s70_keep_lo,y
        ora s70_shift_lo,x
        ldy #0
        sta (zp_e),y
        lda zp_u1
        cmp #4
        bcc :+
        ldy #1
        lda (zp_e),y
        ldy zp_u1
        and s70_keep_hi,y
        ora s70_shift_hi,x
        ldy #1
        sta (zp_e),y
:       lda zp_e+1
        clc
        adc #4              ; next scan line: $400 on
        sta zp_e+1
        inc zp_u0
        lda zp_u0
        cmp #8
        bne @line
@done:  rts

; Blanks text row X across the whole Hi-Res width.
s70_clear_row:
        lda line_lo,x
        sta zp_e
        lda line_hi,x
        sta zp_e+1
        ldx #8
@line:  lda #0
        ldy #39
:       sta (zp_e),y
        dey
        bpl :-
        lda zp_e+1
        clc
        adc #4
        sta zp_e+1
        dex
        bne @line
        rts

; Blanks row X from column Y up to (not including) column A: the part in
; the window, as one run of pixels -- whole bytes in the middle, masked
; ones at the ends.
s70_clear_span:
        sta zp_u2           ; to
        sty zp_u1           ; from
        cpy offset          ; from = max(from, offset)
        bcs :+
        lda offset
        sta zp_u1
:       lda offset          ; to = min(to, offset + WINDOW)
        clc
        adc #WINDOW
        cmp zp_u2
        bcs :+
        sta zp_u2
:       lda zp_u1
        cmp zp_u2
        bcc :+
        rts
:       sec                 ; into window columns
        sbc offset
        tay
        lda s70_col_byte,y
        sta span_first
        lda s70_col_shift,y
        tay
        lda below,y         ; keep the bits before the first pixel
        ora #$80
        sta keep_first
        lda zp_u2
        sec
        sbc offset
        tay
        dey                 ; the last column's last pixel: 3 past its first
        lda s70_col_byte,y
        sta span_last
        lda s70_col_shift,y
        clc
        adc #3
        cmp #7
        bcc :+
        sbc #7
        inc span_last
:       tay
        lda below+1,y       ; keep the bits after the last pixel
        eor #$FF
        sta keep_last
        lda span_first
        cmp span_last
        bne :+
        lda keep_first      ; one byte: keep what's outside the run at either end
        ora keep_last
        sta keep_first
:       lda line_lo,x
        sta zp_e
        lda line_hi,x
        sta zp_e+1
        lda #8
        sta zp_u0
@line:  ldy span_first
        lda (zp_e),y
        and keep_first
        sta (zp_e),y
        cpy span_last
        beq @next
        lda #0
:       iny
        cpy span_last
        beq :+
        sta (zp_e),y
        bne :-
:       lda (zp_e),y
        and keep_last
        sta (zp_e),y
@next:  lda zp_e+1
        clc
        adc #4
        sta zp_e+1
        dec zp_u0
        bne @line
        rts

; The buffer has scrolled up a line: move the Hi-Res rows up to match.
; One pass over the 40 byte columns, each copying that column's byte for
; all 184 scan lines with fixed addresses: 9 cycles a byte, where a loop
; through pointers takes about 17. No address here crosses a page, so the
; indexed loads take no extra cycle.
s70_scroll:
        ldy #39
@column:
.repeat 23, r
  .repeat 8, k
        lda HIRES + ((r + 1) .mod 8) * $80 + ((r + 1) / 8) * $28 + k * $400, y
        sta HIRES + (r .mod 8) * $80 + (r / 8) * $28 + k * $400, y
  .endrepeat
.endrepeat
        dey
        bmi :+
        jmp @column
:       ldx #23
        jmp s70_clear_row

; Shows (A = 1) or hides (A = 0) the cursor at row X, column Y: an
; underline on the cell's bottom scan line, drawn by inverting it, so
; showing and hiding are the same operation.
s70_cursor:
        jsr cell
        bcs @done
        lda zp_e+1
        clc
        adc #7*4            ; scan line 7
        sta zp_e+1
        lda zp_u1
        asl
        asl
        asl
        asl
        ora #$0F            ; all four pixels
        tax
        ldy #0
        lda (zp_e),y
        eor s70_shift_lo,x
        sta (zp_e),y
        lda s70_shift_hi,x
        beq @done
        iny
        eor (zp_e),y
        sta (zp_e),y
@done:  rts

; A = 0: show columns 0-69; A = 1: show columns 10-79.
s70_hscroll:
        cmp #0
        beq :+
        lda #MAX_OFFSET
:       cmp offset
        beq @done
        sta offset
        jmp redraw
@done:  rts

redraw:
        ldx #0
@row:   stx zp_u2
        jsr s70_clear_row
        lda #0
@col:   sta zp_u0
        ldx zp_u2
        lda scr_row_lo,x
        sta zp_d
        lda scr_row_hi,x
        sta zp_d+1
        ldy zp_u0
        lda (zp_d),y
        cmp #' '
        beq :+
        jsr s70_put_keep
:       lda zp_u0
        clc
        adc #1
        cmp #80
        bne @col
        ldx zp_u2
        inx
        cpx #24
        bne @row
        rts

; s70_put for redraw, which keeps its loop state in zp_u0 and zp_u2.
s70_put_keep:
        lda zp_u0
        pha
        lda zp_u2
        pha
        ldx zp_u2
        ldy zp_u0
        jsr s70_put
        pla
        sta zp_u2
        pla
        sta zp_u0
        rts
