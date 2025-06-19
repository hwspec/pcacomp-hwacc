package pca

import chisel3.util._

import scala.util.Random

/**
 * PCA testdata generation
 *
 * vec : row-vector with the size n.
 * mat : matrix with n rows and m cols.
 *
 *
 */
class PCATestData(cfg: PCAConfig = PCAConfigPresets.default) {
  val n = cfg.w * cfg.h
  val resbw = cfg.pxbw + cfg.encbw + log2Ceil(n)
  require(resbw < 64)

  val blockwidth = cfg.w / cfg.nblocks

  val rnd = cfg.seed match {
    case Some(v) => new Random(v)
    case _ =>  new Random()
  }

  val img : Array[Array[Long]] = Array.fill(cfg.w, cfg.h) {
    rnd.nextInt(1 << cfg.pxbw)
  }
  val vec: Array[Long] = img.flatten

  // mat is a transposed invenc matrix
  val mat: Array[Array[Long]] = Array.fill(cfg.m, n) {
    val tmp = 1 << cfg.encbw
    rnd.between(-tmp, tmp)
  }

  val ref: Array[Long] = Array.fill(cfg.m)(0.toLong)
  for (encidx <- 0 until cfg.m) {
    ref(encidx) = (0 until n).map(j => vec(j) * mat(encidx)(j)).sum
  }

  // split vec and mat block-wise
  //
  // blockvec(blockid)(rowid)(pixpos)
  val blockvec : Array[Array[Array[Long]]] =
    Array.tabulate(cfg.nblocks) { blockpos =>
      Array.tabulate(cfg.h) { rowpos =>
        vec.slice(rowpos * cfg.w + blockpos * blockwidth, rowpos * cfg.w + (blockpos + 1) * blockwidth)
      }
    }

  // blockmat(encid)(blockid)(rowid)(pixpos)
  val blockmat :  Array[Array[Array[Array[Long]]]] =
    Array.tabulate(cfg.m) { encpos =>
      Array.tabulate(cfg.nblocks) { blockpos =>
        Array.tabulate(cfg.h) { rowpos =>
          mat(encpos).slice(rowpos * cfg.w + blockpos * blockwidth, rowpos * cfg.w + (blockpos + 1) * blockwidth)
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
    var tmp: Array[Long] = Array.fill(cfg.m)(0)

    for (eid <- 0 until cfg.m) {
      for (bid <- 0 until cfg.nblocks) {
        for (rid <- 0 until cfg.h) {
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
    for (eid <- 0 until cfg.m) {
      if (ref(eid) != reffromblocks(eid)) {
        tmp = false
      }
    }
    tmp
  }
  def printInfo() : Unit = {
    println(s"PCATestData info: w=$cfg.w h=$cfg.h n=$n m=$cfg.m vecbw=$cfg.pxbw matbw=$cfg.encbw nblocks=$cfg.nblocks validate=${validateRef()}")
  }
}
