// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2025, UChicago Argonne, LLC.
// Main author: Kazutomo Yoshii <kazutomo@anl.gov>. See LICENSE in project root.
package axipca

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import garageworks._
import garageworks.AxiModuleParamsHelper._
import pca.{PCAConfig, PCAConfigPresets, PCATestData}

class AxiPCACompBlockSpec extends AnyFlatSpec with ChiselSim {
  val const1 = 0xdeadbeefL
  val const2 = getGitHash

  def loadIEM(bfm: Axi4Lite32BFM[AxiPCACompBlock], p: PCABlockModuleParams,
              td: PCATestData, cfg: PCAConfig, blockid: Int): Unit = {
    for (rowid <- 0 until cfg.h) {
      for (encid <- 0 until cfg.m) {
        bfm.writeVal(p.iem_rowid_w, rowid)
        bfm.writeVal(p.iem_pos_w, encid)
        val data = td.getPerEncBlockRow2Bits(encid, blockid, rowid)
        bfm.writeVal(p.iem_data_w, data)
      }
    }
  }

  def verifyIEM(bfm: Axi4Lite32BFM[AxiPCACompBlock], p: PCABlockModuleParams,
                td: PCATestData, cfg: PCAConfig, blockid: Int): Unit = {
    bfm.writeVal(p.iem_verifymode_w, 1)
    for (rowid <- 0 until cfg.h) {
      for (encid <- 0 until cfg.m) {
        bfm.writeVal(p.iem_rowid_w, rowid)
        bfm.writeVal(p.iem_pos_w, encid)
        val ref = td.getPerEncBlockRow2Bits(encid, blockid, rowid)
        bfm.expectVal(p.iem_verify_r, ref,
          msg = s"failed to verify IEM: rowid=$rowid encid=$encid")
      }
    }
    bfm.writeVal(p.iem_verifymode_w, 0)
  }

  def commitRow(bfm: Axi4Lite32BFM[AxiPCACompBlock], p: PCABlockModuleParams,
                td: PCATestData, cfg: PCAConfig, blockid: Int, rowid: Int): Unit = {
    val indata = td.blockvec(blockid)(rowid)
    val packed = td.convArray2BigInt(indata, cfg.pxbw)
    bfm.writeVal(p.fillup_w, packed)
    bfm.writeVal(p.commit_w, rowid)
  }

  def startFeedAndDrain(bfm: Axi4Lite32BFM[AxiPCACompBlock], p: PCABlockModuleParams): Unit = {
    bfm.writeVal(p.start_feed_w, 1)
    var drained = false
    while (!drained) {
      if (bfm.readVal(p.drained_r) > 0) drained = true
    }
  }

  def testAxiPCACompBlock(cfg: PCAConfig, td: PCATestData, blockid: Int): Unit = {
    val p = checkParamEnv(
      PCABlockModuleParams.default(const1 = const1, const2 = const2,
        cfg = cfg, blockid = blockid, inqsize = 8, outqsize = 4, drain_cycles = 16),
      "PCABLOCK_MODULE_PARAMS")

    simulate(new AxiPCACompBlock(p, debugprint = true)) { dut =>
      val bfm = new Axi4Lite32BFM[AxiPCACompBlock](dut)
      bfm.initMaster()

      bfm.expectVal(p.const1_r, const1)
      bfm.expectVal(p.const2_r, const2)

      loadIEM(bfm, p, td, cfg, blockid)
      verifyIEM(bfm, p, td, cfg, blockid)

      for (rowid <- 0 until cfg.h) {
        commitRow(bfm, p, td, cfg, blockid, rowid)
      }
      bfm.expectVal(p.inq_cnt_r, cfg.h)

      startFeedAndDrain(bfm, p)

      bfm.expectVal(p.outq_cnt_r, 1)
      val res = bfm.readVal(p.outq_r)
      val resarray = td.convBigInt2Array(res, dut.redbw, cfg.m)
      bfm.expectVal(p.outq_cnt_r, 0)

      assert(resarray.sameElements(td.blockref(blockid)),
        s"dut=${resarray.mkString(" ")} ref=${td.blockref(blockid).mkString(" ")}")
    }
  }

  "AxiPCACompBlock with small config" should "pass" in {
    val cfg = PCAConfigPresets.small
    val td = new PCATestData(cfg)
    td.printInfo()
    testAxiPCACompBlock(cfg, td, blockid = 0)
  }
}
