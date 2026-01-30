// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2025, UChicago Argonne, LLC.
// Main author: Kazutomo Yoshii <kazutomo@anl.gov>. See LICENSE in project root.

package pca

import play.api.libs.json._
import scala.io.Source
import scala.util.Using
import java.io.PrintWriter
import java.io.File

/**
 * Export test vectors from PCATestData to JSON format for use in Verilog simulation
 */
object ExportTestVectors extends App {
  val cfgfn = if (args.length > 0) {
    args(0)
  } else {
    sys.env.getOrElse("PCAConfig", "default")
  }

  val outputfn = if (args.length > 1) {
    args(1)
  } else {
    "test_vectors.json"
  }

  val config = if (cfgfn == "default") {
    PCAConfigPresets.cfg1
  } else {
    Using.resource(Source.fromFile(cfgfn)) { src =>
      val json = Json.parse(src.mkString)
      
      val w = (json \ "w").asOpt[Int].getOrElse(12)
      val h = (json \ "h").asOpt[Int].getOrElse(3)
      val pxbw = (json \ "pxbw").asOpt[Int].getOrElse(9)
      val m = (json \ "m").asOpt[Int].getOrElse(7)
      val encbw = (json \ "encbw").asOpt[Int].getOrElse(8)
      val nblocks = (json \ "nblocks").asOpt[Int].getOrElse(3)
      val seed = (json \ "seed").asOpt[Int]
      val nonegative = (json \ "nonegative").asOpt[Boolean].getOrElse(false)
      
      if (w % nblocks != 0) {
        throw new IllegalArgumentException(
          s"w ($w) must be divisible by nblocks ($nblocks)"
        )
      }
      
      PCAConfig(
        w = w,
        h = h,
        pxbw = pxbw,
        m = m,
        encbw = encbw,
        nblocks = nblocks,
        seed = seed,
        nonegative = nonegative
      )
    }
  }

  println(s"Generating test vectors for config: w=${config.w}, h=${config.h}, m=${config.m}, nblocks=${config.nblocks}")
  
  val td = new PCATestData(config)
  td.printInfo()

  // Helper to convert Long to JsValue
  def longToJsValue(l: Long): JsValue = JsNumber(BigDecimal(l))
  
  // Helper to convert BigInt to JsValue (as string to avoid JSON float precision loss)
  def bigIntToJsValue(b: BigInt): JsValue = JsString(b.toString)
  
  // Helper to convert Option[Int] to JsValue
  def optionIntToJsValue(opt: Option[Int]): JsValue = opt.map(i => JsNumber(i): JsValue).getOrElse(JsNull: JsValue)
  
  // Create JSON structure
  val testVectors = Json.obj(
    "config" -> Json.obj(
      "w" -> config.w,
      "h" -> config.h,
      "pxbw" -> config.pxbw,
      "m" -> config.m,
      "encbw" -> config.encbw,
      "nblocks" -> config.nblocks,
      "seed" -> optionIntToJsValue(config.seed),
      "nonegative" -> config.nonegative
    ),
    "reference_output" -> JsArray(td.ref.map(longToJsValue).toSeq),
    "block_reference_outputs" -> JsArray(
      td.blockref.map(block => JsArray(block.map(longToJsValue).toSeq)).toSeq
    ),
    "blocks" -> JsArray(
      (0 until config.nblocks).map { blockid =>
        Json.obj(
          "block_id" -> blockid,
          "input_rows" -> JsArray(
            (0 until config.h).map { rowid =>
              Json.obj(
                "row_id" -> rowid,
                "pixel_data" -> JsArray(td.blockvec(blockid)(rowid).map(longToJsValue).toSeq)
              )
            }.toSeq
          ),
          "iem_data" -> JsObject(
            (0 until config.m).map { encid =>
              (encid.toString, JsObject(
                (0 until config.h).map { rowid =>
                  (rowid.toString, Json.obj(
                    "data" -> JsArray(td.blockmat(encid)(blockid)(rowid).map(longToJsValue).toSeq),
                    "data_as_bigint" -> bigIntToJsValue(td.getPerEncBlockRow2Bits(encid, blockid, rowid))
                  ))
                }.toSeq
              ))
            }.toSeq
          ),
          "expected_output" -> JsArray(td.blockref(blockid).map(longToJsValue).toSeq)
        )
      }.toSeq
    )
  )

  // Write to file
  Using.resource(new PrintWriter(new File(outputfn))) { writer =>
    writer.write(Json.prettyPrint(testVectors))
  }

  println(s"Test vectors exported to: $outputfn")
  println(s"  - Config: ${config.nblocks} blocks, ${config.h} rows per block, ${config.w / config.nblocks} pixels per row")
  println(s"  - ${config.m} principal components")
  println(s"  - Reference output: ${td.ref.length} values")
}

