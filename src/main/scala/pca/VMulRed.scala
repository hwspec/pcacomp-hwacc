package pca

import chisel3._
import chisel3.util._
import common.GenVerilog

class LocalRedStage(n: Int = 4, inbw: Int = 8) extends Module {
  require(n >= 2 && (n & (n - 1)) == 0) // check see if n is a power of two number that is larger than 4

  val io = IO(new Bundle {
    val in  = Input(Vec(n, SInt(inbw.W)))
    val out = Output(Vec(n/2, SInt((inbw+1).W)))
  })

  for(i <- 0 until n/2) {
    io.out(i) := io.in(i*2) +& io.in(i*2+1)
  }
}

object LocalRedStage extends App {
  GenVerilog.generate(new LocalRedStage(n=4))
}

class LocalRedRec(n: Int = 4, inbw: Int = 8) extends Module {
  require(n >= 2 && (n & (n - 1)) == 0) // check see if n is a power of two number that is larger than 4

  override def desiredName = s"LocalRedRec_n${n}_inbw${inbw}"

  val nstages = log2Ceil(n)
  val outbw = inbw + nstages
  val io = IO(new Bundle {
    val in  = Input(Vec(n, SInt(inbw.W)))
    val out = Output(SInt(outbw.W))
  })

  val stages = Array.tabulate(nstages) { i =>
    Module(new LocalRedStage(n>>i, inbw+i))
  }

  // the first stage input wiring
  for(i <- 0 until n)  stages(0).io.in(i) := io.in(i)

  // connect output to input
  for(s <- 1 until nstages) {
    for(i <- 0 until n>>s) {
      stages(s).io.in(i) := stages(s-1).io.out(i)
    }
  }

  // the last stage output writing
  io.out := stages(nstages-1).io.out(0)
}

class LocalRedBuiltIn(n: Int = 4, inbw: Int = 8) extends Module {
  require(n >= 2 && (n & (n - 1)) == 0) // check see if n is a power of two number that is larger than 4

  override def desiredName = s"LocalRedBuiltIn_n${n}_inbw${inbw}"

  val nstages = log2Ceil(n)
  val outbw = inbw + nstages
  val io = IO(new Bundle {
    val in = Input(Vec(n, SInt(inbw.W)))
    val out = Output(SInt(outbw.W))
  })

  io.out := io.in.reduce(_ +& _) // the +& operation does extend the bit width
}

object LocalRed extends App {
  val bw = 8

  List(4, 8, 16, 32, 64, 128).foreach { n =>
    GenVerilog.generate(new LocalRedRec(n, bw))
    GenVerilog.generate(new LocalRedBuiltIn(n, bw))
  }
}


class CompareRed(n: Int = 4, inbw: Int = 8) extends Module {
  require(n >= 2 && (n & (n - 1)) == 0) // check see if n is a power of two number that is larger than 4

  val nstages = log2Ceil(n)
  val outbw = inbw + nstages
  val io = IO(new Bundle {
    val in = Input(Vec(n, SInt(inbw.W)))
    val out1 = Output(SInt(outbw.W))
    val out2 = Output(SInt(outbw.W))
  })

  io.out1 := io.in.reduce(_ +& _)
  val red = Module(new LocalRedRec(n, inbw))
  red.io.in := io.in
  io.out2 := red.io.out
}

class VMulRed(n: Int = 4, nbits_px: Int = 8, nbits_iem: Int = 8) extends Module {
  require(n>=4 && (n&(n-1))==0) // check see if n is a power of two number that is larger than 4

  // override def desiredName = s"VMulRed_n${n}_px${nbits_px}_iem${nbits_iem}"

  val nbits_mul = (nbits_px + nbits_iem)
  val nreds = log2Ceil(n)
  val nbits_out = nbits_mul + nreds
  val io = IO(new Bundle {
    val in_px  = Input(Vec(n, UInt(nbits_px.W)))  // pixel value
    val in_iem = Input(Vec(n, SInt(nbits_iem.W))) // quantized inversed encoding matrix
    val out = Output(SInt(nbits_out.W))
  })

  val mulres = Wire(Vec(n, SInt(nbits_out.W)))
  for (i <- 0 until n) mulres(i) := io.in_iem(i) * io.in_px(i)

  // This implementation simply creates wires with nbits_out.
  // There is room for optimizing the reduction stage by creating wires
  // with the bit width needed for each stage. The first stage only needs
  // nbits_mul + 1, for example.
  io.out := mulres.reduce(_ + _)
}

object VMulRed extends App {
  val n=256
  val nbits_px=8
  val nbits_iem=8

  GenVerilog.generate(new VMulRed(n,nbits_px,nbits_iem))
}
