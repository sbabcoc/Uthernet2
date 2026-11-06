# Uthernet II Hardware Reference

The Uthernet II, from a2RetroSystems, is a WIZnet W5100 Ethernet
controller on an Apple II slot card. This document describes what the
emulation models and where each fact comes from. Evidence tags:

- **[DS]** WIZnet W5100 Datasheet, version 1.2.4 (2011), read in full.
- **[IP65]** IP65's Uthernet II driver, `drivers/w5100.s`
  (cc65/ip65 commit 25a9c5a), which runs unmodified against this card in
  `Ip65Test`.
- **[UM]** a2RetroSystems, *Uthernet II User's and Programmer's Manual*
  (17 Nov 18), read in full, including the card's schematic (pp. 47-48).
- **[AW]** AppleWin's implementation (`source/Uthernet2.cpp`,
  `source/W5100.h`), read as a cross-check only.

## The card

| Fact | Evidence |
|---|---|
| The slot's DEVSEL drives the W5100's /CS, and the slot's A0 and A1 its ADDR0 and ADDR1, through buffers; A2 and A3 aren't connected. Every $C0n0-$C0nF access therefore reaches the chip, and $C0n0-$C0n3, $C0n4-$C0n7, $C0n8-$C0nB and $C0nC-$C0nF address the same four registers. Software uses $C0n4-$C0n7. | [UM] pp. 10, 47-48; [IP65] |
| The slot's R/W selects /RD or /WR on every DEVSEL access, through a 74LS139 and RC delay networks. | [UM] p. 47 |
| The W5100's /INT drives the slot's /IRQ through a 1G07 open-drain buffer, so it is wire-ORed with other cards'. Software that uses it sets IMR and clears the 6502's I flag. | [UM] pp. 44, 47 |
| The slot's /RESET drives the W5100's /RESET directly, so RESET on the Apple is a hardware reset of the chip. | [UM] pp. 47-48 |
| There is no firmware; $Cn00-$CnFF isn't driven and reads as the floating bus. The card works in any slot. | [UM] pp. 2, 38 |
| The manual calls the card "not fully compatible" with the Apple II and II Plus; its troubleshooting and credits put that down to other cards, accelerators and RFI on the real bus, none of which the emulation has. | [UM] pp. 2, 46, 49 |

## The W5100's bus interface

| Fact | Evidence |
|---|---|
| ADDR14-0 select one of 32 KB; the address pins are pulled down internally. With MR's IND bit clear (direct mode), the card's two address lines therefore reach chip addresses $0000-$0003: MR and GAR0-GAR2. | [DS] 1.1 |
| With IND set, the four addresses are MR, IDM_AR0 (high byte), IDM_AR1 (low byte) and IDM_DR. | [DS] 6.2 |
| With MR's AI bit set, every IDM_DR access steps the indirect address by one. | [DS] 4.1, 6.2 |
| Stepping off the end of TX memory ($6000) wraps to $4000, and off the end of RX memory ($8000) to $6000. IP65 handles the wrap itself, so doesn't depend on it. | [UM] p. 12 |
| The indirect address's top bit reaches no pin: from $8000 up, accesses reach the chip from $0000 ($801A is RMSR). Stepping past $FFFF wraps to $E000; elsewhere above $7FFF the address advances as usual. | [DS] 1.1: ADDR14-0; [UM] p. 13 |
| Writing MR with RST set reinitializes every register; RST then reads back clear (and IND with it). IP65 polls MR's bit 7 after a reset. | [DS] 4.1, [IP65] |
| The indirect address register keeps its value across a software reset, and is 0 after a hardware reset. | [UM] p. 10 |

## Registers

Common registers at $0000-$002F and socket registers at
$0400 + n x $100 follow the datasheet's map and reset values: RTR $07D0,
RCR $08, RMSR and TMSR $55, PTIMER $28, Sn_DHAR $FF x 6, Sn_TTL $80. [DS] 3,
4. IP65 checks RTR's reset value to detect the card. [IP65]

- **RMSR/TMSR** give each socket 1, 2, 4 or 8 KB from socket 0 up; a
  socket whose share doesn't fit in what's left of the 8 KB gets none.
  [DS] 4.1
- **IR** bits 3-0 report which sockets have a nonzero Sn_IR; bits 7-5
  clear by writing 1. **Sn_IR** clears by writing 1. [DS] 4.1, 4.2
- **Sn_CR** reads 0 once the command is taken. [DS] 4.2
- **Sn_RX_RSR** changes only when data arrives and on RECV, which takes
  Sn_RX_RD as the new read point. **Sn_TX_FSR** is the socket's buffer
  size less what lies between Sn_TX_RD and Sn_TX_WR. [DS] 4.2
- Receive headers: UDP 8 bytes (peer address, peer port, size); IPRAW
  6 bytes (peer address, size); MACRAW 2 bytes (size, counting itself).
  [DS] 5.2.2-5.2.4, [IP65] subtracts 2 from the MACRAW size
- MACRAW is socket 0 only; its MF bit limits it to frames for SHAR or
  broadcast. [DS] 4.2

## Where the manual and the datasheet disagree

The model follows the datasheet, the chip maker's description, where the
manual says otherwise:

- **UDP and IPRAW receive headers.** The manual (pp. 35-36) says the
  header's size field counts the header itself. The datasheet's receive
  procedures (5.2.2, 5.2.3) read that many bytes *after* the header and
  advance Sn_RX_RD by size + header, so the size is the payload's alone.
  AppleWin agrees with the datasheet. (For MACRAW the two agree: the size
  counts its own two bytes.)
- **IPRAW header address.** The manual (p. 36) calls it the destination
  address; it is the sender's, the same as UDP's.
- **Clearing Sn_IR.** The manual (p. 45) says to write 0; the datasheet
  (4.2) says bits clear by writing 1, as the manual itself says of IR
  (p. 45).

## What the emulation does differently

These are the places the model stands in for the wire rather than
copying the chip, none visible through the registers:

- In TCP and UDP mode the host's TCP/IP stack does the chip's work, so
  RTR, RCR and Sn_MSS have no effect, SEND_KEEP does nothing, and a
  failure shows up as TIMEOUT whenever the host gives up.
- The chip's own replies to ARP and ping aren't modeled: the only other
  station is the gateway, which never asks.
- Frames wait for receive-buffer room rather than being dropped, since
  the virtual LAN has no wire to lose them on.
- An IPRAW send goes out addressed to the broadcast MAC rather than one
  found by ARP; only the gateway receives it.
- AppleWin's "Virtual DNS" extension (Sn_MR P3 and a host name in socket
  memory) is not supported: it isn't in the real card. Software finds the
  name server through DHCP instead.
