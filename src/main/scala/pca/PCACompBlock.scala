package pca

import chisel3._
import chisel3.util._
import common.GenVerilog

class PCACompBlock(
                    blockid: Int = 0, // the PCA encoder block ID
                    // pixel-sensor params. the width and height of a block
                    pxbw: Int = 12, width: Int = 8, height: Int = 8,

                    // PCA params
                    encsize: Int = 30, // the maximum encoding size
                    encbw : Int = 8, // encoding bit width (signed int)
                    // qfactors: quantization factor (vector) needed on chip?

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
  for (i <- 0 until encsize) io.out.bits(i) := 0.S

  // encoding matrix
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

  //
  object PCACompState extends ChiselEnum {
    val Idle, InProcessing, Done = Value
  }
  import PCACompState._

  val stateReg = RegInit(Idle)

  val pixelReg = RegInit(VecInit(Seq.fill(ninpixels)(0.U(pxbw.W))))
  val resReg = RegInit(VecInit(Seq.fill(encsize)(0.S(encbw.W))))

  io.in.ready := false.B
  io.out.valid := false.B

  switch(stateReg) {
    is(Idle) {
      io.in.ready := true.B
      when(io.in.valid) {
        for (i <- 0 until ninpixels) {
          pixelReg(i) := io.in.bits(i)
        }
        for (i <- 0 until encsize) {
          resReg(i) := 0.S
        }
        stateReg := InProcessing
      }
    }
    is(InProcessing) {
      stateReg := Done
    }
    is(Done) {
      stateReg := Idle
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
