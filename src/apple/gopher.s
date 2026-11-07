; GOPHER: a Gopher client over the Uthernet II.
;
; Menus show each item with a number; text documents page through the
; screen. Keys:
;   menu:  number then RETURN opens an item on the page shown, SPACE and -
;          page forward and back, ESC goes back, G goes to a server, Q quits
;   text:  SPACE and - page, ESC goes back, G and Q as in a menu
; On a Soft-70 screen, the left and right arrows show the left or right
; columns.
;
; Items of type 0 (text), 1 (menu) and 7 (search) open; other types are
; listed but not fetched. Menus and text both stream: each page is drawn
; as it arrives, and the connection waits, unread, while the page is on
; screen, so a menu or document of any length can be read. Paging back
; fetches it again and skips ahead. Menu items are numbered through the
; whole menu; those on the page shown can be opened. With a Saturn card in
; slot 0, everything read is also kept there, with where each page starts,
; so paging back redraws from the Saturn instead of fetching again.

.import sys_start, sys_exit
.import scr_init, scr_putc, scr_puts, scr_crlf, scr_goto, scr_clear
.import scr_clear_eol, scr_hscroll, scr_row, scr_col, scr_put_dec
.import scr_batch_begin, scr_batch_end, scr_put_num4
.import kbd_avail, kbd_get, line_input, net_start, scr_driver
.import dns_set_hostname, dns_resolve, dns_ip
.import tcp_connect, tcp_connect_ip, tcp_callback, tcp_send, tcp_send_data_len, tcp_close
.import tcp_inbound_data_ptr, tcp_inbound_data_length, ip65_process, tcp_state
.import __BSS_RUN__, __BSS_SIZE__
.import sat_detect, sat_ok, sat_seek, sat_put, sat_get
.importzp zp_a, zp_b, zp_c, zp_t0, zp_t1, zp_t2, zp_t3

HOST_SIZE = 64
SEL_SIZE  = 128
FIFO_PAGES = 6              ; a menu's FIFO: room for a whole TCP segment
SAT_SIZE  = 7 * 12 * 1024   ; the Saturn memory saturn.s offers
CACHED_PAGES = 128          ; pages whose starts are recorded
HISTORY   = 6
ENTRY_SIZE = 1 + HOST_SIZE + 2 + SEL_SIZE + 1   ; type, host, port, selector, page
PAGE_ROWS = 22              ; menu lines per page
TEXT_ROWS = 23              ; text lines per page
STATUS_ROW = 23
MAIN_END  = $9600           ; DOS 3.3 and BASIC.SYSTEM both start here
HIRES     = $2000           ; free for documents when the VideoTerm is the screen
HIRES_END = $4000

.segment "STARTUP"
        jmp main

.segment "LOWBSS"
history:    .res HISTORY * ENTRY_SIZE
line_lo:    .res PAGE_ROWS  ; the page store: where each line starts
line_hi:    .res PAGE_ROWS
item_lo:    .res PAGE_ROWS  ; each line's item number, 0 for an info line
item_hi:    .res PAGE_ROWS
input:      .res SEL_SIZE
; where each page starts in the stream, and the parser's state there
page_pos0:  .res CACHED_PAGES
page_pos1:  .res CACHED_PAGES
page_pos2:  .res CACHED_PAGES
page_items_lo: .res CACHED_PAGES
page_items_hi: .res CACHED_PAGES
page_flags: .res CACHED_PAGES   ; bit 0 line_start, bit 1 pending
page_pend:  .res CACHED_PAGES
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
line_count: .res 1          ; lines on this menu page
item_count: .res 2          ; items so far in this menu
typed:      .res 1          ; digits typed
digits:     .res 5          ; the item number being typed
row:        .res 1
col:        .res 1
; the stream: unread data runs from head to doc_len above fifo_base
fifo_base:  .res 2
cache_ok:   .res 1          ; nonzero while this document is kept in the Saturn
cache_len:  .res 3          ; bytes of it kept
pos:        .res 3          ; the stream position next_raw reads next
next_page:  .res 1          ; the page the stream is at the start of
pages_known: .res 1         ; pages whose starts are recorded
head:       .res 2
menu_ended: .res 1          ; a menu's last line has been read
store_ptr:  .res 2          ; where the next menu line goes in the page store
line_len:   .res 1
line_first: .res 1
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
msg_anykey: .byte " Press a key.", 0
msg_cant:   .byte "Can't open this type of item. Press a key.", 0
msg_search: .byte "Search for: ", 0
menu_help:  .byte "  RET open SPC/- page ESC back G go Q quit", 0
text_help:  .byte "  SPC/- page  ESC back  G go  Q quit", 0
page_label: .byte "Page ", 0
page_label_menu: .byte " Page ", 0
msg_end:    .byte "  [end]", 0
item_label: .byte "Item ", 0
prefix:     .byte "gopher://"
PREFIX_LEN = * - prefix

.code

main:
        jsr sys_start
        jsr scr_init
        jsr sat_detect
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
        jsr begin_stream
        jsr request
        bcs open_failed
        lda cur_type
        cmp #'0'
        bne :+
        jmp show_text
:       jmp show_menu

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
        lda fifo_base
        adc doc_len
        sta zp_b
        lda fifo_base+1
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

; ---------------------------------------------------------------------
; menus: streamed a page at a time, as text is. A page is up to 22 lines,
; kept in the page store -- the document buffer below the FIFO -- so its
; items can be opened. Items are numbered through the whole menu; only
; the page on screen can be opened by number.

show_menu:
        lda cur_page        ; history may return us to a later page
        sta skip_left
        lda #0
        sta cur_page
        lda skip_left
        beq @page
        lda #1
        sta skipping
:       jsr menu_page
        bcs @short
        inc cur_page
        dec skip_left
        bne :-
        lda #0
        sta skipping
        beq @page
@short: lda #0              ; the menu is shorter now: start at its top
        sta skipping
        sta cur_page
        jsr menu_reopen
        bcc @page
        jmp open_failed
@page:  jsr draw_menu
@new:   lda #0
        sta typed
        sta digits
@key:   jsr status_menu
        jsr kbd_get
        cmp #$0D
        bne :+
        jmp @open
:       cmp #' '
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
@next:  lda menu_ended
        bne @key
        lda cur_page
        cmp #255
        beq @key
        inc cur_page
        jmp @page
@prev:  lda cur_page
        beq @key
        dec cur_page
        jsr replay_page
        bcc @page
        jsr menu_reopen
        bcc @page
        jmp open_failed
@left:  lda typed           ; erases a digit, or shows the left columns
        beq :+
        dec typed
        ldx typed
        lda #0
        sta digits,x
        jmp @key
:       lda #0
        jsr scr_hscroll
        jmp @key
@right: lda #1
        jsr scr_hscroll
        jmp @key
@back:  jsr pop_history
        bcc :+
        jmp @key
:       jsr drop_connection
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
@open:  lda typed
        bne :+
        jmp @key
:       jsr open_item       ; returns only if the item can't be opened
        jsr redraw_menu
        jmp @new

; For paging back: fetches the menu again and skips to cur_page. C set,
; with the reason printed, if the fetch fails.
menu_reopen:
        jsr drop_connection
        jsr begin_stream
        jsr request
        bcs @done
        lda cur_page
        beq @done
        sta skip_left
        lda #1
        sta skipping
:       jsr menu_page
        bcs :+
        dec skip_left
        bne :-
:       lda #0
        sta skipping
        clc
@done:  rts

add_digit:
        ldx typed
        cpx #4
        bcs @done
        ora #'0'
        sta digits,x
        inx
        stx typed
        lda #0
        sta digits,x
@done:  rts

; Reads the next page of the menu into the page store -- or, while
; skipping, only counts its items. C set if the menu had already ended.
menu_page:
        jsr page_begin
        lda #0
        sta line_count
        lda doc_start
        sta store_ptr
        lda doc_start+1
        sta store_ptr+1
@line:  lda line_count
        cmp #PAGE_ROWS
        bcs @full
        sec                 ; under 256 bytes left: the page ends here
        lda fifo_base
        sbc store_ptr
        lda fifo_base+1
        sbc store_ptr+1
        beq @full
        ldx line_count
        lda store_ptr
        sta line_lo,x
        lda store_ptr+1
        sta line_hi,x
        jsr read_line
        bcs @ended
        ldx line_count
        lda #0              ; info lines, blank lines and errors aren't items
        sta item_lo,x
        sta item_hi,x
        lda line_len
        beq @counted
        lda line_first
        cmp #'i'
        beq @counted
        cmp #'3'
        beq @counted
        inc item_count
        bne :+
        inc item_count+1
:       lda item_count
        sta item_lo,x
        lda item_count+1
        sta item_hi,x
@counted:
        inc line_count
        jmp @line
@full:  clc
        rts
@ended: lda line_count
        cmp #1              ; C set if this page got nothing
        rts

; Reads the next line of the menu into the store at store_ptr (unless
; skipping) without its CR and LF, ending it with a 0. C set if the menu
; has ended: the server closed, or the line was a lone ".".
read_line:
        lda menu_ended
        bne @end
        lda #0
        sta line_len
        sta line_first
@byte:  jsr next_raw
        bcs @close
        cmp #$0D
        beq @byte
        cmp #$0A
        beq @eol
        ldx line_len
        bne :+
        sta line_first
:       inx
        beq :+              ; past 255 the count stops; the store takes what fits
        stx line_len
:       ldx skipping
        bne @byte
        jsr store_byte
        jmp @byte
@close: lda line_len        ; the server closed: a line in progress is the last
        beq @end
@eol:   lda line_len
        cmp #1
        bne :+
        lda line_first
        cmp #'.'
        beq @end
:       ldx skipping
        bne :+
        lda #0
        jsr store_end
:       clc
        rts
@end:   lda #1
        sta menu_ended
        sec
        rts

; Stores A at store_ptr, if there's room below the one byte kept for the
; line's ending 0.
store_byte:
        tax
        clc
        lda store_ptr
        adc #1
        tay
        lda store_ptr+1
        adc #0
        cpy fifo_base
        sbc fifo_base+1
        bcs store_skip      ; a long line is cut short
        txa
store_end:
        pha
        lda store_ptr
        sta zp_b
        lda store_ptr+1
        sta zp_b+1
        pla
        ldy #0
        sta (zp_b),y
        inc store_ptr
        bne store_skip
        inc store_ptr+1
store_skip:
        rts

; Reads the next page and shows it.
draw_menu:
        jsr scr_clear
        jsr scr_batch_begin
        jsr menu_page
        jsr show_store
        jmp scr_batch_end

; Shows the page already in the store again.
redraw_menu:
        jsr scr_clear
        jsr scr_batch_begin
        jsr show_store
        jmp scr_batch_end

show_store:
        lda #0
        sta row
@row:   lda row
        cmp line_count
        bcs @done
        tax
        ldy #0
        jsr scr_goto
        ldx row
        lda line_lo,x
        sta zp_b
        lda line_hi,x
        sta zp_b+1
        lda item_lo,x
        ora item_hi,x
        beq @info
        lda item_lo,x
        pha
        lda item_hi,x
        tax
        pla
        jsr scr_put_num4
        lda #' '
        jsr scr_putc
        jmp @text
@info:  lda #<five_spaces
        ldx #>five_spaces
        jsr scr_puts
@text:  ldy #1              ; the display string: after the type, up to a tab
@char:  lda (zp_b),y
        beq @eol
        cmp #$09
        beq @eol
        sty zp_t1
        jsr scr_putc
        ldy zp_t1
        lda scr_col
        cmp #79
        bcs @eol
        iny
        bne @char
@eol:   inc row
        jmp @row
@done:  rts

five_spaces: .byte "     ", 0


status_menu:
        ldx #STATUS_ROW
        ldy #0
        jsr scr_goto
        jsr scr_clear_eol
        lda #<item_label
        ldx #>item_label
        jsr scr_puts
        lda #<digits
        ldx #>digits
        jsr scr_puts
        lda #'_'
        jsr scr_putc
        lda #<page_label_menu
        ldx #>page_label_menu
        jsr scr_puts
        ldx cur_page
        inx
        txa
        jsr scr_put_dec
        lda menu_ended
        beq :+
        lda #<msg_end
        ldx #>msg_end
        jsr scr_puts
:       lda #<menu_help
        ldx #>menu_help
        jsr scr_puts
        ldx #STATUS_ROW     ; leave the cursor after the digits
        lda typed
        clc
        adc #5
        tay
        jmp scr_goto

; Opens the item whose number has been typed, if it's on this page;
; returns only if it isn't, or can't be opened.
open_item:
        lda #<digits
        sta zp_a
        lda #>digits
        sta zp_a+1
        jsr parse_number
        sta zp_t0
        stx zp_t1
        ldx #0
@find:  cpx line_count
        bcs @none
        lda item_lo,x
        cmp zp_t0
        bne :+
        lda item_hi,x
        cmp zp_t1
        beq @found
:       inx
        bne @find
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
        jsr drop_connection
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

; Z set if A ends a field: tab, CR, LF, or the 0 that ends a stored line.
field_end:
        cmp #0
        beq :+
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
        jsr replay_page
        bcc @page
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

; Sets up to read cur_type's document from its beginning. It's called
; before the request goes out: IP65 delivers whatever arrives while
; tcp_send waits for its acknowledgement, and a server may send its first
; data with that acknowledgement. Text uses the whole buffer as its FIFO;
; a menu keeps its page store below a FIFO at the top.
begin_stream:
        lda #0
        sta pos
        sta pos+1
        sta pos+2
        sta cache_len
        sta cache_len+1
        sta cache_len+2
        sta next_page
        sta pages_known
        lda sat_ok
        sta cache_ok
        lda #0
        sta head
        sta head+1
        sta text_ended
        sta pending
        sta skipping
        sta menu_ended
        sta item_count
        sta item_count+1
        lda #1
        sta line_start
        lda cur_type
        cmp #'0'
        bne :+
        lda doc_start
        sta fifo_base
        lda doc_start+1
        sta fifo_base+1
        rts
:       lda #0
        sta fifo_base
        lda doc_end_page
        sec
        sbc #FIFO_PAGES
        sta fifo_base+1
        rts

; For paging back: fetches the document again and skips to cur_page. C
; set, with the reason printed, if the fetch fails.
reopen: jsr drop_connection
        jsr begin_stream
        jsr request
        bcs @done
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
        jsr page_begin
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

; The stream's next byte; C set at its end. While the document is being
; kept in the Saturn, a position already read comes back from there, and
; a new byte from the server is added to it. The Saturn's 84K is enough
; for most documents; past that, the rest isn't kept, and paging back
; fetches the document again.
next_raw:
        lda cache_ok
        bne :+
        jmp net_raw
:       lda pos             ; read before?
        cmp cache_len
        lda pos+1
        sbc cache_len+1
        lda pos+2
        sbc cache_len+2
        bcs @fresh
        lda pos
        ldx pos+1
        ldy pos+2
        jsr sat_seek
        jsr sat_get
        jmp @advance
@fresh: jsr net_raw
        bcs @done
        pha
        lda cache_len       ; room for it?
        cmp #<SAT_SIZE
        lda cache_len+1
        sbc #>SAT_SIZE
        lda cache_len+2
        sbc #^SAT_SIZE
        bcs @full
        lda cache_len
        ldx cache_len+1
        ldy cache_len+2
        jsr sat_seek
        pla
        pha
        jsr sat_put
        inc cache_len
        bne :+
        inc cache_len+1
        bne :+
        inc cache_len+2
:       pla
@advance:
        inc pos
        bne :+
        inc pos+1
        bne :+
        inc pos+2
:       clc
@done:  rts
@full:  lda #0              ; no more room: stop keeping this document
        sta cache_ok
        pla
        clc
        rts

; At the start of each page built from the stream: records where it
; starts, the first time, then counts it.
page_begin:
        lda cache_ok
        beq @count
        ldx next_page
        cpx pages_known
        bne @count
        cpx #CACHED_PAGES
        bcs @count
        lda pos
        sta page_pos0,x
        lda pos+1
        sta page_pos1,x
        lda pos+2
        sta page_pos2,x
        lda item_count
        sta page_items_lo,x
        lda item_count+1
        sta page_items_hi,x
        lda pending
        beq :+
        lda #2
:       ora line_start
        sta page_flags,x
        lda pending_byte
        sta page_pend,x
        inc pages_known
@count: inc next_page
        rts

; Rewinds the stream to the start of page cur_page, if it's in the Saturn;
; C set if it isn't, and the document has to be fetched again.
replay_page:
        lda cache_ok
        beq @no
        ldx cur_page
        cpx pages_known
        bcs @no
        stx next_page
        lda page_pos0,x
        sta pos
        lda page_pos1,x
        sta pos+1
        lda page_pos2,x
        sta pos+2
        lda page_items_lo,x
        sta item_count
        lda page_items_hi,x
        sta item_count+1
        lda page_flags,x
        and #1
        sta line_start
        lda page_flags,x
        lsr
        and #1
        sta pending
        lda page_pend,x
        sta pending_byte
        lda #0
        sta text_ended
        sta menu_ended
        clc
        rts
@no:    sec
        rts

; The next byte from the server, waiting for one if need be; C set once
; the server has closed and everything it sent has been read. It reads
; from the network only when everything already received has been used,
; so a whole segment always fits.
net_raw:
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
        lda fifo_base
        adc head
        sta zp_a
        lda fifo_base+1
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
