// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2025, UChicago Argonne, LLC.
// Main author: Kazutomo Yoshii <kazutomo@anl.gov>. See LICENSE in project root.

package pca

import chisel3._
import chisel3.util._
import common.GenVerilog

class PCACompBlock(cfg: PCAConfig = PCAConfigPresets.default,
                   useSyncReadMem : Boolean = true,
                   debugprint: Boolean = true
                  ) extends Module {

  // pixel-sensor params. the width and height of a block
  val ncols: Int = cfg.w // the numbers of the pixel-sensor columns
  val nrows: Int = cfg.h // the numbers of the pixel-sensor rows
  val pxbw: Int  = cfg.pxbw // pixel bit width
  val width: Int = cfg.w / cfg.nblocks // width for this block. ncols%widht == 0
  // PCA params, iem=inverse encoding matrix
  val nmaxpcs  : Int = cfg.m // the max number of principal components
  val iembw    : Int = cfg.encbw // encoding bit width for int, mantissa bit for float
  // other params

  require((ncols % width) == 0)

  private val mulbw = pxbw + iembw // internal use. signed
  val redbw = mulbw + log2Ceil(width) + log2Ceil(nrows) // reduction result
  val databw = iembw
  val busbw = width * databw

  val cfgstr = s"nrows${nrows}_ncols${ncols}_nblocks${cfg.nblocks}_w${width}_pxbw${pxbw}_iembw${iembw}_npcs${nmaxpcs}"

  override def desiredName = s"${this.getClass.getSimpleName}_" + cfgstr

  println(s"Design: " + cfgstr + s" SRAM: depth${nrows}_width${busbw}")

  val io = IO(new Bundle {
    // input : no stall condition for now
    // val rowid = Input(UInt(log2Ceil(nrows).W))
    val rowid = Input(UInt(log2Ceil(nrows).W)) // used for both compute and updateIEM
    val indata = Input(UInt((width*pxbw).W))
    val indatavalid = Input(Bool())

    // output
    val out = Decoupled(UInt((nmaxpcs*redbw).W))

    // initialize memory with the inverse encoding matrix content for this block
    val updateIEM = Input(Bool()) // load imedata into mem
    val verifyIEM = Input(Bool()) // read mem for verification
    val iempos = Input(UInt(log2Ceil(nmaxpcs).W))
    val iemdata = Input(UInt((width*iembw).W))
    val iemdataverify = Output(UInt((width*iembw).W))
  })

  val clk = RegInit(0.U(10.W))
  clk := clk + 1.U

  val indataHoldReg = RegInit(0.U((width*pxbw).W))
  val indatavec = indataHoldReg.asTypeOf(Vec(width, UInt(pxbw.W)))

  io.iemdataverify := 0.U
  io.out.bits := 0.U
  io.out.valid := false.B

//  val mems = Seq.fill(nmaxpcs)(SyncReadMem(nrows, UInt(busbw.W)))

  val mems = Seq.tabulate(nmaxpcs) { id =>
    Module(new SRAM1RW(nrows, busbw, id, useSyncReadMem)) }
  for(i <- 0 until nmaxpcs) {
    mems(i).io.en := true.B
    mems(i).io.we := false.B
    mems(i).io.addr := 0.U
    mems(i).io.wdata := 0.U
  } // enable for now

  val memaddr  = Wire(UInt(log2Ceil(nrows).W))
  val memrdata = Wire(Vec(nmaxpcs, UInt(busbw.W)))

  val iemdataread = Wire(UInt((width*iembw).W))
  iemdataread := 0.U
  io.iemdataverify := iemdataread

  memaddr := io.rowid
  for(i <- 0 until nmaxpcs) {
  //  memrdata(i) := mems(i).read(memaddr, true.B)  // SyncReadMem
    mems(i).io.addr := memaddr
    memrdata(i) := mems(i).io.rdata // assume synchronous behavior

    //    when(io.verifyIEM) {
//      if (debugprint) {
//        printf("read iem%d : data=%d / readaddr=%d\n", i.U, memrdata(i), io.rowpos)
//      }
//    }
  }

  val dataReceivedStageReg = RegInit(false.B)

  when(io.updateIEM) {
    for(i <- 0 until nmaxpcs) {
      when(io.iempos === i.U) {
//        mems(i).write(io.rowid, io.iemdata ) // SyncReadMem
        mems(i).io.we := true.B
        mems(i).io.addr := io.rowid
        mems(i).io.wdata := io.iemdata

//        if(debugprint) printf("update: iempos=%d rowpos=%d data=%d\n",
//          i.U, io.rowpos, io.iemdata)
      }
    }
  }.elsewhen(io.verifyIEM) {
    iemdataread := memrdata(io.iempos) // .asTypeOf(Vec(width, SInt(iembw.W))) // available in cycle later
//    if(debugprint) printf("verify: iempos=%d data=%d\n", io.iempos, iemdataread)
  }.otherwise {
    // compute mode
    when(io.indatavalid) {
      dataReceivedStageReg := true.B
      indataHoldReg := io.indata
    }.otherwise {
      dataReceivedStageReg := false.B
    }
  }

  val fromiem = Wire(Vec(nmaxpcs, Vec(width, SInt(iembw.W))))
  val multiplied = Reg(Vec(nmaxpcs, Vec(width, SInt((pxbw + iembw).W))))
  val partialcompressed = Reg(Vec(nmaxpcs, SInt(redbw.W)))
  // Accumulator needs redbw + log2Ceil(nrows) bits to accumulate across nrows without overflow
  val accbw = redbw + log2Ceil(nrows)
  val compressedAccReg = RegInit(VecInit(Seq.fill(nmaxpcs)(0.S(accbw.W))))
  val compressedReg = RegInit(0.U((nmaxpcs*redbw).W))

  for(pos <- 0 until nmaxpcs) {
    for (x <- 0 until width) {
      multiplied(pos)(x) := 0.S
    }
    partialcompressed(pos) := 0.S
  }

  for(pos <- 0 until nmaxpcs) {
    fromiem(pos) := memrdata(pos).asTypeOf(Vec(width, SInt(iembw.W)))
  }

  val multipliedStageReg = RegInit(false.B)
  val reducedStageReg    = RegInit(false.B)
  val enqOutQReg = RegInit(false.B)

  multipliedStageReg := dataReceivedStageReg
  reducedStageReg := multipliedStageReg

  // Delay rowid by 3 cycles to match when partialcompressed is used in reducedStageReg
  // Pipeline: dataReceivedStageReg -> multipliedStageReg -> reducedStageReg (2 cycles)
  // But partialcompressed is a Reg updated when multipliedStageReg is true, so it's available
  // when reducedStageReg is true. The 3-cycle delay accounts for the full pipeline latency.
  val rowidDelayed = ShiftRegister(io.rowid, 3)

  when(dataReceivedStageReg) {
    if(debugprint) { printf("%d stage: dataReceived\n",clk)  }
    for (pos <- 0 until nmaxpcs) {
      if(debugprint) { printf("  pos%d: ", pos.U) }
      for (x <- 0 until width) {
        multiplied(pos)(x) := fromiem(pos)(x) * indatavec(x)
        if(debugprint) {
          printf("%d*%d, ", fromiem(pos)(x), indatavec(x))
        }
      }
      if(debugprint) { printf("\n") }
    }
  }
  when(multipliedStageReg) {
    if(debugprint) { printf("%d stage: multiplied\n", clk)  }
    for (pos <- 0 until nmaxpcs) {
      partialcompressed(pos) := multiplied(pos).reduce(_ +& _)
      if (debugprint) {
        printf("  pos%d: ", pos.U)
        for (x <- 0 until width) {
          printf("%d ", multiplied(pos)(x))
        }
        printf("\n")
      }
    }
  }

  val outQ = Module(new Queue(chiselTypeOf(compressedReg),entries = 4))
  // lastCompressedAcc needs to be redbw bits (for final output), not redbw+1
  val lastCompressedAcc = Wire(Vec(nmaxpcs, SInt(redbw.W)))
  // Wire to hold the updated accumulator value (combinational)
  val updatedAcc = Wire(Vec(nmaxpcs, SInt(accbw.W)))
  for (pos <- 0 until nmaxpcs) { 
    lastCompressedAcc(pos) := 0.S
    updatedAcc(pos) := 0.S
  }

  when(reducedStageReg) {
    if(debugprint) { printf("%d stage: reduced: %d %d\n", clk, rowidDelayed, (nrows - 1).U)  }

    // Compute updated accumulator value (combinational) - same for both branches
    // This computes: accumulator + current_row_partialcompressed
    for (pos <- 0 until nmaxpcs) {
      updatedAcc(pos) := compressedAccReg(pos) + partialcompressed(pos)
    }

    // Check if this is the last row
    val isLastRow = rowidDelayed === (nrows - 1).U
    
    // Always update accumulator first (for non-last rows) or reset it (for last row)
    for (pos <- 0 until nmaxpcs) {
      when(isLastRow) {
        // On the last row: output the final accumulated value and reset accumulator
        lastCompressedAcc(pos) := updatedAcc(pos)(redbw-1, 0).asSInt
        compressedAccReg(pos) := 0.S(accbw.W)
      }.otherwise {
        // On non-last rows: accumulate the partialcompressed value
        compressedAccReg(pos) := updatedAcc(pos)
      }
    }
    
    // Output the result on the last row
    when(isLastRow) {
      if(debugprint) { printf("Compressed data accumulated\n")  }
      compressedReg := lastCompressedAcc.asTypeOf(UInt((nmaxpcs*redbw).W))
      enqOutQReg := true.B
    }
  }

  outQ.io.enq.bits := compressedReg
  outQ.io.enq.valid := false.B
  when(enqOutQReg) {

    when(outQ.io.enq.ready) {
      if(debugprint) {
        printf("%d enqOutQ: data=%x\n", clk, compressedReg)
      }
      outQ.io.enq.bits := compressedReg
      outQ.io.enq.valid := true.B
      enqOutQReg := false.B
    }
  }

  io.out <> outQ.io.deq
}

object PCACompBlock extends App {
  GenVerilog(new PCACompBlock())
}

object PCACompBlockSmall extends App {
  GenVerilog(new PCACompBlock(PCAConfigPresets.small))
}

object PCACompBlockMedium extends App {
  GenVerilog(new PCACompBlock(PCAConfigPresets.medium))
}

object PCACompBlockLarge extends App {
  GenVerilog(new PCACompBlock(PCAConfigPresets.large))
}

object PCACompBlockCfg1 extends App {
  GenVerilog(new PCACompBlock(PCAConfigPresets.cfg1))
}

import play.api.libs.json._
import scala.io.Source
import scala.util.Using

object PCACompBlockJson extends App {
  val cfgfn = if (args.length > 0) {
    args(0)
  } else {
    sys.env.getOrElse("PCAConfig", "default")
  }

  if(cfgfn == "default") {
    GenVerilog(new PCACompBlock(PCAConfigPresets.cfg1))
  } else {
    println(s"Loading config from: $cfgfn")
    
    try {
      val config = Using.resource(Source.fromFile(cfgfn)) { src =>
        val json = Json.parse(src.mkString)
        
        // Extract fields with defaults (matching PCAConfig defaults)
        val w = (json \ "w").asOpt[Int].getOrElse(12)
        val h = (json \ "h").asOpt[Int].getOrElse(3)
        val pxbw = (json \ "pxbw").asOpt[Int].getOrElse(9)
        val m = (json \ "m").asOpt[Int].getOrElse(7)
        val encbw = (json \ "encbw").asOpt[Int].getOrElse(8)
        val nblocks = (json \ "nblocks").asOpt[Int].getOrElse(3)
        val seed = (json \ "seed").asOpt[Int]
        val nonegative = (json \ "nonegative").asOpt[Boolean].getOrElse(false)
        
        // Validate that w is divisible by nblocks
        if (w % nblocks != 0) {
          throw new IllegalArgumentException(
            s"w ($w) must be divisible by nblocks ($nblocks)"
          )
        }
        
        // Create PCAConfig
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
      
      println(s"Config loaded: w=${config.w}, h=${config.h}, pxbw=${config.pxbw}, " +
              s"m=${config.m}, encbw=${config.encbw}, nblocks=${config.nblocks}, " +
              s"seed=${config.seed}, nonegative=${config.nonegative}")
      
      // Generate Verilog with the loaded config
      GenVerilog(new PCACompBlock(config))
      
    } catch {
      case e: java.io.FileNotFoundException =>
        println(s"Error: Config file not found: $cfgfn")
        sys.exit(1)
      case e: play.api.libs.json.JsResultException =>
        println(s"Error: Invalid JSON format in $cfgfn")
        println(e.getMessage)
        sys.exit(1)
      case e: IllegalArgumentException =>
        println(s"Error: ${e.getMessage}")
        sys.exit(1)
      case e: Exception =>
        println(s"Error loading config: ${e.getMessage}")
        e.printStackTrace()
        sys.exit(1)
    }
  }
}
