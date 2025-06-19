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
    val out = Output(Vec(nmaxpcs, SInt(redbw.W)))

    // initialize memory with the inverse encoding matrix content for this block
    val updateIEM = Input(Bool()) // load imedata into mem
    val verifyIEM = Input(Bool()) // read mem for verification
    val iempos = Input(UInt(log2Ceil(nmaxpcs).W))
    val iemdata = Input(UInt((width*iembw).W))
    val iemdataverify = Output(UInt((width*iembw).W))
  })

  val indatavec = io.indata.asTypeOf(Vec(width, UInt(pxbw.W)))

  io.iemdataverify := 0.U

  val mems = Seq.fill(nmaxpcs)(SyncReadMem(nrows, UInt(busbw.W)))

  val dataReceivedReg = RegInit(false.B)
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
      dataReceivedReg := true.B
    }.otherwise {
      dataReceivedReg := false.B
    }
  }

  val fromiem = Wire(Vec(nmaxpcs, Vec(width, SInt(iembw.W))))
  // val multiplied = Wire(Vec(nmaxpcs, Vec(width, SInt((pxbw+iembw).W))))
  // val partialcompressed = Wire(Vec(nmaxpcs,SInt(redbw.W)))
  val multiplied = Reg(Vec(nmaxpcs, Vec(width, SInt((pxbw + iembw).W))))
  val partialcompressed = Reg(Vec(nmaxpcs, SInt(redbw.W)))
  val compressedAccReg = RegInit(VecInit(Seq.fill(nmaxpcs)(0.S(redbw.W))))
  val compressedReg = RegInit(VecInit(Seq.fill(nmaxpcs)(0.S(redbw.W))))

  for(pos <- 0 until nmaxpcs) {
    for (x <- 0 until width) {
      multiplied(pos)(x) := 0.S
    }
    partialcompressed(pos) := 0.S
  }

  for(pos <- 0 until nmaxpcs) {
    fromiem(pos) := memrdata(pos).asTypeOf(Vec(width, SInt(iembw.W)))
  }

  val multipliedReg = RegInit(false.B)
  val reducedReg    = RegInit(false.B)

  multipliedReg := dataReceivedReg
  reducedReg := multipliedReg

  when(dataReceivedReg) {
    if(debugprint) { printf("stage: dataReceived\n")  }
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
  when(multipliedReg) {
    if(debugprint) { printf("stage: multiplied\n")  }

    for (pos <- 0 until nmaxpcs) {
      partialcompressed(pos) := multiplied(pos).reduce(_ +& _)
      if (debugprint) {
        printf("  pos%d: partial=%d %d\n", pos.U, partialcompressed(pos),multiplied(pos)(0) )
      }
    }
  }
  when(reducedReg) {
    if(debugprint) { printf("stage: reduced\n")  }

    for (pos <- 0 until nmaxpcs) {
      compressedAccReg(pos) := compressedAccReg(pos) + partialcompressed(pos)
      when(io.rowid === (nrows - 1).U) {
        compressedReg(pos) := compressedAccReg(pos)
      }
    }
  }
  io.out := compressedReg
}

object PCACompBlock extends App {
  GenVerilog(new PCACompBlock)
}
