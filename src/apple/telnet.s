; TELNET: a terminal for telnet BBSes over the Uthernet II.
;
; It answers the telnet options a BBS asks about (terminal type VT100,
; window size 80x24, server echo, suppress go-ahead) and shows the BBS's
; ANSI/VT100 screens: cursor movement, erasing, and the cursor position
; report many BBSes use to detect an ANSI terminal. Colors and attributes
; are ignored; PC graphics characters print as their nearest ASCII.
;
; Keys: ESC then Q disconnects; ESC then the left or right arrow shows the
; left or right columns on a Soft-70 screen; ESC ESC sends ESC. With a
; Saturn card in slot 0, the last 1,024 lines to scroll off the top are
; kept there, and ESC then - shows them: - goes back a page, SPACE comes
; forward, ESC or RETURN returns to the session.

.import sys_start, sys_exit
.import scr_init, scr_putc, scr_puts, scr_crlf, scr_goto, scr_clear
.import scr_clear_eol, scr_clear_bol, scr_clear_line, scr_clear_eos, scr_clear_bos
.import scr_hscroll, scr_reverse_index, scr_row, scr_col, scr_put_dec
.import scr_batch_begin, scr_batch_end, scr_redraw, scroll_hook, scr_put_num4
.import scr_buf, scr_row_lo, scr_row_hi, beep
.import sat_detect, sat_ok, sat_seek, sat_put, sat_get
.import kbd_avail, kbd_get, line_input, net_start
.import dns_set_hostname, dns_resolve, dns_ip
.import tcp_connect, tcp_connect_ip, tcp_callback, tcp_send, tcp_send_data_len, tcp_close
.import tcp_inbound_data_ptr, tcp_inbound_data_length, ip65_process
.importzp zp_a, zp_c, zp_t0

IAC  = 255
DONT = 254
DO   = 253
WONT = 252
WILL = 251
SB   = 250
SE   = 240
OPT_ECHO  = 1
OPT_SGA   = 3
OPT_TTYPE = 24
OPT_NAWS  = 31
TX_SIZE   = 128
SB_LINES  = 1024            ; scrollback lines, 80 bytes each, from the Saturn's byte 0
SNAPSHOT  = SB_LINES * 80   ; the live screen, kept while scrollback is shown

.segment "STARTUP"
        jmp main

.bss
host:       .res 64
port:       .res 2
closed:     .res 1
escape:     .res 1          ; ESC pressed: the next key is a command
rx_left:    .res 2
; telnet protocol state
tn_state:   .res 1
tn_command: .res 1
sb_count:   .res 1
sb_option:  .res 1
sb_verb:    .res 1
; ANSI parser state
an_state:   .res 1
an_param:   .res 8
an_count:   .res 1
an_private: .res 1
saved_row:  .res 1
saved_col:  .res 1
; replies waiting to go out, and the copy being sent
tx_len:     .res 1
tx_buf:     .res TX_SIZE
tx_send:    .res TX_SIZE
; scrollback
sb_head:    .res 2          ; the slot the next line goes in
sb_lines:   .res 2          ; lines kept
sb_up:      .res 2          ; how far back the view is
sb_base:    .res 2          ; the first line shown, counting from the oldest kept
sb_index:   .res 2
sb_row:     .res 1
sb_saved_row: .res 1
sb_saved_col: .res 1
sb_pos:     .res 3

.rodata
banner:     .byte "Uthernet II Telnet", $0D, $0A, $0A, 0
ask_host:   .byte $0D, $0A, "Connect to host[:port] (RETURN quits): ", 0
msg_lookup: .byte "Looking up the host... ", 0
msg_nohost: .byte "not found.", 0
msg_conn:   .byte "Connecting... ", 0
msg_noconn: .byte "the connection failed.", 0
msg_connected: .byte "connected. ESC Q disconnects.", $0D, $0A, 0
msg_closed: .byte $0D, $0A, "Connection closed.", 0
msg_anykey: .byte " Press a key.", 0
msg_sb1:    .byte "-- Scrollback:", 0
msg_sb2:    .byte " lines up.  - older  SPACE newer  ESC back --", 0
ttype:      .byte "VT100"
TTYPE_LEN = * - ttype

.code

main:
        jsr sys_start
        jsr scr_init
        jsr sat_detect
        lda sat_ok
        beq :+
        lda #$4C            ; JMP sb_save: keep lines as they scroll off
        sta scroll_hook
        lda #<sb_save
        sta scroll_hook+1
        lda #>sb_save
        sta scroll_hook+2
:
        lda #<banner
        ldx #>banner
        jsr scr_puts
        jsr net_start
        bcc ask
        lda #<msg_anykey
        ldx #>msg_anykey
        jsr scr_puts
        jsr kbd_get
        jmp sys_exit

ask:    lda #<ask_host
        ldx #>ask_host
        jsr scr_puts
        lda #<host
        ldx #>host
        ldy #63
        jsr line_input
        bcs quit
        lda host
        bne :+
quit:   jmp sys_exit
:       jsr scr_crlf
        jsr parse_port
        lda #<msg_lookup
        ldx #>msg_lookup
        jsr scr_puts
        lda #<host
        ldx #>host
        jsr dns_set_hostname
        bcs @nohost
        jsr dns_resolve
        bcc :+
@nohost:
        lda #<msg_nohost
        ldx #>msg_nohost
        jsr scr_puts
        jmp ask
:       ldx #3
:       lda dns_ip,x
        sta tcp_connect_ip,x
        dex
        bpl :-
        jsr scr_crlf
        lda #<msg_conn
        ldx #>msg_conn
        jsr scr_puts
        jsr session_reset
        lda #<receive
        sta tcp_callback
        lda #>receive
        sta tcp_callback+1
        lda port
        ldx port+1
        jsr tcp_connect
        bcc :+
        lda #<msg_noconn
        ldx #>msg_noconn
        jsr scr_puts
        jmp ask
:       lda #<msg_connected
        ldx #>msg_connected
        jsr scr_puts

session:
        jsr ip65_process
        jsr tx_flush
        lda closed
        bne @closed
        jsr kbd_avail
        bcc session
        jsr kbd_get
        jsr key
        bcc session
        jsr tcp_close       ; ESC Q
@closed:
        lda #<msg_closed
        ldx #>msg_closed
        jsr scr_puts
        jmp ask

session_reset:
        lda #0
        sta closed
        sta escape
        sta tn_state
        sta an_state
        sta tx_len
        sta saved_row
        sta saved_col
        rts

; Splits "host:port" at the colon; port 23 if there's none.
parse_port:
        lda #23
        sta port
        lda #0
        sta port+1
        ldy #0
:       lda host,y
        beq @done
        cmp #':'
        beq @colon
        iny
        bne :-
@colon: lda #0
        sta host,y
        sta port
@digit: iny
        lda host,y
        sec
        sbc #'0'
        cmp #10
        bcs @done
        pha                 ; port = port * 10 + digit
        lda port
        ldx port+1
        asl port
        rol port+1
        asl port
        rol port+1
        clc
        adc port
        sta port
        txa
        adc port+1
        sta port+1
        asl port
        rol port+1
        pla
        clc
        adc port
        sta port
        bcc @digit
        inc port+1
        bne @digit
@done:  rts

; ---------------------------------------------------------------------
; keyboard

; Handles a key; C set if it asks to disconnect.
; RETURN goes as a bare CR, as a modem delivered it, though the telnet
; standard asks for CR NUL: BBSes that read one key at a time take the NUL
; as the next keypress, so a Y/N question right after a typed line got the
; NUL as its answer.
key:    ldx escape
        bne @command
        cmp #$1B
        beq @escape
@send:  jsr tx_put
        jsr tx_flush
        clc
        rts
@escape:
        lda #1
        sta escape
        clc
        rts
@command:
        ldx #0
        stx escape
        cmp #$1B
        beq @send
        cmp #'-'
        bne :+
        jsr scrollback
        clc
        rts
:       and #$DF            ; fold case
        cmp #'Q'
        beq @quit
        cmp #$08
        beq @left
        cmp #$15
        bne @none
        lda #1
        jsr scr_hscroll
        clc
        rts
@left:  lda #0
        jsr scr_hscroll
@none:  clc
        rts
@quit:  sec
        rts

; ---------------------------------------------------------------------
; sending

tx_put: ldx tx_len
        cpx #TX_SIZE
        bcs :+
        sta tx_buf,x
        inc tx_len
:       rts

; Sends what's queued. tcp_send waits for the acknowledgement, and data
; arriving meanwhile can queue more replies, so it sends a copy.
tx_flush:
        ldx tx_len
        beq @done
        stx tcp_send_data_len
        dex
:       lda tx_buf,x
        sta tx_send,x
        dex
        bpl :-
        lda #0
        sta tx_len
        sta tcp_send_data_len+1
        lda #<tx_send
        ldx #>tx_send
        jsr tcp_send
@done:  rts

; ---------------------------------------------------------------------
; receiving: IP65 calls this with each segment, or $FFFF once the far
; end has closed. Its pointer is zp_c, which the screen code leaves alone.
; The segment is one burst of output, so the cursor is drawn once, after it.

receive:
        lda tcp_inbound_data_length+1
        cmp #$FF
        bne :+
        lda #1
        sta closed
        rts
:       lda tcp_inbound_data_ptr
        sta zp_c
        lda tcp_inbound_data_ptr+1
        sta zp_c+1
        lda tcp_inbound_data_length
        sta rx_left
        lda tcp_inbound_data_length+1
        sta rx_left+1
        jsr scr_batch_begin
@next:  lda rx_left
        ora rx_left+1
        beq @done
        ldy #0
        lda (zp_c),y
        jsr telnet_in
        inc zp_c
        bne :+
        inc zp_c+1
:       lda rx_left
        bne :+
        dec rx_left+1
:       dec rx_left
        jmp @next
@done:  jmp scr_batch_end

; The telnet layer: commands are answered, data goes on to the terminal.
telnet_in:
        ldx tn_state
        beq @data
        dex
        beq @command
        dex
        beq @option
        dex
        beq @sub
        ; 4: IAC inside a subnegotiation
        cmp #SE
        beq @sub_end
        ldx #3
        stx tn_state
        cmp #IAC
        beq @sub_byte
        rts
@data:  cmp #IAC
        beq :+
        jmp ansi_in
:       lda #1
        sta tn_state
        rts
@command:
        cmp #IAC            ; IAC IAC: a literal 255
        bne :+
        lda #0
        sta tn_state
        lda #IAC
        jmp ansi_in
:       cmp #SB
        bne :+
        lda #0
        sta sb_count
        lda #3
        sta tn_state
        rts
:       cmp #WILL
        bcc :+
        sta tn_command
        lda #2
        sta tn_state
        rts
:       lda #0              ; GA, NOP and the rest need no answer
        sta tn_state
        rts
@option:
        ldx #0
        stx tn_state
        jmp negotiate
@sub:   cmp #IAC
        bne @sub_byte
        lda #4
        sta tn_state
        rts
@sub_byte:
        ldx sb_count
        beq :+
        dex
        bne @counted
        sta sb_verb
        beq @counted
:       sta sb_option
@counted:
        inc sb_count
        rts
@sub_end:
        lda #0
        sta tn_state
        lda sb_option
        cmp #OPT_TTYPE
        bne @ignore
        lda sb_verb
        cmp #1              ; SEND
        bne @ignore
        lda #IAC
        jsr tx_put
        lda #SB
        jsr tx_put
        lda #OPT_TTYPE
        jsr tx_put
        lda #0              ; IS
        jsr tx_put
        ldx #0
:       lda ttype,x
        stx zp_t0
        jsr tx_put
        ldx zp_t0
        inx
        cpx #TTYPE_LEN
        bne :-
        lda #IAC
        jsr tx_put
        lda #SE
        jmp tx_put
@ignore:
        rts

; Answers WILL/WONT/DO/DONT (in tn_command) for option A.
negotiate:
        ldx tn_command
        cpx #DO
        beq @do
        cpx #WILL
        bne @done           ; WONT and DONT need no answer
        cmp #OPT_ECHO
        beq :+
        cmp #OPT_SGA
        beq :+
        ldx #DONT
        jmp reply
:       ldx #DO
        jmp reply
@do:    cmp #OPT_TTYPE
        beq :+
        cmp #OPT_NAWS
        beq @naws
        cmp #OPT_SGA
        beq :+
        ldx #WONT
        jmp reply
:       ldx #WILL
        jmp reply
@naws:  ldx #WILL
        jsr reply
        ldx #0
:       lda naws,x
        stx zp_t0
        jsr tx_put
        ldx zp_t0
        inx
        cpx #NAWS_LEN
        bne :-
@done:  rts

naws:   .byte IAC, SB, OPT_NAWS, 0, 80, 0, 24, IAC, SE
NAWS_LEN = * - naws

; Queues IAC, X, A.
reply:  pha
        txa
        pha
        lda #IAC
        jsr tx_put
        pla
        jsr tx_put
        pla
        jmp tx_put

; ---------------------------------------------------------------------
; the terminal: ANSI/VT100 escape sequences

ansi_in:
        ldx an_state
        bne :+
        cmp #$1B
        beq @esc
        jmp scr_putc
@esc:   lda #1
        sta an_state
        rts
:       dex
        beq esc_char
        dex
        beq csi_char
        lda #0              ; 3: the character after ESC ( or ESC )
        sta an_state
        rts

esc_char:
        ldx #0
        stx an_state
        cmp #'['
        beq @csi
        cmp #'('
        beq @skip
        cmp #')'
        beq @skip
        cmp #'7'
        beq save_cursor
        cmp #'8'
        beq restore_cursor
        cmp #'D'
        beq @index
        cmp #'E'
        beq @next_line
        cmp #'M'
        beq @reverse
        cmp #'c'
        beq @reset
        rts
@csi:   lda #2
        sta an_state
        lda #0
        sta an_count
        sta an_private
        sta an_param
        rts
@skip:  lda #3
        sta an_state
        rts
@index: lda #$0A
        jmp scr_putc
@next_line:
        lda #$0D
        jsr scr_putc
        lda #$0A
        jmp scr_putc
@reverse:
        jmp scr_reverse_index
@reset: jmp scr_clear

save_cursor:
        lda scr_row
        sta saved_row
        lda scr_col
        sta saved_col
        rts

restore_cursor:
        ldx saved_row
        ldy saved_col
        jmp scr_goto

csi_char:
        cmp #'0'
        bcc @notdigit
        cmp #'9'+1
        bcs @notdigit
        sbc #'0'-1          ; carry clear: subtracts '0'
        sta zp_t0
        ldx an_count
        lda an_param,x      ; param = param * 10 + digit, capped at 255
        cmp #26
        bcs @big
        asl
        sta an_param,x
        asl
        asl
        adc an_param,x
        adc zp_t0
        bcs @big
        sta an_param,x
        rts
@big:   lda #255
        sta an_param,x
        rts
@notdigit:
        cmp #';'
        bne :+
        ldx an_count
        cpx #7
        bcs @ret
        inx
        stx an_count
        lda #0
        sta an_param,x
@ret:   rts
:       cmp #'?'
        bne :+
        sta an_private
        rts
:       cmp #$40
        bcc @ret            ; intermediate bytes
        ldx #0
        stx an_state
        ldx an_private
        bne @ret            ; private modes (?25h and such) change nothing here
        ldx #0
@find:  ldy csi_final,x
        beq @ret
        cmp csi_final,x
        beq @found
        inx
        bne @find
@found: txa
        asl
        tax
        lda csi_action+1,x
        pha
        lda csi_action,x
        pha
        rts                 ; to the action

; Final characters and the actions they select
csi_final:
        .byte "ABCDGHJKdfnsu", 0
csi_action:
        .word cursor_up-1, cursor_down-1, cursor_right-1, cursor_left-1
        .word column_absolute-1, cursor_position-1, erase_display-1
        .word erase_line-1, row_absolute-1, cursor_position-1
        .word device_status-1, save_cursor-1, restore_cursor-1

; The first parameter, with 0 (or none) meaning 1.
count:  lda an_param
        bne :+
        lda #1
:       rts

cursor_up:
        jsr count
        sta zp_t0
        lda scr_row
        sec
        sbc zp_t0
        bcs :+
        lda #0
:       tax
        ldy scr_col
        jmp scr_goto

cursor_down:
        jsr count
        clc
        adc scr_row
        bcc :+
        lda #255
:       tax
        ldy scr_col
        jmp scr_goto

cursor_right:
        jsr count
        clc
        adc scr_col
        bcc :+
        lda #255
:       tay
        ldx scr_row
        jmp scr_goto

cursor_left:
        jsr count
        sta zp_t0
        lda scr_col
        sec
        sbc zp_t0
        bcs :+
        lda #0
:       tay
        ldx scr_row
        jmp scr_goto

column_absolute:
        jsr count
        tay
        dey
        ldx scr_row
        jmp scr_goto

row_absolute:
        jsr count
        tax
        dex
        ldy scr_col
        jmp scr_goto

; ESC [ row ; col H -- 1-based, missing or 0 meaning 1
cursor_position:
        jsr count
        tax
        dex
        lda an_count
        beq @col1
        lda an_param+1
        bne :+
@col1:  lda #1
:       tay
        dey
        jmp scr_goto

erase_display:
        lda an_param
        beq @below
        cmp #1
        beq @above
        jmp scr_clear_whole
@below: jmp scr_clear_eos
@above: jmp scr_clear_bos

scr_clear_whole:
        lda scr_row
        pha
        lda scr_col
        pha
        jsr scr_clear       ; homes the cursor; ESC [ 2 J leaves it where it was
        pla
        tay
        pla
        tax
        jmp scr_goto

erase_line:
        lda an_param
        beq @right
        cmp #1
        beq @left
        jmp scr_clear_line
@right: jmp scr_clear_eol
@left:  jmp scr_clear_bol

; ESC [ 6 n: report the cursor position as ESC [ row ; col R
device_status:
        lda an_param
        cmp #6
        bne @done
        lda #$1B
        jsr tx_put
        lda #'['
        jsr tx_put
        ldx scr_row
        inx
        txa
        jsr tx_dec
        lda #';'
        jsr tx_put
        ldx scr_col
        inx
        txa
        jsr tx_dec
        lda #'R'
        jmp tx_put
@done:  rts

; Queues A (1-99) as decimal digits.
tx_dec: ldx #0
:       cmp #10
        bcc :+
        sbc #10
        inx
        bne :-
:       pha
        txa
        beq :+
        ora #'0'
        jsr tx_put
:       pla
        ora #'0'
        jmp tx_put

; ---------------------------------------------------------------------
; scrollback, in the Saturn

; The scroll hook: keeps row 0, about to scroll off, in slot sb_head.
sb_save:
        lda sb_head
        ldx sb_head+1
        jsr seek_line
        lda #0
        sta sb_index
:       ldx sb_index
        lda scr_buf,x
        jsr sat_put
        inc sb_index
        lda sb_index
        cmp #80
        bne :-
        inc sb_head         ; the next slot, round the ring
        bne :+
        inc sb_head+1
:       lda sb_head+1
        and #>(SB_LINES-1)
        sta sb_head+1
        lda sb_lines+1      ; one more line kept, up to SB_LINES
        cmp #>SB_LINES
        bcs :+
        inc sb_lines
        bne :+
        inc sb_lines+1
:       rts

; Moves to scrollback slot AX: byte AX * 80.
seek_line:
        sta sb_pos          ; * 16
        stx sb_pos+1
        ldx #4
:       asl sb_pos
        rol sb_pos+1
        dex
        bne :-
        lda sb_pos          ; + * 64 (that * 4)
        sta sb_index
        lda sb_pos+1
        sta sb_index+1
        lda #0
        sta sb_pos+2
        ldx #2
:       asl sb_index
        rol sb_index+1
        rol sb_pos+2
        dex
        bne :-
        clc
        lda sb_pos
        adc sb_index
        pha
        lda sb_pos+1
        adc sb_index+1
        tax
        lda sb_pos+2
        adc #0
        tay
        pla
        jmp sat_seek

; Moves to row A of the saved live screen.
seek_snapshot_row:
        tax                 ; row * 80 = row * 64 + row * 16
        lda #0
        sta sb_pos+1
        txa
        ldx #4
:       asl
        rol sb_pos+1
        dex
        bne :-
        sta sb_pos
        ldx sb_pos+1
        stx sb_index+1
        ldx #2
:       asl
        rol sb_index+1
        dex
        bne :-
        clc
        adc sb_pos
        pha
        lda sb_index+1
        adc sb_pos+1
        adc #>SNAPSHOT
        tax
        pla
        ldy #^SNAPSHOT
        jmp sat_seek

; ESC -: shows the scrollback, a page at a time, then puts the session's
; screen back as it was. Nothing is read from the network meanwhile.
scrollback:
        lda sat_ok
        beq @none
        lda sb_lines
        ora sb_lines+1
        bne :+
@none:  jmp beep
:       lda scr_row
        sta sb_saved_row
        lda scr_col
        sta sb_saved_col
        lda #0              ; keep the live screen
        jsr seek_snapshot_row
        jsr snapshot_copy_out
        lda #0
        sta sb_up+1
        lda #23
        sta sb_up
        jsr clamp_up
@show:  jsr sb_render
@key:   jsr kbd_get
        cmp #'-'
        beq @older
        cmp #' '
        beq @newer
        cmp #$1B
        beq @live
        cmp #$0D
        beq @live
        jmp @key
@older: clc
        lda sb_up
        adc #23
        sta sb_up
        bcc :+
        inc sb_up+1
:       jsr clamp_up
        jmp @show
@newer: lda sb_up+1
        bne :+
        lda sb_up
        cmp #24
        bcc @live
:       sec
        lda sb_up
        sbc #23
        sta sb_up
        bcs :+
        dec sb_up+1
:       jmp @show
@live:  lda #0
        jsr seek_snapshot_row
        jsr snapshot_copy_in
        jsr scr_redraw
        ldx sb_saved_row
        ldy sb_saved_col
        jmp scr_goto

; sb_up = min(sb_up, sb_lines)
clamp_up:
        lda sb_lines
        cmp sb_up
        lda sb_lines+1
        sbc sb_up+1
        bcs :+
        lda sb_lines
        sta sb_up
        lda sb_lines+1
        sta sb_up+1
:       rts

; Copies the whole screen buffer to, or from, the Saturn at the current byte.
snapshot_copy_out:
        jsr snapshot_start
:       ldy #0
        lda (zp_a),y
        jsr sat_put
        jsr snapshot_next
        bne :-
        rts

snapshot_copy_in:
        jsr snapshot_start
:       jsr sat_get
        ldy #0
        sta (zp_a),y
        jsr snapshot_next
        bne :-
        rts

snapshot_start:
        lda #<scr_buf
        sta zp_a
        lda #>scr_buf
        sta zp_a+1
        lda #<(80*24)
        sta sb_index
        lda #>(80*24)
        sta sb_index+1
        rts

; Next byte; Z set when the 1,920 are done.
snapshot_next:
        inc zp_a
        bne :+
        inc zp_a+1
:       lda sb_index
        bne :+
        dec sb_index+1
:       dec sb_index
        lda sb_index
        ora sb_index+1
        rts

; Fills the screen with the lines sb_up back from the live screen's top:
; older ones from the scrollback, the rest from the saved live screen.
sb_render:
        sec                 ; sb_base: the first line shown, as a count from the oldest
        lda sb_lines
        sbc sb_up
        sta sb_base
        lda sb_lines+1
        sbc sb_up+1
        sta sb_base+1
        lda #0
        sta sb_row
@row:   clc                 ; this row's line: sb_base + row
        lda sb_base
        adc sb_row
        sta sb_index
        lda sb_base+1
        adc #0
        sta sb_index+1
        lda sb_index        ; in the scrollback, or the live screen?
        cmp sb_lines
        lda sb_index+1
        sbc sb_lines+1
        bcs @live
        sec                 ; slot = (head - count + line) mod SB_LINES
        lda sb_head
        sbc sb_lines
        tax
        lda sb_head+1
        sbc sb_lines+1
        tay
        txa
        clc
        adc sb_index
        tax
        tya
        adc sb_index+1
        and #>(SB_LINES-1)
        tay
        txa
        pha
        tya
        tax
        pla
        jsr seek_line
        jmp @copy
@live:  sec                 ; live row = line - count
        lda sb_index
        sbc sb_lines
        jsr seek_snapshot_row
@copy:  ldx sb_row
        lda scr_row_lo,x
        sta zp_a
        lda scr_row_hi,x
        sta zp_a+1
        lda #0
        sta sb_index
:       jsr sat_get
        ldy sb_index
        sta (zp_a),y
        inc sb_index
        lda sb_index
        cmp #80
        bne :-
        inc sb_row
        lda sb_row
        cmp #24
        bne @row
        jsr scr_redraw
        ldx #23             ; the status, over the bottom line
        ldy #0
        jsr scr_goto
        jsr scr_clear_eol
        lda #<msg_sb1
        ldx #>msg_sb1
        jsr scr_puts
        lda sb_up
        ldx sb_up+1
        jsr scr_put_num4
        lda #<msg_sb2
        ldx #>msg_sb2
        jmp scr_puts
