package pca

import chisel3._
import chisel3.util._
import scala.util.Random
import org.scalatest.flatspec.AnyFlatSpec
import chisel3.simulator.EphemeralSimulator._
//import chisel3.simulator.ChiselSim  // for 7.0 or later

class PCACompBlockSpec extends AnyFlatSpec {
  behavior of "PCACompBlock"

  def updateIEM(dut: PCACompBlock, td: PCATestData, blockid: Int, cfg: PCAConfig) : Unit = {
    dut.io.verifyIEM.poke(false)
    dut.io.updateIEM.poke(false)
    dut.clock.step()
    for(rowid <- 0 until cfg.h) {
      for(encid <- 0 until cfg.m) {
        dut.io.updateIEM.poke(true)
        dut.io.rowid.poke(rowid)
        dut.io.iempos.poke(encid)
        val data = td.getPerEncBlockRow2Bits(encid, blockid, rowid)
        dut.io.iemdata.poke(data)
        dut.clock.step()
        // println(s"updateIEM: $rowid/$encid $data")
      }
    }
    dut.clock.step()
    dut.io.verifyIEM.poke(false)
    dut.io.updateIEM.poke(false)
  }

  def verifyIEM(dut: PCACompBlock, td: PCATestData, blockid: Int, cfg: PCAConfig) : Unit = {
    dut.io.verifyIEM.poke(false)
    dut.io.updateIEM.poke(false)
    dut.clock.step()
    for(rowid <- 0 until cfg.h) {
      for(encid <- 0 until cfg.m) {
        dut.io.verifyIEM.poke(true)
        dut.io.rowid.poke(rowid)
        dut.io.iempos.poke(encid)
        dut.clock.step()
        val iemdata = dut.io.iemdataverify.peek().litValue
        val ref = td.getPerEncBlockRow2Bits(encid, blockid, rowid)
        //println(s"verifyIEM: $rowid/$encid dut=$iemdata, ref=$ref")
        assert(ref == iemdata, s"failed to verify IEM: $rowid/$encid dut=$iemdata, ref=$ref")
      }
    }
    dut.clock.step()
    dut.io.verifyIEM.poke(false)
    dut.io.updateIEM.poke(false)
  }

  "Verify IEM" should "pass" in {
    val cfg = PCAConfigPresets.default
    val blockid = 0

    simulate(new PCACompBlock(cfg, debugprint = false)) { dut =>
      val pcadata = new PCATestData(cfg)
      pcadata.printInfo()
      updateIEM(dut, pcadata, blockid, cfg)
      verifyIEM(dut, pcadata, blockid, cfg)
    }
  }

  "Block test with the small config" should "pass" in {
    val cfg = PCAConfigPresets.default
    val blockid = 0

    simulate(new PCACompBlock(cfg, debugprint = true)) { dut =>
      val pcadata = new PCATestData(cfg)
      val indata = Array.fill(pcadata.blockwidth)(1.toLong)
      pcadata.printInfo()
      updateIEM(dut, pcadata, blockid, cfg)

      dut.io.indatavalid.poke(true)
      for(rowid <- 0 until cfg.h) {
        dut.io.rowid.poke(rowid)
        dut.io.indata.poke(pcadata.convArray2BigInt(indata, cfg.pxbw))
        dut.clock.step()
      }
      dut.io.indatavalid.poke(true)
      dut.clock.step(5) // the number of pipeline
    }
  }


}
