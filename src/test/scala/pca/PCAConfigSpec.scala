package pca

import org.scalatest.flatspec.AnyFlatSpec
//import chisel3.BuildInfo

class PCAConfigSpec extends AnyFlatSpec {
  behavior of "PCAConfig"

  //println(s"Chisel version = ${BuildInfo.version}")

  "Default config" should "pass" in {
    val t = new PCATestData()
    t.printInfo()
    assert(t.validateRef())
  }

  "Large config" should "pass" in {
    val t = new PCATestData(PCAConfigPresets.large)
    t.printInfo()
    assert(t.validateRef())
  }
}
