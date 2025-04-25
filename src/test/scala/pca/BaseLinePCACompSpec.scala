package pca

import chiseltest._
import chisel3.simulator.VCDHackedEphemeralSimulator._

import scala.util.Random
import org.scalatest.flatspec.AnyFlatSpec

class PCARef(N: Int, M: Int, PXBW: Int, IEMBW: Int) {

  val maxUnsigned = (1 << PXBW) - 1
  val row = Array.fill(N)(Random.nextInt(maxUnsigned + 1))

  val minSigned = -(1 << (IEMBW - 1))
  val maxSigned = (1 << (IEMBW - 1)) - 1
  val matrix = Array.fill(N, M)(Random.between(minSigned, maxSigned + 1))

  def rowVectorMatrixProduct(): Array[Int] = {
    require(row.length == matrix.length, "Row-vector length must match number of matrix rows")
    val M = matrix(0).length
    Array.tabulate(M) { j =>
      (0 until row.length).map(i => row(i) * matrix(i)(j)).sum
    }
  }
}

object PCARefTest extends App {
  val N = 4
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

  val W = 4
  val H = W
  val N = W * H
  val M = 3
  val PXBW  = 8
  val IEMBW = 8

  val pcaref = new PCARef(N, M, PXBW, IEMBW)

  "iem update test" should "pass" in {
    simulate(new BaseLinePCAComp(
      pxbw = PXBW, width = W, height = H,
      iemsize = M, iembw = IEMBW,
      debugprint = true)) { dut =>

    }
  }
}
