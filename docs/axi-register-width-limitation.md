# TODO

## `AxiPCACompBlock` only supports configs that fit in one 32-bit register

`src/main/scala/axipca/AxiPCACompBlock.scala` wraps `PCACompBlock` in an
AXI4-Lite register interface (`Axi4Lite32BFM`, 32-bit data bus). Two of its
registers are implemented as a single, atomic 32-bit transfer for a payload
that is wider than 32 bits in general:

* `iem_data_w` (`AxiPCACompBlock.scala:293-299`): one AXI write drives
  `dut.io.iemdata` directly and fires the `updateIEM` pulse in the same
  cycle. The IEM word for one `(rowid, pos)` is `width*encbw` bits.
* `outq_r` (`AxiPCACompBlock.scala:388-396`): the result queue (`outputQ`) is
  declared as `Queue(UInt(axibw.W), ...)` — 32 bits wide — but
  `dut.io.out.bits` is `m*redbw` bits.

This is enforced by two `require`s at `AxiPCACompBlock.scala:113-115`:

```scala
require(width * p.pca_encbw <= axibw, ...)
require(p.pca_m * redbw <= axibw, ...)
```

Only `PCAConfigPresets.small` happens to satisfy both (by construction, it
fits in exactly 32 bits). `medium`, `large`, and `cfg1` all fail to
elaborate `AxiPCACompBlock` — this is independent of single- vs
multi-block testing; `AxiPCACompBlockSpec` therefore only tests `small`,
and `PCACompBlockSpec`'s multi-block pattern (8.2 in `implementation.md`)
has no AXI-level equivalent.

This is **not** an address-map limitation — the map has slack (addresses
jump from `0xD0` straight to `0x1000` for `fillup_w`). It's a data-path
width limitation, and the fix already has a precedent in this file: the
input row path (`fillup_w`) stages a row wider than 32 bits across multiple
AXI writes. `stagingRowDataReg` is a `Vec` of `nwordsperrow` 32-bit
registers; the write decode at `AxiPCACompBlock.scala:308-317` computes a
`wordoffset` from the address (`fillup_w`, `fillup_w+4`, ...) and writes
into the matching slot, then `commit_w` atomically latches the assembled
row. `iem_data_w` and `outq_r` were never given the same treatment.

### Possible approaches

1. **Generalize `iem_data_w`/`outq_r` the same way `fillup_w` already works
   (recommended).**
   - IEM write: stage into a `Vec` sized `ceil(width*encbw/32)` words,
     decode by address word-offset like `fillup_w`, and fire `updateIEM`
     on an explicit commit write (mirroring `commit_w`) instead of on
     every write.
   - Output read: widen `outputQ`'s element type to `UInt((m*redbw).W)`
     and make `outq_r` a multi-beat read (auto-incrementing word offset by
     address, or an explicit word-index register like `iem_rowid_w`/
     `iem_pos_w`).
   - Smallest, most consistent change — same pattern and style already in
     the file. Spec call sites (`AxiPCACompBlockSpec.scala`) would need a
     small multi-word write/read helper, analogous to how `commitRow`
     already packs `fillup_w`.

2. **Widen the AXI-Lite data bus** (e.g. 64/128 bits). Bigger change:
   `AxiLite32IO`/`Axi4Lite32BFM` are used as a fixed 32-bit convention
   across the codebase, so this ripples beyond this one module. Also
   doesn't fully solve it on its own — `medium`'s `m*redbw` is around
   1650 bits, so multi-beat transfers would still be needed on top of a
   wider bus.

3. **Status quo**: restrict `AxiPCACompBlock` testing to configs that
   satisfy the 32-bit constraint (what `AxiPCACompBlockSpec` does today).
   Zero RTL risk, but `medium`/`large`/`cfg1` (and most non-toy presets)
   stay permanently untestable through this AXI wrapper.
