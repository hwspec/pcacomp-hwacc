package pca

import chisel3._
import chisel3.util._
import scala.util.Random
import org.scalatest.flatspec.AnyFlatSpec
import chisel3.simulator.EphemeralSimulator._
//import chisel3.simulator.ChiselSim  // for 7.0 or later

/**
 * PCA testdata generation
 *
 * vec : row-vector with the size n.
 * mat : matrix with n rows and m cols.
 *
 * @param n the number of the rows in mat and the length of vec
 * @param m the number of the columns in vec
 * @param vecbw : the bitwidth of vec element (unsigned integer)
 * @param matbw : the bitwidth of mat element (signed integer)
 * @param nblocks : the number of the parallel encoding blocks
 *
 */
class PCATestData(val n: Int = 168, val m: Int = 192, vecbw: Int = 12, matbw: Int = 8, nblocks : Int = 8) {
  val resbw = vecbw + matbw + log2Ceil(n)
  require(resbw < 64)

  val rnd = new Random(123)

  val mat: Array[Array[Long]] = Array.fill(n, m) {
    val tmp = 1 << matbw
    rnd.between(-tmp, tmp)
  }

  val vec: Array[Long] = Array.fill(n) {
    rnd.nextInt(1 << vecbw)
  }

  val ref: Array[Long] = Array.fill(m)(0.toLong)
  for (encidx <- 0 until m) {
    ref(encidx) = ref(encidx) +
      (0 until n).map(j => vec(j) * mat(j)(encidx)).sum
  }
}

class PCACompBlockSpec extends AnyFlatSpec {
  behavior of "PCACompBlock"

  "PCA basic test" should "pass" in {
    simulate(new PCACompBlock(nrows = 4, nmaxpcs = 10)) { dut =>
      val pcadata = new PCATestData(dut.nrows, dut.width, dut.nmaxpcs, dut.pxbw, dut.iembw)
      println(pcadata.ref.mkString(" "))
    }
  }
}
