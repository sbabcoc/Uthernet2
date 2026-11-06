# TODO

- **Answer pings to hosts beyond the gateway.** ICMP echo needs raw or
  ICMP datagram sockets, which plain Java doesn't offer portably; only
  the gateway's own addresses answer now.
- **Accept connections into MACRAW guests.** A guest running its own TCP
  stack can open connections out but can't accept them; TCP mode's LISTEN
  can.
