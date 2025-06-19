package pca

import chisel3._
import chisel3.util._
import scala.util.Random
import org.scalatest.flatspec.AnyFlatSpec
import chisel3.simulator.EphemeralSimulator._
//import chisel3.simulator.ChiselSim  // for 7.0 or later

class PCACompBlockSpec extends AnyFlatSpec {
  behavior of "PCACompBlock"

  "PCA basic test" should "pass" in {
    val cfg = PCAConfigPresets.default
    simulate(new PCACompBlock(cfg)) { dut =>
      val pcadata = new PCATestData(cfg)
      println(pcadata.ref.mkString(" "))
    }
  }
}
