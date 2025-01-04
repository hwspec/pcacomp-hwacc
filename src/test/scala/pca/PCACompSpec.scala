
package pca

import chiseltest._
import scala.util.Random
import common.CommonSpecConfig

class PCACompBlockSpec extends CommonSpecConfig {
  behavior of "PCACompBlock"

  val blockid : Int = 0
  val pxbw: Int = 12
  val width: Int = 8
  val height: Int = 8
  val encsize: Int = 10
  val encbw : Int = 8
  val nbanks : Int = 8

  val ninpixels = width * height
  val npixelgroups = ninpixels/nbanks

  "encmat load" should "pass" ignore {
    test(new PCACompBlock(
      blockid = blockid,
      pxbw = pxbw,
      width = width,
      height = height,
      encsize = encsize,
      encbw = encbw,
      nbanks = nbanks )) { dut =>
      // set
      println("Loading...")
      dut.io.setencdata.poke(true)
      for(p <- 0 until npixelgroups) {
        dut.io.pxgrouppos.poke(p)
        for (b <- 0 until nbanks) {
          for (i <- 0 until encsize) {
            val v = (p + b + i) % (1 << (encbw - 1))
            dut.io.encdata(b)(i).poke(v)
          }
        }
      }
      dut.clock.step()
      dut.io.setencdata.poke(false)
      println()

      println("Verifying...")
      dut.io.getencdata.poke(true)
      for(p <- 0 until npixelgroups) {
        dut.io.pxgrouppos.poke(p)
        dut.clock.step()
        for(b <- 0 until nbanks) {
          print(f"$b  ")
          for (i <- 0 until encsize) {
            val v = (p + b + i) % (1 << (encbw - 1))
            dut.io.encdataverify(b)(i).expect(v)
            val ret = dut.io.encdataverify(b)(i).peekInt()
            println(f"[$p:$b:$i] $v should be $ret")
          }
          dut.clock.step()
        }
      }
      println()

      dut.io.getencdata.poke(false)
      println("done")
    }
  }
}
