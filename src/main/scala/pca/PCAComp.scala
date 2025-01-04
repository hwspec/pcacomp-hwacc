package pca

import chisel3._
import chisel3.util._
import common.GenVerilog

//
// design parameters for PCA-based compressor
//
// * input images
// blockid: the PCA encoder block ID
// pxbw: pixel bit width (unsigned int)
// width: the number of the image columns that are shifted every cycles
// height : per-block height
//
// * pca
// encsize : the maximum encoding size
// encbw : encoding bit width (signed int)
// qfactors: quantization factor (vector) needed on chip?
//
class PCACompBlock(
                    blockid: Int = 0,
                    // pixel-sensor params
                    pxbw: Int = 12, width: Int = 8, height: Int = 8,

                    // PCA params
                    encsize: Int = 30, // the maximum encoding size
                    encbw : Int = 8, // encoding bit width (signed int)

                    // computing/memory access parallelisms
                    nbanks : Int = 8  // up to width * height
                  ) extends Module {

  val ninpixels = (width * height)
  val npixelgroups = ninpixels/nbanks

  require((ninpixels % nbanks) == 0)
  require(npixelgroups >= nbanks)

  // println(f"ninpixels=$ninpixels npixelgroups=$npixelgroups")

  val io = IO(new Bundle {
    val in = Flipped(Decoupled(Vec(ninpixels, UInt(pxbw.W))))
    val out = Decoupled(Vec(encsize, SInt(encbw.W))) // compressed data
    //
    val setencdata = Input(Bool()) // load encdata into encmat at encpos
    val getencdata = Input(Bool()) // load encdata into encmat at encpos
    val pxgrouppos = Input(UInt(log2Ceil(npixelgroups).W))
    val encdata = Input(Vec(nbanks, Vec(encsize, SInt(encbw.W))))
    val encdataverify = Output(Vec(nbanks, Vec(encsize, SInt(encbw.W))))
  })

  io.in.ready := false.B
  io.out.valid := false.B
  for (i <- 0 until encsize) io.out.bits(i) := 0.S

  val encmat = Seq.fill(nbanks) {SyncReadMem(npixelgroups, Vec(encsize, SInt(encbw.W)))}
  when (io.setencdata) {
    for (b <- 0 until nbanks) {
      encmat(b).write(io.pxgrouppos, io.encdata(b))
    }
  }
  when (io.getencdata) {
    for (b <- 0 until nbanks) {
      io.encdataverify(b) := encmat(b).read(io.pxgrouppos)
    }
  }.otherwise {
    for (b <- 0 until nbanks) {
      for (i <- 0 until encsize) {
        io.encdataverify(b)(i) := 0.S
      }
    }
  }
}

object PCACompBlock extends App {
  val blockid : Int = 0
  val pxbw: Int = 12
  val width: Int = 16
  val height: Int = 16
  val encsize: Int = 30
  val encbw : Int = 8
  val nbanks : Int = 8

  GenVerilog.generate(new PCACompBlock(
    blockid = blockid,
    pxbw = pxbw,
    width = width,
    height = height,
    encsize = encsize,
    encbw = encbw,
    nbanks = nbanks
  ))
}
