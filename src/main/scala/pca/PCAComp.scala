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
                    pxbw: Int = 12, width: Int = 16, height: Int = 32,

                    // PCA params
                    encsize: Int = 30, // the maximum encoding size
                    encbw : Int = 8 // encoding bit width (signed int)
                  ) extends Module {
  val ninpixels = (width * height)
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(Vec(ninpixels, UInt(pxbw.W))))
    val out = Decoupled(Vec(encsize, SInt(encbw.W))) // compressed data
    //
    val setencdata = Input(Bool()) // load encdata into encmat at encpos
    val getencdata = Input(Bool()) // load encdata into encmat at encpos
    val encpos = Input(UInt(log2Ceil(ninpixels).W))
    val encdata = Input(Vec(ninpixels, Vec(encsize, SInt(encbw.W))))
    val encdataverify = Output(Vec(ninpixels, Vec(encsize, SInt(encbw.W))))
  })

  io.in.ready := false.B
  io.out.valid := false.B
  for (i <- 0 until encsize) io.out.bits(i) := 0.S

  val encmat = Seq.fill(ninpixels) {SyncReadMem(width, Vec(encsize, SInt(encbw.W)))}
  when (io.setencdata) {
    for (b <- 0 until ninpixels) { // ninpixels banks
      encmat(b)(io.encpos) := io.encdata(b)
    }
  }
  when (io.getencdata) {
    for (b <- 0 until ninpixels) { // ninpixels banks
      io.encdataverify(b) := encmat(b)(io.encpos)
    }
  }.otherwise {
    for (b <- 0 until ninpixels) { // ninpixels banks
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

  GenVerilog.generate(new PCACompBlock(
    blockid = blockid,
    pxbw = pxbw,
    width = width,
    height = height,
    encsize = encsize,
    encbw = encbw
  ))
}

class PCAComp(ninpixels: Int = 256, ncolumns : Int = 16, pxbw: Int = 8, maxenc: Int = 30, encbw: Int = 8 ) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(Vec(ninpixels, UInt(pxbw.W))))
    val out = Decoupled(Vec(maxenc, SInt(encbw.W)))
    //
    val setencdata = Input(Bool()) // load encdata into encmat at encpos
    val getencdata = Input(Bool()) // load encdata into encmat at encpos
    val encpos  = Input(UInt(log2Ceil(ninpixels).W))
    val encdata = Input(Vec(ninpixels,Vec(maxenc, SInt(encbw.W))))
    val encdataverify = Output(Vec(ninpixels,Vec(maxenc, SInt(encbw.W))))
  })

  io.in.ready := false.B
  io.out.valid := false.B
  for (i <- 0 until maxenc) io.out.bits(i) := 0.S

  val encmat = Seq.fill(ninpixels) {SyncReadMem(ncolumns, Vec(maxenc, SInt(encbw.W)))}
  when (io.setencdata) {
    for (b <- 0 until ninpixels) { // ninpixels banks
      encmat(b)(io.encpos) := io.encdata(b)
    }
  }
  when (io.getencdata) {
    for (b <- 0 until ninpixels) { // ninpixels banks
      io.encdataverify(b) := encmat(b)(io.encpos)
    }
  }.otherwise {
    for (b <- 0 until ninpixels) { // ninpixels banks
      for (i <- 0 until maxenc) {
        io.encdataverify(b)(i) := 0.S
      }
    }
  }
}

object PCAComp extends App {
  GenVerilog.generate(new PCAComp)
}
