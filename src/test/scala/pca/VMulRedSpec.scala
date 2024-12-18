package pca

import chiseltest._
import scala.util.Random

//import org.scalatest.flatspec.AnyFlatSpec
//import org.scalatest.tools.Runner

import common.CommonSpecConfig

class VMulRedSpec extends CommonSpecConfig {
// extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "VMulRed"

  val npx = 128*8
  val nbits_px = 8  // unsigned
  val nbits_iem = 8 // signed

  val maxv_px = (1<<(nbits_px)) - 1
  val minv_iem = -(1<<(nbits_iem-1))
  val maxv_iem = (1<<(nbits_iem-1)) - 1

  def rndRange(minv: BigInt, maxv: BigInt): BigInt = { // minv, maxv inclusive
    BigInt((maxv-minv).bitLength, Random) + minv
  }

  def calmulredref(pxin: List[BigInt], iemin: List[BigInt]): BigInt = {
      pxin.zip(iemin).map { case (a, b) => a * b }.sum
  }

  val input_px_max  = List.fill(npx) {BigInt(maxv_px)}
  val input_iem_max = List.fill(npx) {BigInt(minv_iem)}

  val input_px_rnd = List.fill(npx) {rndRange(0, maxv_px)}
  val input_iem_rnd = List.fill(npx) {rndRange(minv_iem, maxv_iem)}

  def testLoop(inpx: List[BigInt], iniem: List[BigInt]) : Unit = {
    val ref = calmulredref(inpx, iniem)
    test(new VMulRed(n=npx, nbits_px=nbits_px, nbits_iem=nbits_iem))
    //.withAnnotations(Seq(VerilatorBackendAnnotation))
    { c =>
      inpx.zip(iniem).zipWithIndex.foreach { case ((px, iem), idx) =>
        c.io.in_px(idx).poke(px)
        c.io.in_iem(idx).poke(iem)
      }
      println(f"out=${c.io.out.peek().litValue}  expected=${ref}")
      c.io.out.expect(ref)
    }
  }

  "VMulRed max" should "pass" in testLoop(input_px_max, input_iem_max)
  "VMulRed rnd" should "pass" in testLoop(input_px_rnd, input_iem_rnd)
}
