package pca

import chisel3._
import chisel3.simulator.VCDHackedEphemeralSimulator._

import scala.util.Random
import org.scalatest.flatspec.AnyFlatSpec

class PCARef(N: Int, M: Int, PXBW: Int, IEMBW: Int) {

  val maxUnsigned = (1 << PXBW) - 1
  val row = Array.fill(N)(Random.nextInt(maxUnsigned + 1))

  val minSigned = -(1 << (IEMBW - 1))
  val maxSigned = (1 << (IEMBW - 1)) - 1
  // Note: transposed for easier principal component access
  val matrix = Array.fill(M, N)(Random.between(minSigned, maxSigned + 1))

  def rowVectorMatrixProduct(): Array[Int] = {
    require(row.length == matrix(0).length, "Row-vector length must match number of matrix rows")
    val M = matrix.length
    Array.tabulate(M) { j =>
      (0 until row.length).map(i => row(i) * matrix(j)(i)).sum
    }
  }
}

object PCARefTest extends App {
  val N = 8
  val M = 3
  val PXBW  = 8
  val IEMBW = 8

  val pcaref = new PCARef(N, M, PXBW, IEMBW)

  val result = pcaref.rowVectorMatrixProduct()

  println("Row Vector: " + pcaref.row.mkString("[", ", ", "]"))
  println("Matrix:")
  pcaref.matrix.foreach(row => println(row.mkString("[", ", ", "]")))
  println("Result: " + result.mkString("[", ", ", "]"))
}

class BaseLinePCACompSpec extends AnyFlatSpec {
  behavior of "BaseLinePCAComp"

  val W = 2
  val H = W
  val N = W * H
  val M = 3
  val PXBW  = 8
  val IEMBW = 8

  val pcaref = new PCARef(N, M, PXBW, IEMBW)

  def resetBaseLinePCAComp(dut: BaseLinePCAComp): Unit = {
    // EphemeralSimulator required a reset explicitly for some reason
    dut.reset.poke(true)
    dut.clock.step(1)
    dut.reset.poke(false)
    dut.clock.step(1) // initialize the regs with default value
  }

  def uploadRefBaseLinePCAComp(dut: BaseLinePCAComp, verify: Boolean = false): Unit = {
    dut.io.updateIEM.poke(true)
    for(iempos <- 0 until M) {
      dut.io.iempos.poke(iempos)
      for(pxpos <- 0 until N) {
        dut.io.iemdata(pxpos).poke(pcaref.matrix(iempos)(pxpos))
      }
      dut.clock.step()
    }
    dut.io.updateIEM.poke(false)

    if(verify) {
      dut.io.verifyIEM.poke(true)
      for (iempos <- 0 until M) {
        dut.io.iempos.poke(iempos)
        dut.clock.step()

        for (pxpos <- 0 until N) {
          val hw = dut.io.iemdataverify(pxpos).peek().litValue.toInt
          val ref = pcaref.matrix(iempos)(pxpos)
          assert(hw == ref, f"hw=$hw ref=$ref at iempos${iempos}/pxpos${pxpos}")
        }
      }
      dut.io.verifyIEM.poke(false)
      dut.clock.step(1)
    }
  }

  "iem update test" should "pass" in {
    simulate(new BaseLinePCAComp(
      pxbw = PXBW, width = W, height = H,
      iemsize = M, iembw = IEMBW,
      debugprint = false)) { dut =>

      uploadRefBaseLinePCAComp(dut, verify = true)
    }
  }

}
