// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2025, UChicago Argonne, LLC.
// Main author: Kazutomo Yoshii <kazutomo@anl.gov>. See LICENSE in project root.
package axipca

import garageworks._
import garageworks.AxiLiteResp._
import garageworks.AxiModuleParamsHelper._
import upickle.default._
import chisel3._
import chisel3.util._
import pca.{PCACompBlock, PCAConfig, PCAConfigPresets}

case class PCABlockModuleParams( // Note: do not put default value here
                                 // DefParams
                                 soft_reset_rw: Long,
                                 // module addr params
                                 const1_r : Long,
                                 const2_r : Long,
                                 iem_rowid_w : Long, // stages the target row for IEM update/verify
                                 iem_pos_w : Long,   // stages the target encoding position (iempos)
                                 iem_verifymode_w : Long, // write 1/0 to level-enable verifyIEM
                                 iem_data_w : Long,  // write fires a one-cycle updateIEM pulse
                                 iem_verify_r : Long, // read returns iemdataverify
                                 commit_w : Long, // takes rowid and commits the staged row into the input Q
                                 start_feed_w : Long, // start feeding queued rows into the dut
                                 drained_r : Long,
                                 inq_cnt_r : Long, // returns the input Q count
                                 outq_r : Long,
                                 outq_cnt_r : Long,
                                 fillup_w : Long, // filling up a row staging buf
                                 // internal definition
                                 reset_cycles : Int, // soft reset
                                 const1: Long,
                                 const2 : Long,
                                 // design params, flattened from PCAConfig (only the fields PCACompBlock reads)
                                 pca_w : Int,
                                 pca_h : Int,
                                 pca_pxbw : Int,
                                 pca_m : Int,
                                 pca_encbw : Int,
                                 pca_nblocks : Int,
                                 blockid : Int, // informational: which block's data this instance is fed
                                 inqsize : Int,
                                 outqsize : Int,
                                 drain_cycles : Int, // cycles to wait after feeding for the compute pipeline to drain
                               ) extends AxiModuleParams with AxiModuleDefParams
{
  val moduleName = "PCABlock"
}

object PCABlockModuleParams {
  implicit val rw: ReadWriter[PCABlockModuleParams] = macroRW

  def default(const1: Long, const2: Long,
              cfg: PCAConfig = PCAConfigPresets.small,
              blockid: Int = 0,
              inqsize: Int = 8, outqsize: Int = 4,
              drain_cycles: Int = 16
             ): PCABlockModuleParams =
    new PCABlockModuleParams(soft_reset_rw = 0x0,
      const1 = const1, const2 = const2,
      const1_r = 0x10, const2_r = 0x20,
      iem_rowid_w = 0x30, iem_pos_w = 0x40, iem_verifymode_w = 0x50,
      iem_data_w = 0x60, iem_verify_r = 0x70,
      commit_w = 0x80, start_feed_w = 0x90,
      drained_r = 0xA0, inq_cnt_r = 0xB0,
      outq_r = 0xC0, outq_cnt_r = 0xD0,
      fillup_w = 0x1000,
      reset_cycles = 8,
      pca_w = cfg.w, pca_h = cfg.h, pca_pxbw = cfg.pxbw,
      pca_m = cfg.m, pca_encbw = cfg.encbw, pca_nblocks = cfg.nblocks,
      blockid = blockid,
      inqsize = inqsize, outqsize = outqsize, drain_cycles = drain_cycles,
    )
}

// AXI4-Lite dispatcher for a single PCACompBlock instance.
//
// Compute rows are staged and committed through AXI4-Lite writes into an
// input row FIFO, then injected into the dut one row per cycle once
// start_feed_w is written (same pattern as GarageWorks' GWTestQTemplate).
// The dut's own output queue is re-queued into an external outputQ, exposed
// via outq_r/outq_cnt_r.
//
// IEM (inverse encoding matrix) loading/verification uses a simpler,
// GWCmdTemplate-style register-trigger interface instead: iem_rowid_w and
// iem_pos_w are level-held registers (shared by both update and verify).
// Writing iem_data_w fires a single-cycle updateIEM pulse using the
// currently staged rowid/pos. iem_verifymode_w level-enables verifyIEM;
// once enabled, iem_verify_r can be read back repeatedly as rowid/pos are
// changed (PCACompBlock's internal SRAMs are read every cycle regardless of
// mode, so by the time an AXI read transaction completes, the address has
// long since settled -- no extra wait-state logic is needed).
//
// updateIEM/verifyIEM are both forced off whenever the input feed FSM is
// not Idle (i.e. Feeding or Draining): PCACompBlock prioritizes
// updateIEM/verifyIEM over its internal compute pipeline, so leaving either
// asserted during feed/drain would stall or corrupt the in-flight compute
// pipeline state.
class AxiPCACompBlock(p : PCABlockModuleParams, debugprint: Boolean = false)
  extends Module with HasAxiLite32IO {
  val S = IO(new AxiLite32IO())

  val axibw = 32
  val width = p.pca_w / p.pca_nblocks
  val nbitsperrow = width * p.pca_pxbw
  val nwordsperrow = (nbitsperrow + axibw - 1) / axibw
  val nbytesperrow = nwordsperrow * (axibw / 8)
  val redbw = p.pca_pxbw + p.pca_encbw + log2Ceil(width) + log2Ceil(p.pca_h)

  require(width * p.pca_encbw <= axibw,
    s"AxiPCACompBlock: iemdata width (width*encbw=${width * p.pca_encbw}) must fit in a single ${axibw}-bit register")
  require(p.pca_m * redbw <= axibw,
    s"AxiPCACompBlock: output width (m*redbw=${p.pca_m * redbw}) must fit in a single ${axibw}-bit register")

  if (debugprint) {
    // print params, not RTL
    println(f"width : ${width}")
    println(f"nbitsperrow : ${nbitsperrow}")
    println(f"nwordsperrow : ${nwordsperrow}")
    println(f"redbw : ${redbw}")
  }

  // cycle counter for convenience
  val (cycles, wrap) = Counter(true.B, 1 << 16)

  // soft reset handling logic for dut
  val softResetReg = RegInit(false.B)
  val softResetDoneReg = RegInit(false.B)
  val resetCounterReg = RegInit(0.U(log2Ceil(p.reset_cycles).W))
  when(resetCounterReg > 0.U) {
    resetCounterReg := resetCounterReg - 1.U
  }.otherwise {
    softResetReg := false.B
    softResetDoneReg := true.B
  }
  val combinedReset: AsyncReset = (softResetReg || reset.asBool).asAsyncReset

  val cfg = PCAConfig(w = p.pca_w, h = p.pca_h, pxbw = p.pca_pxbw,
    m = p.pca_m, encbw = p.pca_encbw, nblocks = p.pca_nblocks)

  val dut = withReset(combinedReset) {
    Module(new PCACompBlock(cfg, useSyncReadMem = true, debugprint = false))
  }
  dut.io.rowid := 0.U
  dut.io.indata := 0.U
  dut.io.indatavalid := false.B
  dut.io.updateIEM := false.B
  dut.io.verifyIEM := false.B
  dut.io.iempos := 0.U
  dut.io.iemdata := 0.U
  dut.io.out.ready := false.B

  val outputQ = withReset(combinedReset) {
    Module(new Queue(UInt(axibw.W), entries = p.outqsize))
  }
  outputQ.io.enq.valid := dut.io.out.valid
  outputQ.io.enq.bits := dut.io.out.bits
  outputQ.io.deq.ready := false.B
  dut.io.out.ready := outputQ.io.enq.ready

  class RowData extends Bundle {
    val rowid = UInt(log2Ceil(p.pca_h).W)
    val indata = UInt((width * p.pca_pxbw).W)
  }

  val inputQ = withReset(combinedReset) {
    Module(new Queue(new RowData, entries = p.inqsize))
  }
  inputQ.io.enq.valid := false.B
  inputQ.io.enq.bits := 0.U.asTypeOf(new RowData)
  inputQ.io.deq.ready := false.B

  // staging
  val stagingRowDataReg = withReset(combinedReset) {
    RegInit(VecInit(Seq.fill(nwordsperrow)(0.U(axibw.W))))
  }
  val stagingRowIDReg = withReset(combinedReset) {
    RegInit(0.U(log2Ceil(p.pca_h).W))
  }
  val commitReg = withReset(combinedReset) {
    RegInit(false.B)
  }
  val stagingbits = stagingRowDataReg.asUInt

  inputQ.io.enq.bits.indata := stagingbits(width * p.pca_pxbw - 1, 0)
  inputQ.io.enq.bits.rowid := stagingRowIDReg
  when(commitReg) {
    inputQ.io.enq.valid := true.B // Note: the producer checks inq cnt
    commitReg := false.B
  }

  // feeding inq to dut
  object InputFeedSeq extends ChiselEnum {
    val Idle, Feeding, Draining = Value
  }

  val inputFeedStatusReg = withReset(combinedReset) { RegInit(InputFeedSeq.Idle) }
  val drainingCntReg = withReset(combinedReset) { RegInit(0.U(log2Ceil(p.drain_cycles + 1).W)) }
  val drainedReg = withReset(combinedReset) { RegInit(false.B) }

  // covers both Feeding and Draining: see the class-level comment on why
  // updateIEM/verifyIEM must stay gated through the entire drain window,
  // not just while Feeding.
  val feedActive = inputFeedStatusReg =/= InputFeedSeq.Idle

  when(inputFeedStatusReg === InputFeedSeq.Feeding) {
    when(inputQ.io.count === 0.U) {
      if (debugprint) printf("%d: move to draining\n", cycles)
      inputFeedStatusReg := InputFeedSeq.Draining
    }.otherwise {
      inputQ.io.deq.ready := true.B
      when(inputQ.io.deq.valid) {
        if (debugprint) printf("%d: feeding rowid=%d cnt=%d\n", cycles, inputQ.io.deq.bits.rowid,
          inputQ.io.count)
        dut.io.rowid := inputQ.io.deq.bits.rowid
        dut.io.indata := inputQ.io.deq.bits.indata
        dut.io.indatavalid := true.B
      }
    }
  }.elsewhen(inputFeedStatusReg === InputFeedSeq.Draining) {
    when(drainingCntReg === 0.U) {
      if (debugprint) printf("%d: drained\n", cycles)
      inputFeedStatusReg := InputFeedSeq.Idle
      drainedReg := true.B
    }.otherwise {
      drainingCntReg := drainingCntReg - 1.U
    }
  }

  // IEM registers: rowid/iempos are level-held (shared by update and
  // verify); updateIEM fires as a one-shot pulse from the AXI write decode
  // below, verifyIEM is a level gated by iemVerifyModeReg.
  val iemRowidReg = withReset(combinedReset) { RegInit(0.U(log2Ceil(p.pca_h).W)) }
  val iemPosReg = withReset(combinedReset) { RegInit(0.U(log2Ceil(p.pca_m).W)) }
  val iemVerifyModeReg = withReset(combinedReset) { RegInit(false.B) }

  when(!(inputFeedStatusReg === InputFeedSeq.Feeding && inputQ.io.deq.valid)) {
    dut.io.rowid := iemRowidReg
  }
  dut.io.iempos := iemPosReg
  dut.io.verifyIEM := iemVerifyModeReg && !feedActive

  // -----------------------------
  // AXI-lite regs
  // -----------------------------
  val awHoldValidReg = RegInit(false.B)
  val awHoldAddrReg = Reg(UInt(axibw.W))
  val wHoldValidReg = RegInit(false.B)
  val wHoldDataReg = Reg(UInt(axibw.W))
  val wHoldStrbReg = Reg(UInt(4.W))

  val bvalidReg = RegInit(false.B)
  val brespReg = RegInit(0.U(2.W))

  S.AXI.awready := !awHoldValidReg && !bvalidReg
  S.AXI.wready := !wHoldValidReg && !bvalidReg
  val awFire = S.AXI.awvalid && S.AXI.awready
  val wFire = S.AXI.wvalid && S.AXI.wready
  when(awFire) {
    awHoldValidReg := true.B
    awHoldAddrReg := S.AXI.awaddr(19, 0) // in case MMIO range is 1MB
  }
  when(wFire) {
    wHoldValidReg := true.B
    wHoldDataReg := S.AXI.wdata
    wHoldStrbReg := S.AXI.wstrb
  }

  val doWrite = awHoldValidReg && wHoldValidReg && !bvalidReg

  when(doWrite) {
    val a = awHoldAddrReg
    val fullWrite = (wHoldStrbReg === "b1111".U)

    val bresp = WireDefault(OKAY.U)

    when(!fullWrite) { // support full write only for this example
      bresp := SLVERR.U
    }.elsewhen(a === p.soft_reset_rw.U) {
      if (debugprint) printf("%d: reset wr\n", cycles)
      softResetReg := true.B
      resetCounterReg := p.reset_cycles.U
      softResetDoneReg := false.B
      bresp := OKAY.U
    }.elsewhen(a === p.iem_rowid_w.U) {
      if (debugprint) printf("%d: iem_rowid wr: %d\n", cycles, wHoldDataReg)
      iemRowidReg := wHoldDataReg
    }.elsewhen(a === p.iem_pos_w.U) {
      if (debugprint) printf("%d: iem_pos wr: %d\n", cycles, wHoldDataReg)
      iemPosReg := wHoldDataReg
    }.elsewhen(a === p.iem_verifymode_w.U) {
      if (debugprint) printf("%d: iem_verifymode wr: %d\n", cycles, wHoldDataReg)
      iemVerifyModeReg := wHoldDataReg(0)
    }.elsewhen(a === p.iem_data_w.U) {
      if (debugprint) printf("%d: iem_data wr: rowid=%d pos=%d data=%x\n",
        cycles, iemRowidReg, iemPosReg, wHoldDataReg)
      dut.io.updateIEM := !feedActive
      dut.io.rowid := iemRowidReg
      dut.io.iempos := iemPosReg
      dut.io.iemdata := wHoldDataReg
    }.elsewhen(a === p.commit_w.U) {
      if (debugprint) printf("%d: commit wr: rowid=%d\n", cycles, wHoldDataReg)
      stagingRowIDReg := wHoldDataReg
      commitReg := true.B
    }.elsewhen(a === p.start_feed_w.U) {
      if (debugprint) printf("%d: startfeed wr\n", cycles)
      inputFeedStatusReg := InputFeedSeq.Feeding
      drainingCntReg := p.drain_cycles.U
      drainedReg := false.B
    }.elsewhen(a >= p.fillup_w.U &&
      a < (p.fillup_w + nbytesperrow).U) {
      if (nwordsperrow == 1) {
        if (debugprint) printf("%d: filling staging data: %x\n", cycles, wHoldDataReg)
        stagingRowDataReg(0) := wHoldDataReg
      } else {
        val wordoffset = ((a - p.fillup_w.U) >> 2.U)(log2Ceil(nwordsperrow) - 1, 0)
        if (debugprint) printf("%d: filling staging data: %x at %d\n", cycles, wHoldDataReg, wordoffset)
        stagingRowDataReg(wordoffset) := wHoldDataReg
      }
    }.otherwise {
      bresp := SLVERR.U
    }
    brespReg := bresp
    bvalidReg := true.B
    awHoldValidReg := false.B
    wHoldValidReg := false.B
  }

  when(bvalidReg && S.AXI.bready) {
    bvalidReg := false.B
  }
  S.AXI.bvalid := bvalidReg
  S.AXI.bresp := brespReg

  // -----------------------------
  // Read path: AR -> R
  // -----------------------------
  val rdataReg = Reg(UInt(32.W))
  val rrespReg = RegInit(0.U(2.W))

  object RState extends ChiselEnum {
    val READY2READ, COMPLETED = Value
  }

  val rstateReg = RegInit(RState.READY2READ)

  S.AXI.arready := rstateReg === RState.READY2READ
  S.AXI.rvalid := rstateReg === RState.COMPLETED
  S.AXI.rdata := rdataReg
  S.AXI.rresp := rrespReg

  val arFire = S.AXI.arvalid && S.AXI.arready

  when(arFire) {
    if (debugprint) printf("%d: arFire: %x\n", cycles, S.AXI.araddr)
    val araddr = S.AXI.araddr(19, 0) // 1MB range
    rrespReg := OKAY.U

    val rstate = WireDefault(RState.READY2READ)

    when(araddr === p.const1_r.U) {
      if (debugprint) printf("%d: const1 rd\n", cycles)
      rdataReg := p.const1.U
      rstate := RState.COMPLETED
    }.elsewhen(araddr === p.const2_r.U) {
      if (debugprint) printf("%d: const2 rd\n", cycles)
      rdataReg := p.const2.U
      rstate := RState.COMPLETED
    }.elsewhen(araddr === p.soft_reset_rw.U) {
      if (debugprint) printf("%d: reset done rd\n", cycles)
      rdataReg := softResetDoneReg
      rstate := RState.COMPLETED
    }.elsewhen(araddr === p.iem_verify_r.U) {
      if (debugprint) printf("%d: iem_verify rd: %x\n", cycles, dut.io.iemdataverify)
      rdataReg := dut.io.iemdataverify
      rstate := RState.COMPLETED
    }.elsewhen(araddr === p.drained_r.U) {
      if (debugprint) printf("%d: drained %d\n", cycles, drainedReg)
      rdataReg := drainedReg
      rstate := RState.COMPLETED
    }.elsewhen(araddr === p.inq_cnt_r.U) {
      if (debugprint) printf("%d: inqcnt rd %d\n", cycles, inputQ.io.count)
      rdataReg := inputQ.io.count
      rstate := RState.COMPLETED
    }.elsewhen(araddr === p.outq_r.U) {
      outputQ.io.deq.ready := true.B
      if (debugprint) printf("%d: out rd valid=%d bits=%d\n", cycles,
        outputQ.io.deq.valid, outputQ.io.deq.bits)
      when(outputQ.io.deq.valid) {
        rdataReg := outputQ.io.deq.bits
      }.otherwise {
        rdataReg := p.const1.U
      }
      rstate := RState.COMPLETED
    }.elsewhen(araddr === p.outq_cnt_r.U) {
      if (debugprint) printf("%d: outqcnt rd %d\n", cycles, outputQ.io.count)
      rdataReg := outputQ.io.count
      rstate := RState.COMPLETED
    }.otherwise {
      if (debugprint) printf("%d: bad read req %d\n", cycles, araddr)
      rdataReg := 0xbad00000L.U | S.AXI.araddr(31, 0)
      rstate := RState.COMPLETED
    }
    rstateReg := rstate
  }

  when(rstateReg === RState.COMPLETED && S.AXI.rready) {
    rstateReg := RState.READY2READ
  }
}

object AxiPCACompBlock extends App {
  val const1 : Long = 0xdeadbeefL // module id
  val const2 : Long = getGitHash  // git hash of the generating commit

  val p = checkParamEnv(
    PCABlockModuleParams.default(const1 = const1, const2 = const2,
      cfg = PCAConfigPresets.small, blockid = 0),
    "PCABLOCK_MODULE_PARAMS")

  EmitVerilog.generate(new AxiPCACompBlock(p, debugprint = true), p)
}
