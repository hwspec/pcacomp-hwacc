package scratch

import chisel3._
import chiseltest._
import chiseltest.formal.{BoundedCheck, Formal}
import org.scalatest.flatspec.AnyFlatSpec
import java.lang.{Float => javaFloat}
import scala.util.Random

class FPTestSpec extends AnyFlatSpec with ChiselScalatestTester with Formal {
  behavior of "FPtest"
  val debug = true
  val nrndtests = 20
  val rnd = new Random()
  val fixedtestdata: List[Float] = List(0f, 1f, -1f, 2f)
  val rndtestdata: List[Float] = List.fill(nrndtests) { rnd.between(-1.0e4f, 1.0e4f) }
  val testdata :List[Float] = fixedtestdata.concat(rndtestdata)

  // due to the lack of 32-bit unsigned int in Scala, we need to use Long
  def Float2Long(v: Float): Long = javaFloat.floatToRawIntBits(v) & 0xffffffffL // note 'L' is needed
  def Long2Float(v: Long): Float = javaFloat.intBitsToFloat(v.toInt)

  // this just does convert IEEE float into RecFN and back to IEEE float
  "FPTest" should "pass" in {
    test(new FPTest) { c =>
      for (d <- testdata) {
        c.io.in.poke(Float2Long(d))
        val result = c.io.out.peek().litValue.toLong // litValue returns BigInt
        val resultFloat = Long2Float(result)
        if (debug) println(f"input=$d  output=$resultFloat")
        c.io.out.expect(Float2Long(d))
      }
    }
  }

  def testFPOPTest(m: FPOPTest.Mode): Unit = {
    test(new FPOPTest(mode=m)) { c =>
      for (d <- testdata.sliding(2)) {
        val a = d.head
        val b = d.tail.head
        val expected = m match {
          case FPOPTest.MUL => a * b
          case FPOPTest.SUB => a - b
          case _ => a + b
        }
        c.io.in_a.poke(Float2Long(a))
        c.io.in_b.poke(Float2Long(b))
        val result = c.io.out.peek().litValue.toLong
        val resultFloat = Long2Float(result)
        if (debug) {
          val opstr = m match {
            case FPOPTest.MUL => "*"
            case FPOPTest.SUB => "-"
            case _ => "+"
          }
          println(f"$a $opstr $b should equal to $expected: $resultFloat")
        }
        c.io.out.expect(Float2Long(expected))
      }
    }
  }

  "FPOPTest ADD" should "pass" in testFPOPTest(FPOPTest.ADD)

  "FPOPTest SUB" should "pass" in testFPOPTest(FPOPTest.SUB)

  "FPOPTest MUL" should "pass" in testFPOPTest(FPOPTest.MUL)
}
