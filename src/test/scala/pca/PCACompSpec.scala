
package pca

import chiseltest._
import scala.util.Random
import common.CommonSpecConfig

class PCACompSpec extends CommonSpecConfig {
  behavior of "PCAComp"
  val ninpixels = 16 // 4096
  val pxbw = 12
  val maxenc = 4 // 30
  val encbw = 8

  "encmat load" should "pass" in {
    test(new PCAComp(ninpixels, pxbw, maxenc, encbw)) { dut =>
      // set
      dut.io.loadenc.poke(true)
      for(pos <- 0 until ninpixels) {
        dut.io.encpos.poke(pos)
        for(i <- 0 until maxenc) {
          val v = (pos+i) % (1<<(encbw-1))
          dut.io.encdata(i).poke(v)
        }
        dut.clock.step()
      }
      dut.io.loadenc.poke(false)

      // verify
      dut.io.verifyenc.poke(true)
      for(pos <- 0 until ninpixels) {
        dut.io.encpos.poke(pos)
        dut.clock.step() // due to syncreadram
        for(i <- 0 until maxenc) {
          val v = (pos+i) % (1<<(encbw-1))
          dut.io.encdataverify(i).expect(v)
          // val ret = dut.io.encdataverify(i).peekInt()
          // println(f"$pos $i $v $ret")
        }
      }
      dut.io.verifyenc.poke(false)
    }
  }
}
