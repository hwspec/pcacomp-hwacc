package pca

import chisel3._
import chisel3.util._
import common.GenVerilog

class PCAComp(ninpixels: Int = 4096, pxbw: Int = 12, maxenc: Int = 30, encbw: Int = 8 ) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(Vec(ninpixels, UInt(pxbw.W))))
    val out = Decoupled(Vec(maxenc, SInt(encbw.W)))
    //
    val loadenc = Input(Bool()) // load encdata into encmat at encpos
    val encpos  = Input(UInt(log2Ceil(maxenc).W))
    val encdata = Input(Vec(ninpixels, UInt(pxbw.W)))
  })

  io.in.ready := false.B
  io.out.valid := false.B
  for (i <- 0 until maxenc) io.out.bits(i) := 0.S
}


object PCAComp extends App {
  GenVerilog.generate(new PCAComp)
}
