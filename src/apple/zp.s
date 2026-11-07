; Zero page for the utilities: the cells IP65 expects (ptr1-ptr4,
; tmp1-tmp4, sreg -- cc65's runtime names) and our own. The whole block
; lives at $80-$BF, which system.s saves on entry and restores on exit, so
; BASIC and DOS find their zero page as they left it.

.exportzp ptr1, ptr2, ptr3, ptr4, tmp1, tmp2, tmp3, tmp4, sreg
.exportzp zp_a, zp_b, zp_c, zp_t0, zp_t1, zp_t2, zp_t3
.exportzp zp_d, zp_e, zp_f, zp_u0, zp_u1, zp_u2
.exportzp zp_sa, zp_sb, zp_sc, zp_s0, zp_s1, zp_s2, zp_sat
.exportzp drv_init_vec, drv_exit_vec, drv_put_vec, drv_clear_row_vec
.exportzp drv_scroll_vec, drv_cursor_vec, drv_hscroll_vec, drv_clear_span_vec

.zeropage
ptr1:   .res 2
ptr2:   .res 2
ptr3:   .res 2
ptr4:   .res 2
tmp1:   .res 1
tmp2:   .res 1
tmp3:   .res 1
tmp4:   .res 1
sreg:   .res 2
zp_a:   .res 2              ; general pointers
zp_b:   .res 2
zp_c:   .res 2
zp_t0:  .res 1              ; general scratch
zp_t1:  .res 1
zp_t2:  .res 1
zp_t3:  .res 1
zp_d:   .res 2              ; the screen drivers' own
zp_e:   .res 2
zp_f:   .res 2
zp_u0:  .res 1
zp_u1:  .res 1
zp_u2:  .res 1
zp_sa:  .res 2              ; the screen layer's own
zp_sb:  .res 2
zp_sc:  .res 2
zp_s0:  .res 1
zp_s1:  .res 1
zp_s2:  .res 1
zp_sat: .res 2              ; saturn.s's pointer into the card's RAM
; the screen driver's entry points, called through JMP (vector)
drv_init_vec:       .res 2
drv_exit_vec:       .res 2
drv_put_vec:        .res 2
drv_clear_row_vec:  .res 2
drv_scroll_vec:     .res 2
drv_cursor_vec:     .res 2
drv_hscroll_vec:    .res 2
drv_clear_span_vec: .res 2
