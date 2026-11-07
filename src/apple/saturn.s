; The Saturn Systems 128K RAM card, used as extra memory: TELNET keeps
; its scrollback there and GOPHER its page cache. It's looked for only in
; slot 0, where it stands in for the Language Card; probing other slots'
; switches can disturb whatever card is there.
;
; The card's first 16K bank is the Language Card that ProDOS or DOS 3.3
; uses, so only banks 2-8 are used: 7 banks of 12K each -- $D000-$DFFF
; (4K bank A), $E000-$EFFF and $F000-$FFFF -- 84K in all, addressed here
; as one run of 4K chunks. Every access switches a bank in, moves one
; byte, and puts back bank 1 with ROM reads, so between accesses the card
; is always as the operating system expects. Switches, from the Saturn
; manual (ReplicApple2Plus HARDWARE-REFERENCE.md section 2): $C080 RAM
; read, write-protect; $C083 twice, RAM read, write enabled; $C082 ROM
; read, write-protect; $C084-$C087 and $C08C-$C08F select banks 1-8.

.export sat_detect, sat_ok, sat_seek, sat_put, sat_get
.importzp zp_sat

RAM_READ   = $C080
RAM_WRITE  = $C083
ROM_READ   = $C082
BANK_1     = $C084

.bss
sat_ok: .res 1              ; nonzero once a Saturn has been found
chunk:  .res 1              ; the current 4K chunk, 0-20
offset: .res 2              ; within it, 0-$FFF
probe:  .res 32

.rodata
; chunk 0-20: its bank's select switch (offset from $C080) and its page
chunk_select:
        .byte $05, $05, $05, $06, $06, $06, $07, $07, $07, $0C, $0C, $0C
        .byte $0D, $0D, $0D, $0E, $0E, $0E, $0F, $0F, $0F
chunk_page:
.repeat 21, c
        .byte $D0 + (c .mod 3) * $10
.endrepeat

.code

; Looks for a Saturn in slot 0, without writing to it: $D000 is read in
; banks 3 and 4, which hold different (power-up random) data on a Saturn.
; On a Language Card $C086 and $C087 are mirrors of $C082 and $C083, so
; both reads see the same RAM; with no card, both see ROM. Sets sat_ok.
sat_detect:
        lda $C086           ; Saturn: bank 3
        lda RAM_READ
        ldx #31
:       lda $D000,x
        sta probe,x
        dex
        bpl :-
        lda $C087           ; Saturn: bank 4
        lda RAM_READ
        ldy #0
        ldx #31
:       lda $D000,x
        cmp probe,x
        beq :+
        iny
:       dex
        bpl :--
        lda BANK_1
        lda ROM_READ
        sty sat_ok
        rts

; Moves to byte AXY (A low, Y high) of the 84K.
sat_seek:
        sta offset
        txa
        and #$0F
        sta offset+1
        txa
        lsr
        lsr
        lsr
        lsr
        sta chunk
        tya
        asl
        asl
        asl
        asl
        ora chunk
        sta chunk
        rts

; Points zp_sat at the current byte, with its bank selected.
select:
        ldx chunk
        ldy chunk_select,x
        lda RAM_READ,y
        lda chunk_page,x
        clc
        adc offset+1
        sta zp_sat+1
        lda offset
        sta zp_sat
        rts

; Stores A at the current byte and moves on.
sat_put:
        pha
        jsr select
        lda RAM_WRITE
        lda RAM_WRITE
        pla
        ldy #0
        sta (zp_sat),y
        lda BANK_1
        lda ROM_READ
        jmp advance

; Reads the current byte into A and moves on.
sat_get:
        jsr select
        lda RAM_READ
        ldy #0
        lda (zp_sat),y
        tax
        lda BANK_1
        lda ROM_READ
        jsr advance
        txa
        rts

advance:
        inc offset
        bne :+
        inc offset+1
        lda offset+1
        cmp #$10
        bcc :+
        lda #0
        sta offset+1
        inc chunk
:       rts
