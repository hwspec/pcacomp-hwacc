package scratch

import chisel3.simulator.EphemeralSimulator._
//import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

import scala.util.Random

class CompareRedSpec extends AnyFlatSpec  {
  behavior of "CompareRed"

  val n = 4
  val inbw = 8
  val rng = new Random(11)
  val testdata = List.tabulate(n) { i =>
    rng.nextInt((1<<(inbw-1)))
  }

  "basic test" should "pass" in {
    simulate(new CompareRed(n, inbw)) { dut =>
      val ref = testdata.reduce(_ + _)
      testdata.zipWithIndex.foreach{ case(v,idx) =>
        dut.io.in(idx).poke(v)
      }
      val o1 : BigInt = dut.io.out1.peek().litValue
      val o2 : BigInt = dut.io.out2.peek().litValue
      println(s"$o1 $o2 $ref")
      assert(o1 == ref, s"o1:${o1} did not match with ref:${ref}")
      assert(o1 == o2, s"o1:${o1} did not match with o2:${o2}")
    }
  }
}


class VMulRedSpec extends AnyFlatSpec {
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
    simulate(new VMulRed(n=npx, nbits_px=nbits_px, nbits_iem=nbits_iem))
    //.withAnnotations(Seq(VerilatorBackendAnnotation))
    { c =>
      inpx.zip(iniem).zipWithIndex.foreach { case ((px, iem), idx) =>
        c.io.in_px(idx).poke(px)
        c.io.in_iem(idx).poke(iem)
      }
      //println(f"out=${c.io.out.peek().litValue}  expected=${ref}")
      c.io.out.expect(ref)
    }
  }

  "VMulRed max" should "pass" in testLoop(input_px_max, input_iem_max)
  "VMulRed rnd" should "pass" in testLoop(input_px_rnd, input_iem_rnd)

  "Fixed" should "pass" in {
    val N = 4
    val PXBW  = 4
    val IEMBW = 6

    val pxdata = Seq(11, 11, 6, 3)
    val iemdata = Seq(8, -7, -31, 26)
    val ref = pxdata.zip(iemdata).map {case (a,b) => a * b}.reduce(_ + _)

    simulate(new VMulRed(n=N, nbits_px=PXBW, nbits_iem=IEMBW))
    { c =>
      pxdata.zip(iemdata).zipWithIndex.foreach { case ((px, iem), idx) =>
        c.io.in_px(idx).poke(px)
        c.io.in_iem(idx).poke(iem)
      }
      //println(f"out=${c.io.out.peek().litValue}  expected=${ref}")
      c.io.out.expect(ref)
    }
  }
}
