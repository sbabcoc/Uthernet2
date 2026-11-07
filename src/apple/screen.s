; The 80x24 screen both utilities draw on. The text lives in an 80x24
; buffer in main RAM; a driver shows it -- the VideoTerm when slot 3 holds
; one, otherwise Soft-70 on the Hi-Res screen, which shows 70 of the 80
; columns at a time and scrolls sideways to reach the rest.
;
; Printing follows a VT100's rules: writing the last column leaves the
; cursor there with a wrap pending, and the next printable character
; starts a new line.

.export scr_init, scr_exit, scr_putc, scr_puts, scr_crlf, scr_goto
.export scr_clear, scr_clear_eol, scr_clear_bol, scr_clear_line
.export scr_clear_eos, scr_clear_bos, scr_hscroll, scr_reverse_index
.export scr_row, scr_col, scr_buf, scr_row_lo, scr_row_hi, scr_driver
.export scr_put_dec, scr_put_ip, scr_batch_begin, scr_batch_end
.export scr_redraw, scroll_hook, scr_put_num4

.import vt_present, vt_init, vt_exit, vt_put, vt_clear_row, vt_scroll, vt_cursor, vt_hscroll, vt_clear_span
.import s70_init, s70_exit, s70_put, s70_clear_row, s70_scroll, s70_cursor, s70_hscroll, s70_clear_span
.import beep, cp437_map
.importzp zp_sa, zp_sb, zp_sc, zp_s0, zp_s1, zp_s2
.importzp drv_init_vec, drv_exit_vec, drv_put_vec, drv_clear_row_vec
.importzp drv_scroll_vec, drv_cursor_vec, drv_hscroll_vec, drv_clear_span_vec

COLS = 80
ROWS = 24

.segment "SCREEN"
scr_buf: .res COLS*ROWS

.bss
scr_row:     .res 1
scr_col:     .res 1
scr_driver:  .res 1         ; 0 = VideoTerm, 1 = Soft-70
wrap_pending: .res 1
cursor_drawn: .res 1
batch:       .res 1         ; nonzero between scr_batch_begin and scr_batch_end
drawn_row:   .res 1
drawn_col:   .res 1

.data
; Called with the top line still in row 0, just before it scrolls off.
; An RTS until a program patches in a JMP (TELNET does, for scrollback).
scroll_hook:
        rts
        .word 0

.rodata
scr_row_lo:
.repeat ROWS, r
        .byte <(scr_buf + r*COLS)
.endrepeat
scr_row_hi:
.repeat ROWS, r
        .byte >(scr_buf + r*COLS)
.endrepeat

; Each driver's entry points, in the order of the vectors in zp.s
drivers:
        .word vt_init, vt_exit, vt_put, vt_clear_row, vt_scroll, vt_cursor, vt_hscroll, vt_clear_span
        .word s70_init, s70_exit, s70_put, s70_clear_row, s70_scroll, s70_cursor, s70_hscroll, s70_clear_span
DRIVER_SIZE = 16

.code

; Chooses the driver, clears the screen and homes the cursor.
scr_init:
        jsr vt_present
        ldx #0
        bcs :+
        ldx #DRIVER_SIZE
:       txa
        beq :+
        lda #1
:       sta scr_driver
        ldy #0
:       lda drivers,x
        sta drv_init_vec,y
        inx
        iny
        cpy #DRIVER_SIZE
        bne :-
        jsr call_init
        lda #0
        sta cursor_drawn
        sta batch
        jmp scr_clear

call_init:  jmp (drv_init_vec)
call_put:   jmp (drv_put_vec)
call_clear_row: jmp (drv_clear_row_vec)
call_scroll: jmp (drv_scroll_vec)
call_cursor: jmp (drv_cursor_vec)
call_clear_span: jmp (drv_clear_span_vec)

scr_exit:
        jmp (drv_exit_vec)

; Shifts the Soft-70 window: A = 0 for the left columns, 1 for the right.
scr_hscroll:
        pha
        jsr cursor_hide
        pla
        jsr :+
        jmp cursor_show
:       jmp (drv_hscroll_vec)

; ---------------------------------------------------------------------
; cursor

; Starts a burst of output: the cursor comes off once, and stays off until
; scr_batch_end puts it back, instead of being redrawn after every
; character.
scr_batch_begin:
        jsr cursor_hide
        lda #1
        sta batch
        rts

scr_batch_end:
        lda #0
        sta batch
        jmp cursor_show

cursor_hide:
        lda cursor_drawn
        beq :+
        ldx drawn_row
        ldy drawn_col
        lda #0
        sta cursor_drawn
        jmp call_cursor
:       rts

cursor_show:
        lda batch
        bne :+
        ldx scr_row
        ldy scr_col
        stx drawn_row
        sty drawn_col
        lda #1
        sta cursor_drawn
        jmp call_cursor
:       rts

; ---------------------------------------------------------------------
; output

; Prints A: printable ASCII, CP437 above $7F (as ASCII), or a control
; character (CR, LF, BS, TAB, BEL; others are ignored).
scr_putc:
        cmp #$20
        bcc @control
        cmp #$7F
        bcc @print
        beq @ignore
        tax
        lda cp437_map-$80,x
@print: pha
        jsr cursor_hide
        lda wrap_pending
        beq :+
        lda #0
        sta wrap_pending
        sta scr_col
        jsr line_feed
:       ldx scr_row
        lda scr_row_lo,x
        sta zp_sa
        lda scr_row_hi,x
        sta zp_sa+1
        ldy scr_col
        pla
        sta (zp_sa),y
        jsr call_put
        lda scr_col
        cmp #COLS-1
        bcc :+
        lda #1
        sta wrap_pending
        bne @show
:       inc scr_col
@show:  jmp cursor_show
@ignore:
        rts
@control:
        cmp #$0D
        beq @cr
        cmp #$0A
        beq @lf
        cmp #$08
        beq @bs
        cmp #$09
        beq @tab
        cmp #$07
        bne @ignore
        jmp beep
@cr:    jsr cursor_hide
        lda #0
        sta scr_col
        sta wrap_pending
        jmp cursor_show
@lf:    jsr cursor_hide
        jsr line_feed
        jmp cursor_show
@bs:    jsr cursor_hide
        lda #0
        sta wrap_pending
        lda scr_col
        beq :+
        dec scr_col
:       jmp cursor_show
@tab:   jsr cursor_hide
        lda scr_col
        ora #7
        cmp #COLS-1
        bcs :+
        adc #1
:       sta scr_col
        lda #0
        sta wrap_pending
        jmp cursor_show

; Moves down a line, scrolling at the bottom. Cursor already hidden.
line_feed:
        lda scr_row
        cmp #ROWS-1
        bcs scroll_up
        inc scr_row
        rts

scroll_up:
        jsr scroll_hook
        lda #<scr_buf
        sta zp_sa
        lda #>scr_buf
        sta zp_sa+1
        lda #<(scr_buf+COLS)
        sta zp_sb
        lda #>(scr_buf+COLS)
        sta zp_sb+1
        ldx #ROWS-1
@row:   ldy #COLS-1
:       lda (zp_sb),y
        sta (zp_sa),y
        dey
        bpl :-
        lda zp_sb
        sta zp_sa
        lda zp_sb+1
        sta zp_sa+1
        clc
        lda zp_sb
        adc #COLS
        sta zp_sb
        bcc :+
        inc zp_sb+1
:       dex
        bne @row
        ldx #ROWS-1
        jsr blank_buffer_row
        jmp call_scroll

; Up a line, scrolling down at the top (ESC M). The buffer scrolls; the
; driver redraws, which is slow but rare.
scr_reverse_index:
        jsr cursor_hide
        lda scr_row
        beq :+
        dec scr_row
        jmp cursor_show
:       ldx #ROWS-1
@row:   lda scr_row_lo,x
        sta zp_sa
        lda scr_row_hi,x
        sta zp_sa+1
        lda scr_row_lo-1,x
        sta zp_sb
        lda scr_row_hi-1,x
        sta zp_sb+1
        ldy #COLS-1
:       lda (zp_sb),y
        sta (zp_sa),y
        dey
        bpl :-
        dex
        bne @row
        ldx #0
        jsr blank_buffer_row
        jsr redraw_all
        jmp cursor_show

; Draws the whole buffer again, cursor and all.
scr_redraw:
        jsr cursor_hide
        jsr redraw_all
        jmp cursor_show

redraw_all:
        ldx #0
@row:   stx zp_s0
        jsr call_clear_row
        ldy #0
@col:   sty zp_s1
        ldx zp_s0
        lda scr_row_lo,x
        sta zp_sa
        lda scr_row_hi,x
        sta zp_sa+1
        lda (zp_sa),y
        cmp #' '
        beq :+
        jsr call_put
:       ldy zp_s1
        iny
        cpy #COLS
        bne @col
        ldx zp_s0
        inx
        cpx #ROWS
        bne @row
        rts

; Fills buffer row X with spaces.
blank_buffer_row:
        lda scr_row_lo,x
        sta zp_sa
        lda scr_row_hi,x
        sta zp_sa+1
        lda #' '
        ldy #COLS-1
:       sta (zp_sa),y
        dey
        bpl :-
        rts

; Prints the null-terminated string at AX (low byte in A). Its pointer is
; zp_sc, which scrolling leaves alone.
scr_puts:
        sta zp_sc
        stx zp_sc+1
        ldy #0
@next:  lda (zp_sc),y
        beq @done
        sty zp_s0
        jsr scr_putc
        ldy zp_s0
        iny
        bne @next
@done:  rts

scr_crlf:
        lda #$0D
        jsr scr_putc
        lda #$0A
        jmp scr_putc

; Moves the cursor to row X, column Y, clamped to the screen.
scr_goto:
        txa
        pha
        tya
        pha
        jsr cursor_hide
        pla
        cmp #COLS
        bcc :+
        lda #COLS-1
:       sta scr_col
        pla
        cmp #ROWS
        bcc :+
        lda #ROWS-1
:       sta scr_row
        lda #0
        sta wrap_pending
        jmp cursor_show

; ---------------------------------------------------------------------
; erasing

scr_clear:
        jsr cursor_hide
        ldx #0
:       stx zp_s0
        jsr blank_buffer_row
        ldx zp_s0
        jsr call_clear_row
        ldx zp_s0
        inx
        cpx #ROWS
        bne :-
        lda #0
        sta scr_row
        sta scr_col
        sta wrap_pending
        jmp cursor_show

; Erases from the cursor to the end of its line.
scr_clear_eol:
        jsr cursor_hide
        ldy scr_col
        lda #COLS
        jsr erase_span
        jmp cursor_show

; Erases from the start of the cursor's line through the cursor.
scr_clear_bol:
        jsr cursor_hide
        ldy #0
        ldx scr_col
        inx
        txa
        jsr erase_span
        jmp cursor_show

scr_clear_line:
        jsr cursor_hide
        ldx scr_row
        jsr blank_buffer_row
        ldx scr_row
        jsr call_clear_row
        jmp cursor_show

; Erases from the cursor to the end of the screen.
scr_clear_eos:
        jsr cursor_hide
        ldy scr_col
        lda #COLS
        jsr erase_span
        ldx scr_row
@row:   inx
        cpx #ROWS
        bcs @done
        stx zp_s0
        jsr blank_buffer_row
        ldx zp_s0
        jsr call_clear_row
        ldx zp_s0
        jmp @row
@done:  jmp cursor_show

; Erases from the top of the screen through the cursor.
scr_clear_bos:
        jsr cursor_hide
        ldx #0
@row:   cpx scr_row
        bcs @line
        stx zp_s0
        jsr blank_buffer_row
        ldx zp_s0
        jsr call_clear_row
        ldx zp_s0
        inx
        jmp @row
@line:  ldy #0
        ldx scr_col
        inx
        txa
        jsr erase_span
        jmp cursor_show

; Blanks columns Y up to (not including) A on the cursor's row: in the
; buffer, then in one call to the driver.
erase_span:
        sta zp_s1
        sty zp_s0
        cpy zp_s1
        bcs @done
        ldx scr_row
        lda scr_row_lo,x
        sta zp_sa
        lda scr_row_hi,x
        sta zp_sa+1
        lda #' '
:       sta (zp_sa),y
        iny
        cpy zp_s1
        bcc :-
        ldx scr_row
        ldy zp_s0
        lda zp_s1
        jmp call_clear_span
@done:  rts

; ---------------------------------------------------------------------
; numbers

; Prints A as an unsigned decimal number.
scr_put_dec:
        ldx #0
        stx zp_s1           ; nonzero once a digit has been printed
        ldx #2
@digit: ldy #0
:       cmp powers,x
        bcc :+
        sbc powers,x
        iny
        bne :-
:       pha
        tya
        bne @show
        lda zp_s1
        bne @show
        cpx #0
        bne @skip
@show:  tya
        ora #'0'
        stx zp_s0
        jsr scr_putc
        ldx zp_s0
        lda #1
        sta zp_s1
@skip:  pla
        dex
        bpl @digit
        rts

powers: .byte 1, 10, 100

; Prints the four bytes at AX as a dotted IPv4 address.
scr_put_ip:
        sta zp_sc
        stx zp_sc+1
        ldy #0
@byte:  sty zp_s2
        lda (zp_sc),y
        jsr scr_put_dec
        ldy zp_s2
        cpy #3
        beq @done
        lda #'.'
        jsr scr_putc
        ldy zp_s2
        iny
        bne @byte
@done:  rts

; Prints AX (up to 9999) right-aligned in four columns.
scr_put_num4:
        sta zp_s0
        stx zp_s1
        lda #0
        sta zp_s2           ; nonzero once a digit has printed
        ldx #3
@digit: ldy #0              ; this digit: how many times its power fits
@sub:   lda zp_s0
        sec
        sbc powers4_lo,x
        pha
        lda zp_s1
        sbc powers4_hi,x
        bcc @less
        sta zp_s1
        pla
        sta zp_s0
        iny
        bne @sub
@less:  pla
        tya
        bne :+
        lda zp_s2
        bne :+
        cpx #0
        beq :+
        lda #' '            ; a leading zero: blank
        bne @put
:       inc zp_s2
        tya
        ora #'0'
@put:   pha
        txa
        tay
        pla
        sty zp_sb           ; the digit's power, kept across scr_putc
        jsr scr_putc
        ldx zp_sb
        dex
        bpl @digit
        rts

powers4_lo: .byte <1, <10, <100, <1000
powers4_hi: .byte >1, >10, >100, >1000
