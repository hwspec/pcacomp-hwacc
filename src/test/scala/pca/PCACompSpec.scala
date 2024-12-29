
package pca

import chiseltest._
import scala.util.Random
import common.CommonSpecConfig

class PCACompSpec extends CommonSpecConfig {
  behavior of "PCAComp"
  val ninpixels = 16 // 4096
  val ncolumns = 8
  val pxbw = 12
  val maxenc = 4 // 30
  val encbw = 8

  "encmat load" should "pass" in {
    test(new PCAComp(ninpixels, ncolumns, pxbw, maxenc, encbw)) { dut =>
      // set
      dut.io.setencdata.poke(true)
      for(col <- 0 until ncolumns) {
        dut.io.encpos.poke(col)
        for(b <- 0 until ninpixels) {
          for (i <- 0 until maxenc) {
            val v = (col + b + i) % (1 << (encbw - 1))
            dut.io.encdata(b)(i).poke(v)
          }
        }
        dut.clock.step()
      }
      dut.io.setencdata.poke(false)

      // verify
      dut.io.getencdata.poke(true)
      for(col <- 0 until ncolumns) {
        dut.io.encpos.poke(col)
        dut.clock.step() // due to syncreadram
        for(b <- 0 until ninpixels) {
          for (i <- 0 until maxenc) {
            val v = (col + b + i) % (1 << (encbw - 1))
            dut.io.encdata(b)(i).poke(v)
            dut.io.encdataverify(b)(i).expect(v)
//            val ret = dut.io.encdataverify(b)(i).peekInt()
//            println(f"[$col:$b:$i] $v should be $ret")
          }
        }
        dut.clock.step()
      }
      dut.io.getencdata.poke(false)
    }
  }
}
