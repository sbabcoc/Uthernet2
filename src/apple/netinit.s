; Finds the Uthernet II and brings the network up with IP65: DHCP for an
; address, then ready for DNS and TCP.
;
; The card has no ROM, so it's found by how it behaves, as the Uthernet II
; manual's probe does (p. 38): writing $80 to the mode register resets a
; W5100, whose mode register then reads $00; writing $03 then reads back
; $03. Slots are tried in the manual's order, skipping slot 3 when the
; VideoTerm is there.
;
; The manual probes at $C0n4, but only A1-A0 reach the W5100 (schematic,
; pp. 47-48), so $C0nC is the same mode register -- and it's harmless to
; the cards the probe passes on the way: $C0n4 is the host file card's
; print register, where the probe's two bytes became a print job, while
; $C0nC is unused there and on the SoftCard II, and is the Disk II's idle
; read-latch switch.

.export net_start, net_slot
.import ip65_init, dhcp_init, cfg_ip
.import scr_puts, scr_put_dec, scr_put_ip, scr_crlf, scr_driver

MODE = $C08C                ; the W5100's mode register at $C0nC, with X = slot * 16

.bss
net_slot: .res 1

.rodata
probe_order: .byte 3, 4, 2, 1, 5, 6, 7
msg_probe:   .byte "Looking for the Uthernet II... ", 0
msg_found:   .byte "slot ", 0
msg_none:    .byte "not found.", 0
msg_init:    .byte "The card didn't start.", 0
msg_dhcp:    .byte "Getting an address... ", 0
msg_nodhcp:  .byte "no DHCP server answered.", 0

.code

; C clear once the network is up; C set, with the reason printed, if not.
net_start:
        lda #<msg_probe
        ldx #>msg_probe
        jsr scr_puts
        ldy #0
@slot:  lda probe_order,y
        cmp #3
        bne :+
        ldx scr_driver      ; 0: the VideoTerm owns slot 3
        beq @next
:       sta net_slot
        asl
        asl
        asl
        asl
        tax
        lda #$80
        sta MODE,x
        nop
        nop
        lda MODE,x
        bne @next
        lda #$03
        sta MODE,x
        lda MODE,x
        cmp #$03
        beq @found
@next:  iny
        cpy #7
        bne @slot
        lda #<msg_none
        ldx #>msg_none
        jmp fail
@found: lda #<msg_found
        ldx #>msg_found
        jsr scr_puts
        lda net_slot
        jsr scr_put_dec
        jsr scr_crlf
        lda net_slot
        jsr ip65_init
        bcc :+
        lda #<msg_init
        ldx #>msg_init
        jmp fail
:       lda #<msg_dhcp
        ldx #>msg_dhcp
        jsr scr_puts
        jsr dhcp_init
        bcc :+
        lda #<msg_nodhcp
        ldx #>msg_nodhcp
        jmp fail
:       lda #<cfg_ip
        ldx #>cfg_ip
        jsr scr_put_ip
        jsr scr_crlf
        clc
        rts

fail:   jsr scr_puts
        jsr scr_crlf
        sec
        rts
