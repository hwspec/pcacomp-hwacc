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
 * @param w the width  of the input pixel matrix
 * @param h the height of the input pixel matrix
 * @param m the number of the columns in vec, which is the number of principal components
 * @param vecbw : the bitwidth of vec element (unsigned integer)
 * @param matbw : the bitwidth of mat element (signed integer)
 * @param nblocks : the number of the parallel encoding blocks
 *
 */
class PCATestData(val w: Int = 24, val h : Int = 20, val m: Int = 10, vecbw: Int = 10, matbw: Int = 8, nblocks : Int = 2) {
  val n = w * h
  val resbw = vecbw + matbw + log2Ceil(n)
  require(resbw < 64)

  val blockwidth = w / nblocks

  val rnd = new Random(123)

  val mat: Array[Array[Long]] = Array.fill(n, m) {
    val tmp = 1 << matbw
    rnd.between(-tmp, tmp)
  }
  import scala.reflect.ClassTag

  def hsplit[T: ClassTag](a: Array[Array[T]], numSplits: Int): Array[Array[Array[T]]] = {
    val rows = a.length
    val cols = a(0).length
    require(cols % numSplits == 0, "Number of columns must be divisible by numSplits")

    val splitWidth = cols / numSplits
    Array.tabulate(numSplits) { splitIdx =>
      Array.tabulate(rows) { row =>
        a(row).slice(splitIdx * splitWidth, (splitIdx + 1) * splitWidth)
      }
    }
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

object Temp extends App {
  val a = Array(
    Array(1.0, 2.0, 3.0, 4.0),
    Array(5.0, 6.0, 7.0, 8.0)
  )
  val t = new PCATestData()
  val result = t.hsplit(a, 2)
  result.foreach { part =>
    println("Split:")
    part.foreach(row => println(row.mkString("[", ", ", "]")))
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
