This repository contains register-transfer level (RTL) implementations
of building blocks for a lossy compressor based on principal component
analysis (PCA), written in the Chisel hardware construction
language. The core of the PCA-based lossy compressor is a
vector–matrix computation, which can be very large. This
implementation leverages tree-based reduction and reduced-precision
arithmetic (lower-precision integer values) to optimize the resource
usage. It can be applied to on-chip or near-sensor processing where
spatially localized digital logic is required.

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
[info] welcome to sbt 1.8.0 (Ubuntu Java 17.0.17)
[info] loading settings for project pca-comp-chisel-build from plugins.sbt ...
[info] loading project definition from /home/kazutomo/gitwork/pca-comp-chisel/project
[info] loading settings for project root from build.sbt ...
[info] set current project to pca-comp (in build file:/home/kazutomo/gitwork/pca-comp-chisel/)
Design: nrows168_ncols192_nblocks8_w24_pxbw12_iembw8_npcs100 SRAM: depth168_width192
```

Note: Verilog files are generated inside the generated directory



Please contact Kazutomo Yoshii <kazutomo@anl.gov> if you have any question.

