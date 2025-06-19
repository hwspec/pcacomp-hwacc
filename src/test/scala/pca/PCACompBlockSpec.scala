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
class PCATestData(val w: Int = 12, val h : Int = 3, val m: Int = 7, vecbw: Int = 9, matbw: Int = 8, nblocks : Int = 3, seed : Int = 123) {
  import scala.reflect.ClassTag

  val n = w * h
  val resbw = vecbw + matbw + log2Ceil(n)
  require(resbw < 64)

  val blockwidth = w / nblocks

  val rnd = new Random(seed)

  val img : Array[Array[Long]] = Array.fill(w, h) {
    rnd.nextInt(1 << vecbw)
  }
  val vec: Array[Long] = img.flatten

  // mat is a transposed invenc matrix
  val mat: Array[Array[Long]] = Array.fill(m, n) {
    val tmp = 1 << matbw
    rnd.between(-tmp, tmp)
  }

  val ref: Array[Long] = Array.fill(m)(0.toLong)
  for (encidx <- 0 until m) {
    ref(encidx) = (0 until n).map(j => vec(j) * mat(encidx)(j)).sum
  }

  // split vec and mat block-wise
  //
  // blockvec(blockid)(rowid)(pixpos)
  val blockvec : Array[Array[Array[Long]]] =
    Array.tabulate(nblocks) { blockpos =>
      Array.tabulate(h) { rowpos =>
        vec.slice(rowpos * w + blockpos * blockwidth, rowpos * w + (blockpos + 1) * blockwidth)
      }
    }

  // blockmat(encid)(blockid)(rowid)(pixpos)
  val blockmat :  Array[Array[Array[Array[Long]]]] =
    Array.tabulate(m) { encpos =>
      Array.tabulate(nblocks) { blockpos =>
        Array.tabulate(h) { rowpos =>
          mat(encpos).slice(rowpos * w + blockpos * blockwidth, rowpos * w + (blockpos + 1) * blockwidth)
        }
      }
    }

  def dumpVec(): Unit = {
    println("[Flatten Input Vec]")
    println("  " + vec.mkString("[", ", ", "]"))
    println()
    println("[Grouped Input Vec]")
    for ((b,bid) <- blockvec.zipWithIndex) {
      println(s" [block${bid}]")
      for ((r,rid) <- b.zipWithIndex) {
        print(s"   $rid : ")
        println(r.mkString("[", ", ", "]"))
      }
    }
    println()
  }

  def dumpMat(): Unit = {
    println("[Transposed IEM]")
    for ((r, rid) <- mat.zipWithIndex) {
      print(s"   $rid : ")
      println(r.mkString("[", ", ", "]"))
    }
    println()
    println("[Grouped IEM]")
    for ((e, eid) <- blockmat.zipWithIndex) {
      println(s" [enc${eid}]")
      for ((b, bid) <- e.zipWithIndex) {
        println(s"   [block${bid}]")
        for ((r, rid) <- b.zipWithIndex) {
          print(s"      $rid : ")
          println(r.mkString("[", ", ", "]"))
        }
      }
    }
  }


  def calcRefFromBlocks() : Array[Long] = {
    var tmp: Array[Long] = Array.fill(m)(0)

    for (eid <- 0 until m) {
      for (bid <- 0 until nblocks) {
        for (rid <- 0 until h) {
          tmp(eid) +=
            Array.tabulate(blockwidth) { i =>
              blockvec(bid)(rid)(i) * blockmat(eid)(bid)(rid)(i)
            }.sum
        }
      }
    } // blockmat(encid)(blockid)(rowid)(pixpos)
    tmp
  }


  def validateRef() : Boolean = {
    var tmp = true
    val reffromblocks = calcRefFromBlocks()
    for (eid <- 0 until m) {
      if (ref(eid) != reffromblocks(eid)) {
        tmp = false
      }
    }
    tmp
  }
  def printInfo() : Unit = {
    println(s"PCATestData info: w=$w h=$h n=$n m=$m vecbw=$vecbw matbw=$matbw nblocks=$nblocks validate=${validateRef()}")
  }
}


class PCATestDataSpec extends AnyFlatSpec {
  behavior of "PCATestData"

  "Default config" should "pass" in {
    val t = new PCATestData()
    t.printInfo()
    assert(t.validateRef())
  }



  "Large config" should "pass" in {
    val t = new PCATestData(w=192, h=168, m=100, vecbw=12, matbw=8, nblocks=8)
    t.printInfo()
    assert(t.validateRef())
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
