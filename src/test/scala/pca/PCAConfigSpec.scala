package pca

import org.scalatest.flatspec.AnyFlatSpec

class PCAConfigSpec extends AnyFlatSpec {
  behavior of "PCAConfig"

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
