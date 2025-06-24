package pca

import chisel3._
import chisel3.util._
import common.GenVerilog

class PCACompBlock(cfg: PCAConfig = PCAConfigPresets.default,
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

  override def desiredName = s"${this.getClass.getSimpleName}__w${width}_pxbw${pxbw}_iembw${iembw}_npcs${nmaxpcs}"

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

  val mems = Seq.fill(nmaxpcs)(SyncReadMem(nrows, UInt(busbw.W)))

  val memaddr  = Wire(UInt(log2Ceil(nrows).W))
  val memrdata = Wire(Vec(nmaxpcs, UInt(busbw.W)))

  val iemdataread = Wire(UInt((width*iembw).W))
  iemdataread := 0.U
  io.iemdataverify := iemdataread

  memaddr := io.rowid
  for(i <- 0 until nmaxpcs) {
    memrdata(i) := mems(i).read(memaddr, true.B)
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
        mems(i).write(io.rowid, io.iemdata /*.asTypeOf(UInt(busbw.W)) */ )
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
  val compressedAccReg = RegInit(VecInit(Seq.fill(nmaxpcs)(0.S(redbw.W))))
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
  val lastCompressedAcc = Wire(chiselTypeOf(compressedAccReg))
  for (pos <- 0 until nmaxpcs) { lastCompressedAcc(pos) := 0.S }

  when(reducedStageReg) {
    if(debugprint) { printf("%d stage: reduced: %d %d\n", clk, rowidDelayed, (nrows - 1).U)  }

    when(rowidDelayed === (nrows - 1).U) {
      if(debugprint) { printf("Compressed data accumulated\n")  }
      for (pos <- 0 until nmaxpcs) {
        lastCompressedAcc(pos) := (compressedAccReg(pos) + partialcompressed(pos))
        compressedAccReg(pos) := 0.S
      }
      compressedReg := lastCompressedAcc.asTypeOf(UInt((nmaxpcs*redbw).W))

      enqOutQReg := true.B
    }.otherwise {
      for (pos <- 0 until nmaxpcs) {
        compressedAccReg(pos) := compressedAccReg(pos) + partialcompressed(pos)
      }
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
