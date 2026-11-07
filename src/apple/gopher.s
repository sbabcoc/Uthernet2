; GOPHER: a Gopher client over the Uthernet II.
;
; Menus show each item with a number; text documents page through the
; screen. Keys:
;   menu:  number then RETURN opens an item, SPACE and - page forward and
;          back, ESC goes back, G goes to a server, Q quits
;   text:  SPACE and - page, ESC goes back, G and Q as in a menu
; On a Soft-70 screen, the left and right arrows show the left or right
; columns.
;
; Items of type 0 (text), 1 (menu) and 7 (search) open; other types are
; listed but not fetched. Text streams: each page is drawn as it arrives,
; and the connection waits, unread, while the page is on screen, so a
; document of any length can be read. Paging back fetches the document
; again and skips ahead. A menu is held whole, so one larger than free
; memory is cut off.

.import sys_start, sys_exit
.import scr_init, scr_putc, scr_puts, scr_crlf, scr_goto, scr_clear
.import scr_clear_eol, scr_hscroll, scr_row, scr_col, scr_put_dec
.import scr_batch_begin, scr_batch_end
.import kbd_avail, kbd_get, line_input, net_start, scr_driver
.import dns_set_hostname, dns_resolve, dns_ip
.import tcp_connect, tcp_connect_ip, tcp_callback, tcp_send, tcp_send_data_len, tcp_close
.import tcp_inbound_data_ptr, tcp_inbound_data_length, ip65_process, tcp_state
.import __BSS_RUN__, __BSS_SIZE__
.importzp zp_a, zp_b, zp_c, zp_t0, zp_t1, zp_t2

HOST_SIZE = 64
SEL_SIZE  = 128
MAX_LINES = 255
HISTORY   = 6
ENTRY_SIZE = 1 + HOST_SIZE + 2 + SEL_SIZE + 1   ; type, host, port, selector, page
PAGE_ROWS = 22              ; menu lines per page
TEXT_ROWS = 23              ; text lines per page
STATUS_ROW = 23
MAX_PAGES = 64
MAIN_END  = $9600           ; DOS 3.3 and BASIC.SYSTEM both start here
HIRES     = $2000           ; free for documents when the VideoTerm is the screen
HIRES_END = $4000

.segment "STARTUP"
        jmp main

.segment "LOWBSS"
history:    .res HISTORY * ENTRY_SIZE
line_lo:    .res MAX_LINES
line_hi:    .res MAX_LINES
line_item:  .res MAX_LINES  ; item number, 0 for an info line
page_lo:    .res MAX_PAGES
page_hi:    .res MAX_PAGES
input:      .res SEL_SIZE
request_line: .res SEL_SIZE + 2

.bss
; where we are
cur_type:   .res 1
cur_host:   .res HOST_SIZE
cur_port:   .res 2
cur_sel:    .res SEL_SIZE
cur_page:   .res 1
depth:      .res 1
; the document: doc_start up to (not including) page doc_end_page
doc_start:  .res 2
doc_end_page: .res 1
doc_len:    .res 2
cut_off:    .res 1
done:       .res 1
line_count: .res 1
item_count: .res 1
page_count: .res 1
number:     .res 1          ; item number being typed
typed:      .res 1          ; digits typed
row:        .res 1
col:        .res 1
; a streaming text document: unread data runs from head to doc_len
head:       .res 2
text_ended: .res 1          ; the last page has been drawn
line_start: .res 1          ; the next byte starts a line
pending:    .res 1          ; nonzero: pending_byte comes next
pending_byte: .res 1
skipping:   .res 1          ; nonzero: count pages without drawing them
skip_left:  .res 1
; IP65's BSS follows this program's, and tcp.s jumps through its
; tcp_callback vector with JMP (abs), which the 6502 gets wrong when the
; vector's low byte is at $xxFF. ld65 checks that with an assertion; when it
; fires after a change here, adjust this padding by a byte.
pad:        .res 1

.rodata
default_host: .byte "gopher.floodgap.com", 0
banner:     .byte "Uthernet II Gopher", $0D, $0A, $0A, 0
ask_go:     .byte $0D, $0A, "Go to host[:port][/type selector] (RETURN for gopher.floodgap.com): ", 0
msg_lookup: .byte "Looking up ", 0
msg_conn:   .byte "... connecting... ", 0
msg_nohost: .byte "host not found.", 0
msg_noconn: .byte "connection failed.", 0
msg_load:   .byte "loading...", 0
msg_cut:    .byte " [cut off: out of memory]", 0
msg_anykey: .byte " Press a key.", 0
msg_cant:   .byte "Can't open this type of item. Press a key.", 0
msg_search: .byte "Search for: ", 0
menu_help:  .byte "  RET open SPC/- page ESC back G go Q quit", 0
text_help:  .byte "  SPC/- page  ESC back  G go  Q quit", 0
page_label: .byte "Page ", 0
msg_end:    .byte "  [end]", 0
item_label: .byte "Item ", 0
prefix:     .byte "gopher://"
PREFIX_LEN = * - prefix

.code

main:
        jsr sys_start
        jsr scr_init
        lda #<banner
        ldx #>banner
        jsr scr_puts
        jsr net_start
        bcc :+
        lda #<msg_anykey
        ldx #>msg_anykey
        jsr scr_puts
        jsr kbd_get
        jmp sys_exit
:       jsr choose_buffer
        lda #0
        sta depth
        jsr go_prompt
        bcc :+
        jmp sys_exit
:       jmp open_current

; Documents go in the Hi-Res page when the VideoTerm leaves it free, else
; in the memory after the program.
choose_buffer:
        lda scr_driver
        bne :+
        lda #<HIRES
        sta doc_start
        lda #>HIRES
        sta doc_start+1
        lda #>HIRES_END
        sta doc_end_page
        rts
:       lda #<(__BSS_RUN__ + __BSS_SIZE__)
        sta doc_start
        lda #>(__BSS_RUN__ + __BSS_SIZE__)
        sta doc_start+1
        lda #>MAIN_END
        sta doc_end_page
        rts

; ---------------------------------------------------------------------
; going places

; Asks where to go and makes it the current location. C set if abandoned.
go_prompt:
        lda #<ask_go
        ldx #>ask_go
        jsr scr_puts
        lda #<input
        ldx #>input
        ldy #SEL_SIZE-1
        jsr line_input
        bcs @done
        jsr scr_crlf
        lda input
        bne :+
        ldx #0              ; the default server
@copy:  lda default_host,x
        sta input,x
        beq :+
        inx
        bne @copy
:       jsr parse_location
        clc
@done:  rts

; Parses input -- [gopher://]host[:port][/type selector] -- into cur_*.
parse_location:
        ldy #0
        ldx #0              ; skip gopher:// if it's there
:       lda input,x
        ora #$20
        cmp prefix,x
        bne @host
        inx
        cpx #PREFIX_LEN
        bne :-
        ldy #PREFIX_LEN
@host:  ldx #0
:       lda input,y
        beq @end_host
        cmp #':'
        beq @end_host
        cmp #'/'
        beq @end_host
        cpx #HOST_SIZE-1
        bcs :+
        sta cur_host,x
        inx
:       iny
        bne :--
@end_host:
        lda #0
        sta cur_host,x
        lda #70
        sta cur_port
        lda #0
        sta cur_port+1
        lda #'1'
        sta cur_type
        lda #0
        sta cur_sel
        sta cur_page
        lda input,y
        cmp #':'
        bne @path
        iny
        tya
        pha
        clc
        adc #<input
        sta zp_a
        lda #>input
        adc #0
        sta zp_a+1
        jsr parse_number
        sta cur_port
        stx cur_port+1
        pla                 ; past the digits
        clc
        adc zp_t2
        tay
@path:  lda input,y
        cmp #'/'
        bne @done
        iny
        lda input,y
        beq @done
        sta cur_type
        iny
        ldx #0
:       lda input,y
        sta cur_sel,x
        beq @done
        iny
        inx
        cpx #SEL_SIZE-1
        bne :-
        lda #0
        sta cur_sel,x
@done:  rts

; Reads a decimal number at zp_a (stopping at the first non-digit) into
; AX; Y = digits read.
parse_number:
        lda #0
        sta zp_t0
        sta zp_t1
        ldy #0
@digit: lda (zp_a),y
        sec
        sbc #'0'
        cmp #10
        bcs @done
        pha
        lda zp_t0           ; value * 10
        ldx zp_t1
        asl zp_t0
        rol zp_t1
        asl zp_t0
        rol zp_t1
        clc
        adc zp_t0
        sta zp_t0
        txa
        adc zp_t1
        sta zp_t1
        asl zp_t0
        rol zp_t1
        pla
        clc
        adc zp_t0
        sta zp_t0
        bcc :+
        inc zp_t1
:       iny
        bne @digit
@done:  sty zp_t2
        lda zp_t0
        ldx zp_t1
        rts

; Pushes the current location onto the history.
push_history:
        lda depth
        cmp #HISTORY
        bcc :+
        jsr drop_oldest
:       jsr entry_pointer
        jsr copy_to_entry
        inc depth
        rts

; Forgets the oldest entry to make room.
drop_oldest:
        lda #<(history+ENTRY_SIZE)
        sta zp_a
        lda #>(history+ENTRY_SIZE)
        sta zp_a+1
        lda #<history
        sta zp_b
        lda #>history
        sta zp_b+1
        lda #<((HISTORY-1)*ENTRY_SIZE)
        sta zp_c
        lda #>((HISTORY-1)*ENTRY_SIZE)
        sta zp_c+1
        ldy #0
@byte:  lda (zp_a),y
        sta (zp_b),y
        inc zp_a
        bne :+
        inc zp_a+1
:       inc zp_b
        bne :+
        inc zp_b+1
:       lda zp_c
        bne :+
        dec zp_c+1
:       dec zp_c
        lda zp_c
        ora zp_c+1
        bne @byte
        dec depth
        rts

; Points zp_b at history entry `depth`.
entry_pointer:
        lda #<history
        sta zp_b
        lda #>history
        sta zp_b+1
        ldx depth
        beq @done
:       clc
        lda zp_b
        adc #ENTRY_SIZE
        sta zp_b
        bcc :+
        inc zp_b+1
:       dex
        bne :--
@done:  rts

copy_to_entry:
        ldy #0
        lda cur_type
        sta (zp_b),y
        ldx #0
:       iny
        lda cur_host,x
        sta (zp_b),y
        inx
        cpx #HOST_SIZE
        bne :-
        iny
        lda cur_port
        sta (zp_b),y
        iny
        lda cur_port+1
        sta (zp_b),y
        ldx #0
:       iny
        lda cur_sel,x
        sta (zp_b),y
        inx
        cpx #SEL_SIZE
        bne :-
        iny
        lda cur_page
        sta (zp_b),y
        rts

; Back to the previous location; C set if there's none.
pop_history:
        lda depth
        bne :+
        sec
        rts
:       dec depth
        jsr entry_pointer
        ldy #0
        lda (zp_b),y
        sta cur_type
        ldx #0
:       iny
        lda (zp_b),y
        sta cur_host,x
        inx
        cpx #HOST_SIZE
        bne :-
        iny
        lda (zp_b),y
        sta cur_port
        iny
        lda (zp_b),y
        sta cur_port+1
        ldx #0
:       iny
        lda (zp_b),y
        sta cur_sel,x
        inx
        cpx #SEL_SIZE
        bne :-
        iny
        lda (zp_b),y
        sta cur_page
        clc
        rts

; ---------------------------------------------------------------------
; fetching

; Fetches the current location and shows it.
open_current:
        lda cur_type
        cmp #'0'
        bne @menu
        jsr request
        bcs open_failed
        jmp show_text
@menu:  jsr fetch
        bcs open_failed
        jmp show_menu

open_failed:
        lda #<msg_anykey
        ldx #>msg_anykey
        jsr scr_puts
        jsr kbd_get
        jsr pop_history
        bcs :+
        jmp open_current
:       jsr go_prompt
        bcs :+
        jmp open_current
:       jmp sys_exit

; Fetches cur_host:cur_port's cur_sel whole into the document buffer. C
; set, with the reason printed, on failure.
fetch:  jsr request
        bcs @done
@wait:  jsr ip65_process
        lda done
        bne @got
        jsr kbd_avail
        bcc @wait
        jsr kbd_get
        cmp #$1B
        bne @wait
        jsr tcp_close
@got:   clc
@done:  rts

; Connects to cur_host:cur_port and sends cur_sel; what comes back is
; appended to the document buffer as IP65 delivers it. C set, with the
; reason printed, on failure.
request:
        jsr scr_clear
        lda #<msg_lookup
        ldx #>msg_lookup
        jsr scr_puts
        lda #<cur_host
        ldx #>cur_host
        jsr scr_puts
        lda #<cur_host
        ldx #>cur_host
        jsr dns_set_hostname
        bcs @nohost
        jsr dns_resolve
        bcc :+
@nohost:
        lda #<msg_nohost
        ldx #>msg_nohost
        jmp fail
:       ldx #3
:       lda dns_ip,x
        sta tcp_connect_ip,x
        dex
        bpl :-
        lda #<msg_conn
        ldx #>msg_conn
        jsr scr_puts
        lda #0
        sta doc_len
        sta doc_len+1
        sta cut_off
        sta done
        lda #<receive
        sta tcp_callback
        lda #>receive
        sta tcp_callback+1
        lda cur_port
        ldx cur_port+1
        jsr tcp_connect
        bcc :+
        lda #<msg_noconn
        ldx #>msg_noconn
        jmp fail
:       lda #<msg_load
        ldx #>msg_load
        jsr scr_puts
        ldx #0              ; the request: selector, CR, LF
:       lda cur_sel,x
        beq :+
        sta request_line,x
        inx
        bne :-
:       lda #$0D
        sta request_line,x
        inx
        lda #$0A
        sta request_line,x
        inx
        stx tcp_send_data_len
        lda #0
        sta tcp_send_data_len+1
        lda #<request_line
        ldx #>request_line
        jsr tcp_send
        clc
        rts

fail:   jsr scr_puts
        sec
        rts

; IP65 calls this with each segment, or $FFFF once the server has closed.
receive:
        lda tcp_inbound_data_length+1
        cmp #$FF
        bne :+
        lda #1
        sta done
        rts
:       lda tcp_inbound_data_ptr
        sta zp_a
        lda tcp_inbound_data_ptr+1
        sta zp_a+1
        clc                 ; zp_b: where the data goes
        lda doc_start
        adc doc_len
        sta zp_b
        lda doc_start+1
        adc doc_len+1
        sta zp_b+1
        lda tcp_inbound_data_length
        sta zp_c
        lda tcp_inbound_data_length+1
        sta zp_c+1
        ldy #0
@next:  lda zp_c
        ora zp_c+1
        beq @done
        lda zp_b+1
        cmp doc_end_page
        bcc :+
        lda #1
        sta cut_off
        rts
:       lda (zp_a),y
        sta (zp_b),y
        inc zp_a
        bne :+
        inc zp_a+1
:       inc zp_b
        bne :+
        inc zp_b+1
:       inc doc_len
        bne :+
        inc doc_len+1
:       lda zp_c
        bne :+
        dec zp_c+1
:       dec zp_c
        jmp @next
@done:  rts

; Points zp_a at document offset AX and sets zp_c to the bytes left.
doc_at: clc
        adc doc_start
        sta zp_a
        txa
        adc doc_start+1
        sta zp_a+1
        clc
        lda doc_start
        adc doc_len
        sta zp_c
        lda doc_start+1
        adc doc_len+1
        sta zp_c+1
        rts

; ---------------------------------------------------------------------
; menus

show_menu:
        jsr index_menu
@page:  jsr draw_menu
        lda #0
        sta number
        sta typed
@key:   jsr status_menu
        jsr kbd_get
        cmp #$0D
        beq @open
        cmp #' '
        beq @next
        cmp #'-'
        beq @prev
        cmp #$08
        beq @left
        cmp #$15
        beq @right
        cmp #$1B
        beq @back
        and #$DF
        cmp #'Q'
        beq @quit
        cmp #'G'
        beq @go
        and #$1F            ; digits ('0'-'9' & $DF & $1F = $10-$19)
        sec
        sbc #$10
        cmp #10
        bcs @key
        jsr add_digit
        jmp @key
@next:  lda cur_page
        clc
        adc #1
        jsr menu_page_ok
        bcs @key
        inc cur_page
        jmp @page
@prev:  lda cur_page
        beq @key
        dec cur_page
        jmp @page
@left:  lda #0
        jsr scr_hscroll
        jmp @key
@right: lda #1
        jsr scr_hscroll
        jmp @key
@back:  jsr pop_history
        bcs @key
        jmp open_current
@quit:  jmp sys_exit
@go:    jsr scr_clear
        jsr push_history
        jsr go_prompt
        bcc :+
        jsr pop_history
:       jmp open_current
@open:  lda typed
        bne :+
        jmp @key
:       jsr open_item
        jmp @page

; A = page: C clear if it has lines on it.
menu_page_ok:
        tax
        lda #0
:       cpx #0
        beq :+
        clc
        adc #PAGE_ROWS
        bcs @no
        dex
        jmp :-
:       cmp line_count
        bcs @no
        clc
        rts
@no:    sec
        rts

add_digit:
        sta zp_t0
        lda typed
        cmp #3
        bcs @done
        inc typed
        lda number          ; number * 10 + digit, if it fits
        cmp #26
        bcs @done
        asl
        sta zp_t1
        asl
        asl
        adc zp_t1
        adc zp_t0
        bcs @done
        sta number
@done:  rts

; Records where each line starts and numbers the items.
index_menu:
        lda #0
        sta line_count
        sta item_count
        tax
        jsr doc_at
@line:  lda zp_a
        cmp zp_c
        lda zp_a+1
        sbc zp_c+1
        bcs @done           ; end of document
        ldy #0
        lda (zp_a),y
        cmp #'.'            ; "." alone ends a menu
        bne :+
        iny
        lda (zp_a),y
        cmp #$0D
        beq @done
        cmp #$0A
        beq @done
        dey
        lda (zp_a),y
:       ldx line_count
        cpx #MAX_LINES
        bcs @done
        pha
        lda zp_a
        sta line_lo,x
        lda zp_a+1
        sta line_hi,x
        pla
        ldy #0
        cmp #'i'            ; info lines, blank lines and errors aren't items
        beq :+
        cmp #'3'
        beq :+
        cmp #$0D
        beq :+
        cmp #$0A
        beq :+
        inc item_count
        ldy item_count
:       tya
        sta line_item,x
        inc line_count
        jsr next_line
        bcc @line
        lda cut_off         ; a last line the buffer cut off is half an item:
        beq @done           ; leave it out
        dec line_count
        ldx line_count
        lda line_item,x
        beq @done
        dec item_count
@done:  rts

; Moves zp_a past the next LF; C set at the end of the document.
next_line:
        ldy #0
@char:  lda zp_a
        cmp zp_c
        lda zp_a+1
        sbc zp_c+1
        bcs @end
        lda (zp_a),y
        inc zp_a
        bne :+
        inc zp_a+1
:       cmp #$0A
        bne @char
        clc
        rts
@end:   sec
        rts

draw_menu:
        jsr scr_batch_begin
        jsr draw_menu_page
        jmp scr_batch_end

draw_menu_page:
        jsr scr_clear
        lda cur_page        ; first line: page * PAGE_ROWS
        ldx #0
        stx zp_t2
        tax
        lda #0
:       cpx #0
        beq :+
        clc
        adc #PAGE_ROWS
        dex
        jmp :-
:       sta zp_t2           ; line index
        lda #0
        sta row
@row:   ldx zp_t2
        cpx line_count
        bcs @done
        ldx row
        ldy #0
        jsr scr_goto
        ldx zp_t2
        lda line_lo,x
        sta zp_b
        lda line_hi,x
        sta zp_b+1
        lda line_item,x
        beq @info
        jsr put_number3
        lda #' '
        jsr scr_putc
        jmp @text
@info:  lda #<four_spaces
        ldx #>four_spaces
        jsr scr_puts
@text:  ldy #1              ; the display string: after the type, up to a tab
@char:  lda (zp_b),y
        cmp #$09
        beq @eol
        cmp #$0D
        beq @eol
        cmp #$0A
        beq @eol
        sty zp_t1
        jsr scr_putc
        ldy zp_t1
        lda scr_col
        cmp #79
        bcs @eol
        iny
        bne @char
@eol:   inc zp_t2
        inc row
        lda row
        cmp #PAGE_ROWS
        bne @row
@done:  rts

four_spaces: .byte "    ", 0

; Prints A right-aligned in three columns.
put_number3:
        cmp #100
        bcs :++
        pha
        lda #' '
        jsr scr_putc
        pla
        cmp #10
        bcs :+
        pha
        lda #' '
        jsr scr_putc
        pla
:       jmp scr_put_dec
:       jmp scr_put_dec

status_menu:
        ldx #STATUS_ROW
        ldy #0
        jsr scr_goto
        jsr scr_clear_eol
        lda #<item_label
        ldx #>item_label
        jsr scr_puts
        lda typed
        beq :+
        lda number
        jsr scr_put_dec
:       lda #'_'
        jsr scr_putc
        lda #<menu_help
        ldx #>menu_help
        jsr scr_puts
        lda cut_off
        beq :+
        lda #<msg_cut
        ldx #>msg_cut
        jsr scr_puts
:       ldx #STATUS_ROW     ; leave the cursor after the number
        ldy #5
        lda typed
        beq :+
        lda number
        cmp #10
        iny
        bcc :+
        iny
        cmp #100
        bcc :+
        iny
:       jmp scr_goto

; Opens item `number`.
open_item:
        ldx #0
:       cpx line_count
        bcs @none
        lda line_item,x
        cmp number
        beq @found
        inx
        bne :-
@none:  rts
@found: lda line_lo,x
        sta zp_b
        lda line_hi,x
        sta zp_b+1
        ldy #0
        lda (zp_b),y
        cmp #'0'
        beq @fetch
        cmp #'1'
        beq @fetch
        cmp #'7'
        beq @fetch
        jsr status_line
        lda #<msg_cant
        ldx #>msg_cant
        jsr scr_puts
        jsr kbd_get
        rts
@fetch: pla                 ; the menu is left behind: drop the return address
        pla
        txa
        pha
        jsr push_history
        pla
        tax
        lda line_lo,x
        sta zp_b
        lda line_hi,x
        sta zp_b+1
        ldy #0
        lda (zp_b),y
        sta cur_type
        jsr item_fields
        lda cur_type
        cmp #'7'
        bne :+
        jsr search_query
        bcc :+
        jsr pop_history     ; abandoned
        jmp open_current
:       lda #'7'
        cmp cur_type
        bne :+
        lda #'1'            ; search results are a menu
        sta cur_type
:       lda #0
        sta cur_page
        jmp open_current

status_line:
        ldx #STATUS_ROW
        ldy #0
        jsr scr_goto
        jmp scr_clear_eol

; Copies the selector, host and port fields of the menu line at zp_b.
item_fields:
        ldy #1
        jsr skip_field      ; display string
        ldx #0
:       lda (zp_b),y        ; selector
        jsr field_end
        beq :+
        cpx #SEL_SIZE-1
        bcs @sel_skip
        sta cur_sel,x
        inx
@sel_skip:
        iny
        bne :-
:       lda #0
        sta cur_sel,x
        iny
        ldx #0
:       lda (zp_b),y        ; host
        jsr field_end
        beq :+
        cpx #HOST_SIZE-1
        bcs @host_skip
        sta cur_host,x
        inx
@host_skip:
        iny
        bne :-
:       lda #0
        sta cur_host,x
        iny
        tya
        clc
        adc zp_b
        sta zp_a
        lda zp_b+1
        adc #0
        sta zp_a+1
        jsr parse_number
        sta cur_port
        stx cur_port+1
        ora cur_port+1
        bne :+
        lda #70
        sta cur_port
:       rts

; Skips to just past the next tab (or to the end of the line).
skip_field:
:       lda (zp_b),y
        jsr field_end
        beq :+
        iny
        bne :-
:       iny
        rts

; Z set if A ends a field: tab, CR, LF.
field_end:
        cmp #$09
        beq :+
        cmp #$0D
        beq :+
        cmp #$0A
:       rts

; Asks for a search; appends TAB and the query to cur_sel. C set if abandoned.
search_query:
        jsr status_line
        lda #<msg_search
        ldx #>msg_search
        jsr scr_puts
        lda #<input
        ldx #>input
        ldy #60
        jsr line_input
        bcs @done
        ldx #0
:       lda cur_sel,x
        beq :+
        inx
        bne :-
:       lda #$09
        sta cur_sel,x
        inx
        ldy #0
:       cpx #SEL_SIZE-1
        bcs :+
        lda input,y
        sta cur_sel,x
        beq @ok
        inx
        iny
        bne :-
:       lda #0
        sta cur_sel,x
@ok:    clc
@done:  rts

; ---------------------------------------------------------------------
; text documents

show_text:
        lda #0
        sta cur_page
        jsr stream_reset
@page:  jsr draw_text
@key:   jsr status_text
        jsr kbd_get
        cmp #' '
        beq @next
        cmp #'-'
        beq @prev
        cmp #$08
        beq @left
        cmp #$15
        beq @right
        cmp #$1B
        beq @back
        and #$DF
        cmp #'Q'
        beq @quit
        cmp #'G'
        beq @go
        jmp @key
@next:  lda text_ended
        bne @key
        lda cur_page
        cmp #255
        beq @key
        inc cur_page
        jmp @page
@prev:  lda cur_page
        beq @key
        dec cur_page
        jsr reopen
        bcc @page
        jmp open_failed
@left:  lda #0
        jsr scr_hscroll
        jmp @key
@right: lda #1
        jsr scr_hscroll
        jmp @key
@back:  jsr pop_history
        bcs @key
        jsr drop_connection
        jmp open_current
@quit:  jsr drop_connection
        jmp sys_exit
@go:    jsr drop_connection
        jsr scr_clear
        jsr push_history
        jsr go_prompt
        bcc :+
        jsr pop_history
:       jmp open_current

; Starts reading a document from its beginning.
stream_reset:
        lda #0
        sta head
        sta head+1
        sta doc_len
        sta doc_len+1
        sta text_ended
        sta pending
        sta skipping
        lda #1
        sta line_start
        rts

; For paging back: fetches the document again and skips to cur_page. C
; set, with the reason printed, if the fetch fails.
reopen: jsr drop_connection
        jsr request
        bcs @done
        jsr stream_reset
        lda cur_page
        beq @done
        sta skip_left
        lda #1
        sta skipping
:       jsr text_page
        bcs :+              ; ended early: show what's left
        dec skip_left
        bne :-
:       lda #0
        sta skipping
        clc
@done:  rts

; Lets go of the connection without reading the rest of the document:
; IP65 forgets it, and answers anything more the server sends with a reset.
drop_connection:
        lda #0              ; tcp.s's tcp_cxn_state_closed
        sta tcp_state
        rts

; Draws the next page.
draw_text:
        jsr scr_clear
        jsr scr_batch_begin
        jsr text_page
        jmp scr_batch_end

; Draws one page from the stream -- or, while skipping, only counts it.
; Long lines wrap at 80 columns. C set if the document ended on this page.
text_page:
        lda #0
        sta row
        sta col
@byte:  jsr next_byte
        bcs @ended
        cmp #$0D
        beq @byte
        cmp #$0A
        beq @newline
        cmp #$09
        bne :+
        lda #' '
:       cmp #$20
        bcc @byte
        ldx col
        cpx #80
        bcc @put
        pha                 ; wrap
        jsr new_row
        pla
        bcc @put
        sta pending_byte    ; the page is full: this starts the next one
        lda #1
        sta pending
        clc
        rts
@put:   ldx skipping
        bne :+
        pha
        ldx row
        ldy col
        jsr scr_goto
        pla
        jsr scr_putc
:       inc col
        jmp @byte
@newline:
        jsr new_row
        bcc @byte
        clc
        rts
@ended: sec
        rts

; Next row; C set if the page is full.
new_row:
        lda #0
        sta col
        inc row
        lda row
        cmp #TEXT_ROWS
        rts

; The document's next byte in A, C clear; C set once it has ended -- at a
; line of just ".", or when the server closes.
next_byte:
        lda text_ended
        bne @end
        lda pending
        beq :+
        lda #0
        sta pending
        lda pending_byte
        jmp @got
:       jsr next_raw
        bcs @ended
        ldx line_start
        beq @got
        cmp #'.'
        bne @got
        jsr next_raw        ; "." at a line's start: the end, if the line ends there
        bcs @ended
        cmp #$0D
        beq @ended
        cmp #$0A
        beq @ended
        sta pending_byte
        lda #1
        sta pending
        lda #'.'
@got:   ldx #0
        cmp #$0A
        bne :+
        inx
:       stx line_start
        clc
        rts
@ended: lda #1
        sta text_ended
@end:   sec
        rts

; The next byte from the server, waiting for one if need be; C set once
; the server has closed and everything it sent has been read. It reads
; from the network only when everything already received has been used,
; so a whole segment always fits.
next_raw:
@try:   lda head
        cmp doc_len
        lda head+1
        sbc doc_len+1
        bcc @have
        lda done
        bne @end
        lda #0
        sta head
        sta head+1
        sta doc_len
        sta doc_len+1
        jsr ip65_process
        lda $C000           ; ESC stops the transfer; the document ends here
        bpl @try
        and #$7F
        cmp #$1B
        bne @try
        jsr kbd_get
        jsr drop_connection
        lda #1
        sta done
        bne @try
@have:  clc
        lda doc_start
        adc head
        sta zp_a
        lda doc_start+1
        adc head+1
        sta zp_a+1
        inc head
        bne :+
        inc head+1
:       ldy #0
        lda (zp_a),y
        clc
        rts
@end:   sec
        rts

status_text:
        jsr status_line
        lda #<page_label
        ldx #>page_label
        jsr scr_puts
        ldx cur_page
        inx
        txa
        jsr scr_put_dec
        lda text_ended
        beq :+
        lda #<msg_end
        ldx #>msg_end
        jsr scr_puts
:       lda #<text_help
        ldx #>text_help
        jmp scr_puts
