# Builds the Apple II utilities and the two disk images that carry them.
#
# Needs:
#   cc65 (https://github.com/cc65/cc65) on the PATH
#   an IP65 checkout (https://github.com/cc65/ip65) with its TCP library
#   and Uthernet II driver built:
#     make -C $IP65/ip65 ip65_tcp.lib
#     make -C $IP65/drivers a2uther2.lib
#   AppleCommander's command-line tool, ac (https://applecommander.github.io/),
#   for the disk images only. The same on macOS, Linux/Termux and Windows
#   (Git Bash or MSYS2), found in this order:
#     1. AC, if given: the whole command, e.g. AC=/path/to/ac
#     2. ac on the PATH (a native build; ac.exe on Windows)
#     3. AC_JAR, if given: the jar, run as java -jar "$AC_JAR"
#   AC_JAR is best set once in the shell's profile (~/.zprofile on macOS,
#   ~/.bashrc in Git Bash), with forward slashes on Windows:
#     export AC_JAR=C:/tools/AppleCommander-ac.jar
#
#   make IP65=/path/to/ip65
#
# tables.s is generated from s70font.txt (a2lib's Soft-70 font) by
# gentables.py; it's checked in, so building needs neither the script nor
# Python.

IP65 ?= ../../../ip65
ifeq ($(origin AC),undefined)
  ifneq ($(shell command -v ac 2>/dev/null),)
    AC := ac
  else ifneq ($(AC_JAR),)
    AC := java -jar "$(AC_JAR)"
  endif
endif
OUT  = disks
TEST = ../test/resources

COMMON = zp.o system.o screen.o vterm.o soft70.o tables.o kbd.o lineedit.o netinit.o saturn.o
LIBS   = $(IP65)/ip65/ip65_tcp.lib $(IP65)/drivers/a2uther2.lib

all: $(OUT)/uthernet2-dos33.dsk $(OUT)/uthernet2-prodos.po $(TEST)/telnet.bin $(TEST)/gopher.bin

%.o: %.s
	ca65 --cpu 6502 -o $@ $<

telnet.bin: telnet.o $(COMMON)
	ld65 -C apple2.cfg -m telnet.map -o $@ telnet.o $(COMMON) $(LIBS)

gopher.bin: gopher.o $(COMMON)
	ld65 -C apple2.cfg -m gopher.map -o $@ gopher.o $(COMMON) $(LIBS)

$(OUT)/uthernet2-dos33.dsk: telnet.bin gopher.bin | ac-check
	mkdir -p $(OUT)
	rm -f $@
	$(AC) -dos140 $@
	$(AC) -p $@ TELNET B 0x4000 < telnet.bin
	$(AC) -p $@ GOPHER B 0x4000 < gopher.bin

$(OUT)/uthernet2-prodos.po: telnet.bin gopher.bin | ac-check
	mkdir -p $(OUT)
	rm -f $@
	$(AC) -pro140 $@ UTHERNET2
	$(AC) -p $@ TELNET BIN 0x4000 < telnet.bin
	$(AC) -p $@ GOPHER BIN 0x4000 < gopher.bin

# The tests run the programs themselves.
$(TEST)/%.bin: %.bin
	cp $< $@

# Stops with directions, before a disk is touched, when there's no AppleCommander.
ac-check:
ifeq ($(strip $(AC)),)
	$(error AppleCommander not found: put its ac on the PATH, or set AC_JAR to its jar, e.g. AC_JAR=/path/to/AppleCommander-ac.jar)
endif

clean:
	rm -f *.o *.map telnet.bin gopher.bin

.PHONY: all clean ac-check
