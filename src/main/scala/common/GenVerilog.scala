// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2025, UChicago Argonne, LLC.
// Main author: Kazutomo Yoshii <kazutomo@anl.gov>. See LICENSE in project root.

package common

import chisel3._
import _root_.circt.stage.ChiselStage // 5.x. _root_ to avoid a potential conflict

object GenVerilog {
  val args_a = Array(
    "--target-dir", "generated"
  )
  val firtoolOpts_a = Array(
    "--disable-all-randomization",
    // "--strip-debug-info",  // Commented out to preserve printf statements for debugging
    "--lowering-options=disallowLocalVariables,disallowPackedArrays",
    "--verilog",
  )
  def apply(gen: => RawModule) : Unit = {
    generate(gen)
  }
  def generate(gen: => RawModule) : Unit = {
    ChiselStage.emitSystemVerilogFile(gen,
      args = args_a,
      firtoolOpts = firtoolOpts_a)
  }
}
