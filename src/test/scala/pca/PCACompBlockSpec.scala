package pca

import org.scalatest.flatspec.AnyFlatSpec
import chisel3.simulator.EphemeralSimulator._
//import chisel3.simulator.scalatest.ChiselSim  // for 7.0 or later
import scala.collection.mutable.ArrayBuffer

class PCACompBlockSpec extends AnyFlatSpec {
  behavior of "PCACompBlock"

  def updateIEM(dut: PCACompBlock, td: PCATestData, blockid: Int, cfg: PCAConfig): Unit = {
    dut.io.verifyIEM.poke(false)
    dut.io.updateIEM.poke(false)
    dut.clock.step()
    for (rowid <- 0 until cfg.h) {
      for (encid <- 0 until cfg.m) {
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

  def verifyIEM(dut: PCACompBlock, td: PCATestData, blockid: Int, cfg: PCAConfig): Unit = {
    dut.io.verifyIEM.poke(false)
    dut.io.updateIEM.poke(false)
    dut.clock.step()
    for (rowid <- 0 until cfg.h) {
      for (encid <- 0 until cfg.m) {
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

  def testPCACompBlock(cfg: PCAConfig, td: PCATestData, blockid: Int): Array[Long] = {
    var ret : Array[Long] = Array.fill(cfg.m)(0)

    simulate(new PCACompBlock(cfg, debugprint = false)) { dut =>
      // val indata = Array.fill(td.blockwidth)(1.toLong)
      td.printInfo()
      // td.dumpMat()
      // td.dumpVec()
      updateIEM(dut, td, blockid, cfg)


      dut.io.indatavalid.poke(true)
      for (rowid <- 0 until cfg.h) {
        dut.io.rowid.poke(rowid)
        val indata = td.blockvec(blockid)(rowid)
        dut.io.indata.poke(td.convArray2BigInt(indata, cfg.pxbw))
        dut.clock.step()
      }
      dut.io.rowid.poke(0) // new frame
      dut.io.indatavalid.poke(false)
      //dut.clock.step(3)

      dut.io.out.ready.poke(true)
      while (!dut.io.out.valid.peek().litToBoolean) {
        dut.clock.step()
      }
      val res = dut.io.out.bits.peek().litValue
      val resarray = td.convBigInt2Array(res,
        dut.redbw, cfg.m)
      ret = resarray
      // println(s"$res")
      def printRefandRes() : String = {
        println(s"dut : ${resarray.mkString(" ")}")
        println(s"ref : ${td.blockref(blockid).mkString(" ")}")
        "failed"
      }
      if(resarray.sameElements(td.blockref(blockid))) {
        // printRefandRes()
      } else {
        assert(false, printRefandRes())  // 'if' due to no lazy eval of printefandRes
      }
    }
    ret
  }

  def singleBlockTest(cfg: PCAConfig) : Unit = {
    val td = new PCATestData(cfg)
    testPCACompBlock(cfg, td, blockid = 0)
  }

  "Single small config" should "pass" in singleBlockTest(PCAConfigPresets.small)

  "Single large config" should "pass" in singleBlockTest(PCAConfigPresets.large)


  def multipleBlockTest(cfg: PCAConfig) : Unit = {
    val td = new PCATestData(cfg)
    val partialsbuf = ArrayBuffer[Array[Long]]()
    for (blockid <- 0 until cfg.nblocks) {
      partialsbuf += testPCACompBlock(cfg, td, blockid)
    }
    val summed: Array[Long] = partialsbuf.toArray.transpose.map(_.sum)

    def printRefandRes(): String = {
      println(summed.mkString(" "))
      println(td.ref.mkString(" "))
      "failed"
    }

    if (summed.sameElements(td.ref)) {
      // printRefandRes()
    } else {
      assert(false, printRefandRes()) // 'if' due to no lazy eval of printefandRes
    }
  }

  "Multiple small config" should "pass" in multipleBlockTest(PCAConfigPresets.small)

  "Multiple large config" should "pass" in multipleBlockTest(PCAConfigPresets.large)
}
