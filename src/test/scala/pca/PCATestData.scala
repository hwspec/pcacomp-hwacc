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
class PCATestData(val cfg: PCAConfig = PCAConfigPresets.default) {
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
    val tmp = 1 << (cfg.encbw-1)
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
  val blockmat : Array[Array[Array[Array[Long]]]] =
    Array.tabulate(cfg.m) { encpos =>
      Array.tabulate(cfg.nblocks) { blockpos =>
        Array.tabulate(cfg.h) { rowpos =>
          mat(encpos).slice(rowpos * cfg.w + blockpos * blockwidth, rowpos * cfg.w + (blockpos + 1) * blockwidth)
        }
      }
    }

  def getPerEncBlockRow2Bits(encid: Int, blockid: Int, rowid : Int) : BigInt = {
    convArray2BigInt(blockmat(encid)(blockid)(rowid), cfg.encbw)
  }

  def calcRefPerBlock() : Array[Array[Long]] = {
    val tmp: Array[Array[Long]] = Array.fill(cfg.nblocks, cfg.m)(0)

    for (bid <- 0 until cfg.nblocks) {
      for (eid <- 0 until cfg.m) {
        for (rid <- 0 until cfg.h) {
          tmp(bid)(eid) +=
            Array.tabulate(blockwidth) { i =>
              blockvec(bid)(rid)(i) * blockmat(eid)(bid)(rid)(i)
            }.sum
        }
      }
    }
    tmp
  }
  val blockref : Array[Array[Long]] = calcRefPerBlock()

  def calcRef() : Array[Long] = {
    val blocks: Array[Array[Long]] = calcRefPerBlock()
    val tmp: Array[Long] = Array.fill(cfg.m)(0)

    for (i <- 0 until cfg.m) {
      for (j <- 0 until cfg.nblocks) {
        tmp(i) += blocks(j)(i)
      }
    }
    tmp
  }
  val reffromblocks : Array[Long] = calcRef()

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
    // val reffromblocks = calcRefFromBlocks()
    for (eid <- 0 until cfg.m) {
      if (ref(eid) != reffromblocks(eid)) {
        tmp = false
      }
    }
    tmp
  }
  def printInfo() : Unit = {
    println(s"[PCATestData info]")
    println(s"  pixel: w=${cfg.w} h=${cfg.h} pxbw=${cfg.pxbw}")
    println(s"  enc:   n=$n m=${cfg.m} matbw=${cfg.encbw}")
    println(s"  block: nblocks=${cfg.nblocks} blockwidth=${blockwidth}")
  }

  // utility functions that do not depend on any property in this class
  def convArray2BigInt(a: Array[Long], bw: Int) : BigInt = {
    var tmp = BigInt(0)
    val len = a.length
    val mask = (BigInt(1) << bw) - 1
    for(i <- 0 until len) {
      tmp |= (a(i) & mask) << (i * bw)
    }
    tmp
  }

  def convBigInt2Array(in: BigInt, bw: Int, n: Int) : Array[Long] = {
    val mask = (1.toLong << bw) - 1
    val sbit = 1.toLong << (bw-1)
    val ret : Array[Long] = Array.tabulate(n) {
      i => {
        val masked = (in >> (i*bw)).toLong & mask
        if((sbit&masked)> 0) -(mask - masked + 1) else masked
      }
    }
    ret
  }

  def v2bin(v: Long, bw: Int): String =
    java.lang.Long.toBinaryString(v).reverse.padTo(bw, '0').reverse

}

object PCATestData extends App {
  val rnd = new Random()

  val t = new PCATestData(PCAConfigPresets.large)
  t.printInfo()
  t.validateRef()

  val data = t.blockmat(0)(0)(0)
  //val data = Array.fill(16) { (rnd.nextInt(200) - 100).toLong }
  // val data = Array(0L, 1L, -3L, 5L)
  val busdata = t.convArray2BigInt(data, t.cfg.encbw)
  val data2  = t.convBigInt2Array(busdata, t.cfg.encbw, data.length)
  val busbw = t.cfg.encbw * data.length
  // println(t.v2bin(busdata.toLong, busbw))
  val validateData = data.zip(data2).map { case (one, two) =>
    one == two
  }.reduce(_ & _)
  //println(data.mkString(" "))
  //println(data2.mkString(" "))
  assert(validateData, "failed to test the Array-BigInt conversion")
  println("test passed")
}
