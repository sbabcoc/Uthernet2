; Screen driver for a II+ without a VideoTerm: "Soft-70" text on Hi-Res
; page 1, 4-pixel cells, 70 columns of the 80-column buffer at a time.
; scr_hscroll switches between columns 0-69 and 10-79. The drawing follows
; a2lib's Soft-70 (github.com/sbabcoc/a2lib, DESIGN.md 3.8 and 3.9): its
; font, per-row extents for scrolling, the inverse cursor, the position
; gauge and the text marker.
;
; Each text row is 8 Hi-Res lines; row R's top line is at
; $2000 + (R mod 8) * $80 + (R / 8) * $28, the same layout as the text
; page, and each line below is $400 further on. Column X's 4 pixels start
; at pixel 4X, which is bit (4X mod 7) of byte (4X / 7): a cell can
; straddle two bytes. Every byte written has bit 7 clear.
;
; Extents: sext[R] is one more than the rightmost Hi-Res byte drawn in text
; row R since the row was last cleared, so the row's bytes from there on
; are blank. Scrolling and clearing touch only the bytes below the extent,
; and a blank written beyond it isn't drawn at all.
;
; Gauge: after a pan, a bar on the bottom scan line of row 23, dotted
; across the whole width with a solid thumb over the 70 columns shown,
; drawn and removed with EOR. Whatever draws on row 23 while it is shown
; takes it off first and puts it back after, so the cells underneath are
; kept as they are.
;
; Text marker: while Soft-70 runs, the 16 bytes before the buffer hold
; a2lib's marker (DESIGN.md 3.9), so an emulator's Copy can find the text,
; which never reaches the text page: signature "A2SCR80", version 1,
; 80 x 24, 70 columns shown, encoding 2 (the buffer's bytes are plain
; ASCII, $20 a blank), the address of the leftmost column shown, and of
; the buffer. s70_exit clears its first byte.

.macpack longbranch

.export s70_init, s70_exit, s70_put, s70_clear_row, s70_scroll, s70_cursor, s70_hscroll, s70_clear_span
.export s70_gauge_on, s70_gauge_off
.import scr_row_lo, scr_row_hi, scr_marker, scr_buf
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
ROWS    = 24
BARLINE = HIRES + $03D0 + 7 * $400  ; row 23, scan line 7
ENCODING = 2                ; the marker's: plain ASCII

.segment "LOWBSS"
sext:   .res ROWS           ; each text row's extent (see above)
barpat: .res 40             ; the gauge's bar, byte by byte

.bss
offset: .res 1              ; buffer column shown at the left edge
inv:    .res 1              ; $0F to draw a cell inverted, else 0
cbyte:  .res 1              ; the cell's first Hi-Res byte
bar_on: .res 1              ; nonzero while the gauge is shown
span_first: .res 1          ; s70_clear_span: first and last byte, and the
span_last:  .res 1          ; bits of each to keep
keep_first: .res 1
keep_last:  .res 1

.rodata
line_lo:
.repeat ROWS, r
        .byte <(HIRES + (r .mod 8) * $80 + (r / 8) * $28)
.endrepeat
line_hi:
.repeat ROWS, r
        .byte >(HIRES + (r .mod 8) * $80 + (r / 8) * $28)
.endrepeat

; the bits below bit 0-7
below:  .byte $00, $01, $03, $07, $0F, $1F, $3F, $7F

; the gauge's track: even pixels lit, in even and odd bytes
track:  .byte $55, $2A

marker: .byte "A2SCR80"     ; signature, bit 7 clear
        .byte 1             ; version
        .byte 80, ROWS, WINDOW
        .byte ENCODING
        .addr offset        ; leftmost column shown (a byte)
        .addr scr_buf       ; the buffer: rows of 80 bytes, directly after
MARKER_SIZE = * - marker
        .assert MARKER_SIZE = 16, error, "the text marker is 16 bytes"

.code

s70_init:
        lda #0
        sta offset
        sta bar_on
        ldx #ROWS-1
:       sta sext,x
        dex
        bpl :-
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
        ldx #MARKER_SIZE-1
:       lda marker,x
        sta scr_marker,x
        dex
        bpl :-
        lda HIRES_ON
        lda LOWSCR
        lda MIXCLR
        lda TXTCLR
        rts

s70_exit:
        lda #0              ; no marker once the text is gone
        sta scr_marker
        lda TXTSET
        lda LORES
        rts

; Points zp_e at the top Hi-Res line of the cell for row X, screen column
; Y - offset; returns C set if that column isn't in the window, else the
; cell's shift in zp_u1 and its byte in cbyte. Keeps X.
cell:
        tya
        sec
        sbc offset
        bcc @out
        cmp #WINDOW
        bcs @out
        tay
        lda s70_col_byte,y
        sta cbyte
        clc
        adc line_lo,x
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

; Shows (A = 1) or hides (A = 0) the cursor at row X, column Y: the cell
; drawn inverted, or as it is.
s70_cursor:
        cmp #0
        beq s70_put
        lda #$0F
        sta inv
        bne put_cell        ; always

; Draws row X, column Y of the screen buffer.
s70_put:
        lda #0
        sta inv
put_cell:
        lda scr_row_lo,x
        sta zp_d
        lda scr_row_hi,x
        sta zp_d+1
        lda (zp_d),y
        sta zp_u0
        jsr cell
        bcs @done
        ldy cbyte           ; the byte after the cell
        iny
        lda zp_u1
        cmp #4
        bcc :+
        iny
:       lda inv             ; a blank beyond the extent is there already
        bne @draw
        lda zp_u0
        cmp #' '
        bne @draw
        lda cbyte
        cmp sext,x
        bcs @done
@draw:  tya
        cmp sext,x
        bcc :+
        sta sext,x
:       cpx #ROWS-1
        bne draw
        lda bar_on
        beq draw
        jsr barxor
        jsr draw
        jmp barxor
@done:  rts

; Draws the character in zp_u0 into the cell at zp_e, shift zp_u1,
; inverted by inv.
draw:   lda zp_u0           ; glyph address: font + (char - $20) * 8
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
        eor inv
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
        rts

; Blanks text row X: the bytes drawn since it was last cleared.
s70_clear_row:
        lda sext,x
        beq @done
        cpx #ROWS-1
        bne clear_row
        lda bar_on
        beq clear_row
        jsr barxor
        jsr clear_row
        jmp barxor
@done:  rts

; Blanks the bytes of row X below its extent, which becomes 0.
clear_row:
        ldy sext,x
        beq @done
        dey
        sty zp_u0           ; the last byte
        lda #0
        sta sext,x
        lda line_lo,x
        sta zp_e
        lda line_hi,x
        sta zp_e+1
        ldx #8
@line:  lda #0
        ldy zp_u0
:       sta (zp_e),y
        dey
        bpl :-
        lda zp_e+1
        clc
        adc #4
        sta zp_e+1
        dex
        bne @line
@done:  rts

; Blanks row X from column Y up to (not including) column A: the part in
; the window, as one run of pixels -- whole bytes in the middle, masked
; ones at the ends.
s70_clear_span:
        sta zp_u2           ; to
        sty zp_u1           ; from
        cpx #ROWS-1
        bne span
        lda bar_on
        beq span
        jsr barxor
        jsr span
        jmp barxor
span:   ldy zp_u1
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
        cmp sext,x          ; nothing drawn from here on
        bcc :+
        rts
:       sta span_first
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
; Each row copies the bytes below the larger of its own extent and the
; extent of the row moving into it -- 8 scan lines a byte, through
; addresses set for the row -- and takes that row's extent. No address
; here crosses a page, so the indexed loads take no extra cycle.
s70_scroll:
        lda bar_on          ; the gauge comes off row 23 first
        beq :+
        jsr barxor
:       ldx #0
srow:   lda sext,x
        cmp sext+1,x
        bcs :+
        lda sext+1,x
:       tay
        jeq snext           ; both blank
        stx zp_u0
        lda line_lo+1,x     ; loads from row X + 1, stores to row X
.repeat 8, k
        sta .ident(.sprintf("src%d", k))+1
.endrepeat
        lda line_lo,x
.repeat 8, k
        sta .ident(.sprintf("dst%d", k))+1
.endrepeat
        lda line_hi+1,x
.repeat 8, k
        sta .ident(.sprintf("src%d", k))+2
  .if k < 7
        clc
        adc #4
  .endif
.endrepeat
        lda line_hi,x
.repeat 8, k
        sta .ident(.sprintf("dst%d", k))+2
  .if k < 7
        clc
        adc #4
  .endif
.endrepeat
        dey
scopy:
.repeat 8, k
.ident(.sprintf("src%d", k)):
        lda HIRES,y
.ident(.sprintf("dst%d", k)):
        sta HIRES,y
.endrepeat
        dey
        bpl scopy
        ldx zp_u0
snext:  lda sext+1,x
        sta sext,x
        inx
        cpx #ROWS-1
        jne srow
        jsr clear_row       ; row 23
        lda bar_on
        beq :+
        jmp barxor
:       rts

; A = 0: show columns 0-69; A = 1: show columns 10-79. Takes the gauge
; down; scr_hscroll puts it up.
s70_hscroll:
        cmp #0
        beq :+
        lda #MAX_OFFSET
:       cmp offset
        beq @done
        sta offset
        jsr s70_gauge_off
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

; ---------------------------------------------------------------------
; the position gauge

; Shows the gauge for the columns now in the window: the thumb covers 35
; of the 40 bytes (70 of 80 columns), from byte offset / 2.
s70_gauge_on:
        jsr s70_gauge_off
        lda offset
        lsr
        sta zp_u0           ; the thumb's first byte
        clc
        adc #35
        sta zp_u1           ; and the byte after its last
        ldy #39
@byte:  cpy zp_u0
        bcc @track
        cpy zp_u1
        bcs @track
        lda #$7F
        bne @put            ; always
@track: tya
        and #1
        tax
        lda track,x
@put:   sta barpat,y
        dey
        bpl @byte
        jsr barxor
        lda #1
        sta bar_on
        rts

; Takes the gauge down, if it's shown.
s70_gauge_off:
        lda bar_on
        beq :+
        jsr barxor
        lda #0
        sta bar_on
:       rts

; EORs the bar onto row 23's bottom scan line. Keeps X.
barxor: ldy #39
:       lda BARLINE,y
        eor barpat,y
        sta BARLINE,y
        dey
        bpl :-
        rts
