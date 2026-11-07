# Builds the Apple II utilities and the two disk images that carry them.
#
# Needs:
#   cc65 (https://github.com/cc65/cc65) on the PATH
#   an IP65 checkout (https://github.com/cc65/ip65) with its TCP library
#   and Uthernet II driver built:
#     make -C $IP65/ip65 ip65_tcp.lib
#     make -C $IP65/drivers a2uther2.lib
#   AppleCommander's command-line jar (https://applecommander.github.io/)
#
#   make IP65=/path/to/ip65 AC=/path/to/AppleCommander-ac.jar
#
# tables.s is generated from the Tom Thumb font by gentables.py; it's
# checked in, so building needs neither the script nor the font.

IP65 ?= ../../../ip65
AC   ?= AppleCommander-ac.jar
OUT  = disks
TEST = ../test/resources

COMMON = zp.o system.o screen.o vterm.o soft70.o tables.o kbd.o lineedit.o netinit.o
LIBS   = $(IP65)/ip65/ip65_tcp.lib $(IP65)/drivers/a2uther2.lib

all: $(OUT)/uthernet2-dos33.dsk $(OUT)/uthernet2-prodos.po $(TEST)/telnet.bin $(TEST)/gopher.bin

%.o: %.s
	ca65 --cpu 6502 -o $@ $<

telnet.bin: telnet.o $(COMMON)
	ld65 -C apple2.cfg -m telnet.map -o $@ telnet.o $(COMMON) $(LIBS)

gopher.bin: gopher.o $(COMMON)
	ld65 -C apple2.cfg -m gopher.map -o $@ gopher.o $(COMMON) $(LIBS)

$(OUT)/uthernet2-dos33.dsk: telnet.bin gopher.bin
	mkdir -p $(OUT)
	rm -f $@
	java -jar $(AC) -dos140 $@
	java -jar $(AC) -p $@ TELNET B 0x4000 < telnet.bin
	java -jar $(AC) -p $@ GOPHER B 0x4000 < gopher.bin

$(OUT)/uthernet2-prodos.po: telnet.bin gopher.bin
	mkdir -p $(OUT)
	rm -f $@
	java -jar $(AC) -pro140 $@ UTHERNET2
	java -jar $(AC) -p $@ TELNET BIN 0x4000 < telnet.bin
	java -jar $(AC) -p $@ GOPHER BIN 0x4000 < gopher.bin

# The tests run the programs themselves.
$(TEST)/%.bin: %.bin
	cp $< $@

clean:
	rm -f *.o *.map telnet.bin gopher.bin

.PHONY: all clean
