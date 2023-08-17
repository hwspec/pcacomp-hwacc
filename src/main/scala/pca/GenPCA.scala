package pca

//import chisel3.stage.ChiselStage
import _root_.circt.stage.ChiselStage // for 5.0.0

object GenPCA {
  def main(args: Array[String]): Unit = {
    // option handling
    val opts = Array("--disable-all-randomization",
       "--strip-debug-info",
       "--split-verilog",
       "-o=generated")

    ChiselStage.emitSystemVerilog(new VMulRed(), firtoolOpts = opts) // 5.0.0
  }
}
