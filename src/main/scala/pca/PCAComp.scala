package pca

import chisel3._
import chisel3.util._
import common.GenVerilog

//
// design parameters for PCA-based compressor
//
// * input images
// pxbw: pixel bit width (unsigned int)
// width : the input image width
// height : its height
// ngroups: shift groups
// nblocks: the number of sub-PCA blocks
//
// * pca
// encsize : the maximum encoding size
// encbw : encoding bit width (signed int)
// qfactors: quantization factor (vector) needed on chip?
//
class PCACompTop(
                // pixel-sensor params
                pxbw: Int = 12, width: Int = 256, height: Int = 256,
                ngroups: Int = 16,
                nblocks: Int = 8, // the number of sub-PCA blocks
                // PCA params
                encsize: Int = 30, // the maximum encoding size
                encbw : Int = 8 // encoding bit width (signed int)
                ) extends Module {

}

object PCACompTop extends App {
  val pxbw: Int = 12
  val width: Int = 256
  val height: Int = 256
  val ngroups: Int = 16
  val nblocks: Int = 8
  val encsize: Int = 30
  val encbw : Int = 8

  GenVerilog.generate(new PCACompTop(
    pxbw = pxbw,
    width = width,
    height = height,
    ngroups = ngroups,
    nblocks = nblocks,
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
