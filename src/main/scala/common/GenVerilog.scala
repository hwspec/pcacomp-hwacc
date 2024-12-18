package common

import chisel3._
import _root_.circt.stage.ChiselStage // 5.x. _root_ to avoid a potential conflict

object GenVerilog {
  val args_a = Array(
    "--target-dir", "generated"
  )
  val firtoolOpts_a = Array(
    "--disable-all-randomization",
    "--strip-debug-info",
    "--lowering-options=disallowLocalVariables,disallowPackedArrays",
    "--verilog",
  )

  def generate(gen: => RawModule) : Unit = {
    ChiselStage.emitSystemVerilogFile(gen,
      args = args_a,
      firtoolOpts = firtoolOpts_a)
  }
}
