package pca

import chisel3.util._

import scala.util.Random

case class PCAConfig(val w: Int = 12,  val h : Int = 3, val pxbw: Int = 9,  // pixel sensor params
                     val m: Int = 7,   val encbw: Int = 8,  // encoding params, note: n = w * h
                     val nblocks : Int = 3, // parallel blocks
                     val seed : Option[Int] = None,
                     val nonegative : Boolean = false
                    )

object PCAConfigPresets {
  val default = PCAConfig()
  val small  = PCAConfig(w=12,  h=2,   m=2,   pxbw=5, seed=Some(123), nonegative = true)
  val medium = PCAConfig(w=192, h=168, m=50,  pxbw=12, nblocks=8)
  val large  = PCAConfig(w=192, h=168, m=100, pxbw=12, nblocks=8)
  //
  val cfg1 = PCAConfig(w=24, h=24, pxbw=12, m=25, nblocks = 4)
}
