package pca

import chisel3.util._

import scala.util.Random

case class PCAConfig(val w: Int = 12,  val h : Int = 3, val pxbw: Int = 9,  // pixel sensor params
                             val m: Int = 7,   val encbw: Int = 8,  // encoding params, note: n = w * h
                             val nblocks : Int = 3, // parallel blocks
                             val seed : Option[Int] = Some(123))

object PCAConfigPresets {
  val default = PCAConfig()
  val large = PCAConfig(w=192, h=168, m=100, pxbw=12, nblocks=8)
}
