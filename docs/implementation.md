# PCA Compressor Hardware: Implementation Notes

This document describes the Chisel sources under `src/`: what each module
does, how data moves through the main datapath (`PCACompBlock`), and how the
design is tested.

## 1. What the hardware computes

PCA-based compression projects an input frame onto the first `m` principal
components. Flatten an `h x w` frame of unsigned pixels into a vector `x` of
length `n = w*h`, and let `E` be the `n x m` (quantized, signed integer)
encoding matrix. The compressed output is

```
y[k] = sum_{j=0}^{n-1} x[j] * E[j][k]        for k = 0 .. m-1
```

so it is one large vector-matrix product. The hardware splits the work two
ways:

* **Column blocks.** The `w` columns are divided into `nblocks` equal-width
  slices (`width = w / nblocks`). Each slice is handled by its own
  `PCACompBlock` instance, which produces a *partial* `y` over its columns.
  Adding the partial results from all blocks gives the full `y`.
* **Rows over time.** A block takes one row of its slice (`width` pixels) per
  cycle and accumulates over the `h` rows of a frame.

In the code, the encoding matrix is called the *IEM* ("inverse encoding
matrix"). It is stored on chip and loaded beforehand.

## 2. Source layout

| File | Contents |
|---|---|
| `src/main/scala/pca/PCAConfig.scala` | `PCAConfig` case class and `PCAConfigPresets` |
| `src/main/scala/pca/PCACompBlock.scala` | Main datapath module, plus Verilog-generation entry points (`PCACompBlock*` objects, including the JSON loader) |
| `src/main/scala/pca/SRAM1RW.scala` | Single-port SRAM wrapper: either a behavioural `SyncReadMem` or a black box |
| `src/main/scala/pca/PCACompTop.scala` | Empty placeholder for a future top level |
| `src/main/scala/common/GenVerilog.scala` | Wrapper around `ChiselStage.emitSystemVerilogFile` |
| `src/main/scala/scratch/VMulRed.scala` | Experiments with multiply-reduce and adder-tree styles |
| `src/main/scala/scratch/JustMemBlock.scala` | Bare `SyncReadMem` used for memory-mapping experiments |
| `src/test/scala/pca/PCATestData.scala` | Golden model and test-data generator |
| `src/test/scala/pca/PCAConfigSpec.scala` | Self-check of the golden model |
| `src/test/scala/pca/PCACompBlockSpec.scala` | ChiselSim tests for `PCACompBlock` |
| `src/test/scala/pca/ExportTestVectors.scala` | Writes test vectors to JSON for external (cocotb) simulation |
| `src/test/scala/scratch/VMulRedSpec.scala` | ChiselSim tests for the scratch reduction modules |

## 3. Configuration (`PCAConfig`)

```scala
case class PCAConfig(w: Int = 12, h: Int = 3, pxbw: Int = 9,
                     m: Int = 7, encbw: Int = 8,
                     nblocks: Int = 3,
                     seed: Option[Int] = None,
                     nonegative: Boolean = false)
```

| Field | Meaning | Name in `PCACompBlock` |
|---|---|---|
| `w`, `h` | Frame width (columns) and height (rows) | `ncols`, `nrows` |
| `pxbw` | Pixel bit width (pixels are unsigned) | `pxbw` |
| `m` | Number of principal components | `nmaxpcs` |
| `encbw` | IEM coefficient bit width (signed) | `iembw` |
| `nblocks` | Number of column blocks; `w % nblocks == 0` is required | used to derive `width` |
| `seed` | RNG seed for test data only (`None` means non-deterministic) | not used by hardware |
| `nonegative` | Test data only: draw IEM values from `[0, 2^(encbw-1))` | not used by hardware |

Presets:

| Preset | w x h | m | pxbw | nblocks | Block width |
|---|---|---|---|---|---|
| `default` | 12 x 3 | 7 | 9 | 3 | 4 |
| `small` | 12 x 2 | 2 | 5 | 3 | 4 (seed 123, non-negative) |
| `medium` | 192 x 168 | 50 | 12 | 8 | 24 |
| `large` | 192 x 168 | 100 | 12 | 8 | 24 |
| `cfg1` | 24 x 24 | 25 | 12 | 4 | 6 |

Each `configs/*.json` file holds the same fields. Missing keys fall back to
the `PCAConfig` defaults.

## 4. `PCACompBlock`: the datapath

### 4.1 Derived widths

```
width  = w / nblocks                         pixels per row for this block
mulbw  = pxbw + iembw                        one signed product
redbw  = mulbw + log2Ceil(width) + log2Ceil(nrows)   output element width
busbw  = width * iembw                       one IEM row word
accbw  = redbw + log2Ceil(nrows)             accumulator width
```

`redbw` is enough to hold a sum of `width * nrows` products without overflow,
so it is the width of each output element. `accbw` adds a further
`log2Ceil(nrows)` bits of headroom. That is more than needed, and the result
is truncated back to `redbw` at output.

Module names encode the configuration, for example
`PCACompBlock_nrows168_ncols192_nblocks8_w24_pxbw12_iembw8_npcs100`.
`simulate.py` depends on this naming scheme.

### 4.2 Ports

| Port | Dir | Width | Purpose |
|---|---|---|---|
| `rowid` | in | `log2Ceil(nrows)` | Row index. Used as the SRAM address in every mode |
| `indata` | in | `width*pxbw` | One row of pixels, pixel 0 in the LSBs |
| `indatavalid` | in | 1 | `indata` is valid this cycle (compute mode) |
| `out` | out | Decoupled, `m*redbw` | Compressed partial vector, component 0 in the LSBs |
| `updateIEM` | in | 1 | Write `iemdata` to IEM memory `iempos` at row `rowid` |
| `verifyIEM` | in | 1 | Read back IEM memory `iempos` on `iemdataverify` |
| `iempos` | in | `log2Ceil(m)` | Selects the principal component (memory bank) |
| `iemdata` | in | `width*iembw` | One IEM row slice, coefficient 0 in the LSBs |
| `iemdataverify` | out | `width*iembw` | Read-back data |

There is no back-pressure on the input side: `indata` is accepted
unconditionally whenever `indatavalid` is high.

### 4.3 Operating modes

The module has three modes, in priority order:

1. **`updateIEM`**: writes one word into bank `iempos` at address `rowid`.
   Loading a full block takes `h * m` cycles.
2. **`verifyIEM`**: drives `iemdataverify` with `memrdata(iempos)`. The SRAM
   read takes one cycle, so the data shown is for the `rowid` presented on
   the *previous* cycle.
3. **Compute** (neither flag set): if `indatavalid` is high, `indata` is
   latched into `indataHoldReg` and the pipeline is started.

### 4.4 IEM storage

```
mems(k) : SRAM1RW(depth = nrows, width = width*iembw),  k = 0 .. m-1
```

Each principal component has its own memory bank. Row `r` of bank `k` holds
the `width` coefficients `E[r*w + blockoffset + i][k]`. All banks share the
address `rowid`, so every cycle the block reads one IEM row for *all* `m`
components in parallel. That is what lets the block compute all `m` dot
products in one pass. Total storage per block is
`m * h * width * iembw` bits. For example, `large` needs
100 x 168 x 24 x 8 bits, about 3.2 Mbit per block.

### 4.5 Compute pipeline

Rows can be streamed back to back, one per cycle. For a row presented with
`indatavalid` at cycle `t`:

| Cycle | Stage flag | Action |
|---|---|---|
| t | — | SRAM read of all banks at `rowid`. `indata` is latched into `indataHoldReg` |
| t+1 | `dataReceivedStageReg` | `multiplied(k)(x) := fromiem(k)(x) * indatavec(x)`: `m*width` signed x unsigned multipliers, registered |
| t+2 | `multipliedStageReg` | `partialcompressed(k) := multiplied(k).reduce(_ +& _)`: per-component row sum, registered |
| t+3 | `reducedStageReg` | `compressedAccReg(k) += partialcompressed(k)`. If `rowidDelayed == nrows-1`, the sum is truncated to `redbw`, written to `compressedReg`, the accumulator is cleared, and `enqOutQReg` is set |
| t+4 | `enqOutQReg` | `compressedReg` is pushed into a 4-entry `Queue` once `enq.ready` is high |
| t+5 | — | Result appears on `io.out` (the `Queue` adds one cycle of latency) |

Notes on the pipeline:

* **End of frame.** The frame boundary is detected only from `rowid`.
  `rowidDelayed = ShiftRegister(io.rowid, 3)` lines the row index up with
  the reduce stage. The row with `rowid == nrows-1` closes a frame, and the
  next row starts a new accumulation. Rows must arrive in order `0 .. nrows-1`
  with no rows missing.
* **Output packing.** `lastCompressedAcc.asTypeOf(UInt)` places component 0
  in the LSBs. `PCATestData.convBigInt2Array` decodes the same layout.
* **Reduction structure.** `Vec.reduce` is Scala's left fold, so the RTL
  describes a *linear* chain of `width-1` adders, not a balanced tree.
  Synthesis may rebalance it. `reduceTree` would describe a tree explicitly,
  and `scratch/VMulRed.scala` compares the two styles. Intermediate widths
  grow by one bit per `+&` and are truncated to `redbw` on assignment. The
  truncation is safe because the true value always fits.
* **Multiplier width.** `SInt * UInt` in Chisel gives `iembw + pxbw + 1`
  bits. Storing into `multiplied` (`pxbw + iembw` bits) drops a redundant
  sign bit.
* **Debug output.** `debugprint` defaults to `true`. It emits `printf`s for
  every stage, driven by a free-running 10-bit `clk` counter. The tests turn
  it off. `GenVerilog` does not strip debug info, so the generated Verilog
  keeps these `printf`s when `debugprint` is on.

### 4.6 Caveats in the current RTL

* `dataReceivedStageReg` is only assigned in the compute branch. If
  `indatavalid` was high on the last cycle before `updateIEM` or `verifyIEM`
  goes high, the flag stays high and the pipeline keeps firing on stale data.
  To avoid this, deassert `indatavalid` for at least one cycle before
  switching modes. The tests do this.
* `outQ` back-pressure only delays `enqOutQReg`. If a new frame completes
  while the previous result is still waiting to enter a full queue,
  `compressedReg` is overwritten.
* All SRAMs have `en` tied high and are read every cycle. With
  `SyncReadMem`, a read that coincides with a write is undefined-on-collision,
  but the read result is not used during `updateIEM`.
* There is no hardware that combines results across blocks. Adding the
  `nblocks` partial vectors is left to the integration level. `PCACompTop` is
  an empty stub with unused parameters (`iemfloat`, `iemexp`).

## 5. `SRAM1RW`

A single-port memory with a one-cycle read latency (`en`, `we`, `addr`,
`wdata`, `rdata`), in two variants:

* **`useSyncReadMem = true`** (default). Uses a Chisel `SyncReadMem`. Module
  name: `SRAM1RW__depth{D}_width{W}_simulation`. firtool lowers it to
  `mem_*.sv` or `ram_*.sv`.
* **`useSyncReadMem = false`**. Instantiates `BlackBoxSRAM1RW`, an
  `ExtModule` named `SRAM1RW_{D}x{W}` with an inline behavioural Verilog
  model. In this variant `rdata` is not updated on write cycles. This is the
  hook for swapping in a foundry SRAM macro.

## 6. Scratch modules (`src/main/scala/scratch`)

These modules are for design exploration and resource estimation, and are
not part of the main datapath.

* `LocalRedStage` / `LocalRedRec`: an explicit binary adder tree built from
  stages, with each stage one bit wider (power-of-two `n` only).
* `LocalRedBuiltIn`: the same function written as `reduce(_ +& _)`. Used to
  compare synthesis results (see `estimate/` and `analysis/yosys-*.sh`).
* `CompareRed`: instantiates both versions so they can be checked against
  each other.
* `VMulRed`: a combinational dot product of `n` unsigned pixels and `n`
  signed coefficients.
* `JustMemBlock`: a single `SyncReadMem`, used to check how memories map.

## 7. Generating Verilog

`GenVerilog` calls `ChiselStage.emitSystemVerilogFile` with output to
`generated/`, `--disable-all-randomization`, and
`--lowering-options=disallowLocalVariables,disallowPackedArrays` for
compatibility with older tools.

| Command | Generates |
|---|---|
| `make gen` | `pca.PCACompBlockLarge` |
| `sbt 'runMain pca.PCACompBlock{,Small,Medium,Large,Cfg1}'` | Preset configurations |
| `sbt 'runMain pca.PCACompBlockJson configs/foo.json'` | A configuration from JSON (or `PCAConfig=... sbt ...`) |
| `python3 simulate.py configs/foo.json` | A wrapper around the JSON entry point |

## 8. How the design is tested

### 8.1 Golden model: `PCATestData`

`PCATestData(cfg)` builds everything the tests need, in software:

* `vec`: `n` random unsigned pixels in `[0, 2^pxbw)`.
* `mat(k)(j)`: random signed IEM coefficients in `[-2^(encbw-1), 2^(encbw-1))`,
  or non-negative if `nonegative` is set. The RNG uses `seed` when it is
  given.
* `ref(k) = sum_j vec(j) * mat(k)(j)`: the full reference result.
* `blockvec(b)(r)` and `blockmat(k)(b)(r)`: the same data cut into the
  per-block, per-row slices the hardware uses.
* `blockref(b)(k)`: the expected partial output for block `b`.
* `convArray2BigInt` / `convBigInt2Array`: pack arrays into bus words and
  unpack them again (two's complement, element 0 in the LSBs).

All values are `Long`, so `require(resbw < 64)` guards against overflow.

### 8.2 Unit tests (`sbt test` / `make test`)

The tests use ChiselSim (`chisel3.simulator.scalatest.ChiselSim`), which
compiles the design with **Verilator** through svsim. Verilator must
therefore be on `PATH`. `build.sbt` sets `Test / parallelExecution := false`.

**`PCAConfigSpec`** checks the golden model only, with no hardware. It
confirms that the reference computed directly over the full vector
(`ref`) equals the sum of the per-block, per-row partial references
(`reffromblocks`). This checks the slicing logic that the hardware tests
rely on. It runs for the `default` and `large` presets.

**`PCACompBlockSpec`** checks the RTL against the golden model:

| Test | What it does |
|---|---|
| `Verify IEM` | Loads all `h*m` IEM words of block 0 (`default` preset) with `updateIEM`, then reads each back with `verifyIEM` and compares it bit-exactly. Covers the SRAM wrapper, bank selection, and addressing. |
| `Single small config` / `Single large config` | Loads the IEM for block 0, streams `h` rows back to back with `indatavalid` held high, sets `out.ready`, waits for `out.valid`, unpacks the result and compares it to `blockref(0)`. |
| `Multiple small config` / `Multiple large config` | Runs the single-block flow for every block `0 .. nblocks-1`, each in a fresh simulation, then adds the partial vectors in software and compares the sum to the full reference `ref`. This checks the column-block decomposition end to end. Cross-block accumulation itself is not hardware. |

The single-block flow in `testPCACompBlock` works like this:

```
updateIEM:   for r in 0..h-1, k in 0..m-1: poke updateIEM=1, rowid=r, iempos=k, iemdata; step
compute:     indatavalid=1; for r in 0..h-1: poke rowid=r, indata=row; step
             indatavalid=0, rowid=0
drain:       out.ready=1; step until out.valid; peek out.bits
check:       convBigInt2Array(bits, redbw, m) == blockref(blockid)
```

The `large` preset (192x168, m=100, 8 blocks) exercises realistic widths:
`redbw = 20 + 5 + 8 = 33` and an output bus of 3300 bits. The `large` tests
use `seed = None`, so each run uses new random data. A failure prints the
DUT and reference vectors, but you cannot replay it without fixing a seed.
The `small` preset uses a fixed seed (123) and non-negative coefficients.

**`CompareRedSpec` / `VMulRedSpec`** check the scratch modules. They confirm
that the recursive adder tree and the built-in `reduce` agree, and they run
`VMulRed` with 1024 inputs at worst-case values (max pixel x most-negative
coefficient), with random values, and with a small hand-written vector.

CI (`.github/workflows/test.yml`) builds Verilator v5.022, caches it, and
runs `sbt test` on every push to `main` and on every pull request.

### 8.3 Generated-Verilog flow (`make test-all` / `simulate.py --test`)

This flow is for testing the emitted SystemVerilog outside Chisel, across
the `configs/test_*.json` shapes (minimal 4x4, narrow, wide, odd block
counts, 200x200, and others). For each config, `simulate.py --test`:

1. runs `pca.PCACompBlockJson` to generate the Verilog;
2. runs `test:runMain pca.ExportTestVectors` to write `test_vectors.json`
   (config, per-block input rows, IEM words as both arrays and packed
   big-integer strings, and per-block and full expected outputs);
3. runs a cocotb testbench (`make test` in `pca_compblock_cocotb/`), passing
   `TOPLEVEL` and `VERILOG_SOURCES`.

**Current gap:** the `pca_compblock_cocotb/` directory is not in the
repository. When it is missing, or when cocotb is not installed,
`simulate.py` prints a warning and exits with status 0. So `make test-all`
currently only checks that each config elaborates and that test vectors can
be exported. It reports "passed" without simulating any of those configs.
Hardware correctness is currently covered only by the ChiselSim tests in 8.2.

### 8.4 Coverage summary

| Covered | Not covered |
|---|---|
| IEM write/read-back (default preset) | `BlackBoxSRAM1RW` variant (tests use `SyncReadMem`) |
| One frame per simulation, rows back to back | Several frames in a row, gaps in `indatavalid`, `out.ready` back-pressure |
| Bit-exact results for small and large presets, all blocks | The `configs/test_*.json` shapes in RTL simulation (see 8.3) |
| Signed coefficients at full width (`large`) | The mode-switch hazard in 4.6 |
