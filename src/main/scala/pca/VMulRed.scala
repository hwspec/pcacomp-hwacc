package pca

import chisel3._
import chisel3.util._
import common.GenVerilog


class VMulRed(n: Int = 4, nbits_px: Int = 8, nbits_iem: Int = 8) extends Module {
  require(n>=4 && (n&(n-1))==0) // check see if n is a power of two number that is larger than 4

  override def desiredName = s"VMulRed_n${n}_px${nbits_px}_iem${nbits_iem}"

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
  io.out := mulres.reduce(_ + _)
}

object VMulRed extends App {
  val n=256
  val nbits_px=8
  val nbits_iem=8

  GenVerilog.generate(new VMulRed(n,nbits_px,nbits_iem))
}
