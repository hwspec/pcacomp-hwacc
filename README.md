This repository contains register-transfer level (RTL) implementations
of building blocks for a lossy compressor based on principal component
analysis (PCA), written in the Chisel hardware construction
language. The core of the PCA-based lossy compressor is a
vector–matrix computation, which can be very large. This
implementation leverages tree-based reduction and reduced-precision
arithmetic (lower-precision integer values) to optimize the resource
usage. It can be applied to on-chip or near-sensor processing where
spatially localized digital logic is required.

See [`docs/implementation.md`](docs/implementation.md) for how the
design works and how it is tested.

### Dependencies

#### JDK 8 or newer

We recommend LTS releases Java 8 and Java 11. You can install the JDK as recommended by your operating system, or use the prebuilt binaries from [AdoptOpenJDK](https://adoptopenjdk.net/).

#### SBT or mill

SBT is the most common built tool in the Scala community. You can download it [here](https://www.scala-sbt.org/download.html).  
mill is another Scala/Java build tool without obscure DSL like SBT. You can download it [here](https://github.com/com-lihaoyi/mill/releases)

### To run tests

```bash
$ make test
```

### To generate verilog

```bash
$ make gen
```

This runs the `large` preset configuration (see `docs/implementation.md`
for other presets and `PCAConfig` options) and writes the generated
SystemVerilog to the `generated/` directory.

### FPGA / cocotb testing (GarageWorks)

`AxiPCACompBlock` (the AXI4-Lite wrapper) is also tested with
[GarageWorks](https://github.com/hwspec/garageworks), which runs the same
cocotb testbench in Verilator simulation and on FPGA. Project settings are
in `.gwconfig`; the testbench and bridge live in `gwtests/`.

```bash
$ pip install garageworks
$ gws          # run the prereq -> rtlgen -> cocotb stages in simulation
$ gws --fpga   # also run the FPGA stages (needs Vivado/Vitis and an AVED board)
```

See `gws --help` and the GarageWorks docs for more.



Please contact Kazutomo Yoshii <kazutomo@anl.gov> if you have any question.

