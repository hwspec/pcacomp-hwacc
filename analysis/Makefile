SIM ?= verilator
TOPLEVEL_LANG ?= verilog
VERILOG_SOURCES += ../generated/VMulRed.sv

all: test

../generated/VMulRed.sv: ../build.sbt
	sbt 'runMain pca.GenPCA'

test: ../generated/VMulRed.sv
	$(MAKE) sim MODULE=testVMulRed TOPLEVEL=VMulRed

include $(shell cocotb-config --makefiles)/Makefile.sim

distclean:
	rm -f *~
	rm -f results.xml
	rm -rf __pycache__
	rm -rf sim_build
