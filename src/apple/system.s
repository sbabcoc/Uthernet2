; Program entry and exit. Each utility's STARTUP code calls sys_start
; first and leaves through sys_exit, which returns to BASIC through the
; DOS 3.3 / ProDOS BASIC.SYSTEM warm-start vector at $3D0.

.export sys_start, sys_exit, beep
.import scr_exit
.import __BSS_RUN__, __BSS_SIZE__
.importzp zp_a

ZP_SAVE_START = $80
ZP_SAVE_SIZE  = $40

.segment "LOWBSS"
zp_save: .res ZP_SAVE_SIZE

.code

; Saves the zero page we use, then zeroes BSS (IP65's state included).
sys_start:
        cld
        ldx #ZP_SAVE_SIZE-1
:       lda ZP_SAVE_START,x
        sta zp_save,x
        dex
        bpl :-
        lda #<__BSS_RUN__
        sta zp_a
        lda #>__BSS_RUN__
        sta zp_a+1
        ldx #>__BSS_SIZE__
        lda #0
        tay
@page:  cpx #0
        beq @rest
:       sta (zp_a),y
        iny
        bne :-
        inc zp_a+1
        dex
        jmp @page
@rest:  cpy #<__BSS_SIZE__
        beq @done
        sta (zp_a),y
        iny
        jmp @rest
@done:  rts

; Restores the screen and zero page, then warm-starts BASIC.
sys_exit:
        jsr scr_exit
        ldx #ZP_SAVE_SIZE-1
:       lda zp_save,x
        sta ZP_SAVE_START,x
        dex
        bpl :-
        jmp $03D0

; A short click train on the speaker.
beep:
        ldy #$40
@cycle: lda $C030
        ldx #$60
:       dex
        bne :-
        dey
        bne @cycle
        rts
