package dedup
package pagewriter

import spinal.core._
import spinal.lib._

case class DecodedReadyInstr(conf: DedupConfig) extends Bundle{
  // read instr -> directly send to SSD
  val SSDLBALen   = UInt(conf.lbaWidth bits)
  val SSDLBAStart = UInt(conf.lbaWidth bits)
  val SSDNodeIdx  = UInt(conf.dataNodeIdxWidth bits)
  val opCode      = DedupCoreOp()
  val tag         = UInt(conf.pwConf.instrTagWidth bits)
}

case class DecodedWaitingInstr(conf: DedupConfig) extends Bundle{
  val hostLBALen      = UInt(conf.lbaWidth bits)
  val hostLBAStart    = UInt(conf.lbaWidth bits)
  val hostDataNodeIdx = UInt(conf.dataNodeIdxWidth bits)
  val opCode          = DedupCoreOp()
  val tag             = UInt(conf.pwConf.instrTagWidth bits)
}

case class PageWriterInstrDecoder(conf: DedupConfig) extends Component{

  val instrBitWidth = DedupCoreOp().getBitsWidth
  val pwConf = conf.pwConf

  val io = new Bundle {
    /* input raw Instr Stream: 512bits*/
    val rawInstrStream = slave Stream (Bits(conf.instrTotalWidth bits))

    /* output instr stream */
    val readyInstrStream = master Stream (DecodedReadyInstr(conf)) 
    val waitingInstrStream = master Stream (DecodedWaitingInstr(conf))
  }

  val isNeededInstr = Bool() default(False)

  val tagGenerator = Counter(pwConf.instrTagWidth bits, inc = isNeededInstr & io.rawInstrStream.fire)
  
  val instrDispatcher = new Area {
    when(io.rawInstrStream.valid){
      switch(io.rawInstrStream.payload((conf.instrTotalWidth - 1) downto (conf.instrTotalWidth - instrBitWidth))){
        is(DedupCoreOp.WRITE2FREE.asBits){
          // go to waitInstrStream
          isNeededInstr := True
          io.waitingInstrStream.translateFrom(io.rawInstrStream){ (decodedInstr, rawBits) =>
            val decodedFullInstr = WRITE2FREEInstr(conf)
            WRITE2FREEInstr(conf).decodeFromRawBits()(decodedFullInstr, rawBits)
            decodedInstr.hostLBALen      := decodedFullInstr.hostLBALen
            decodedInstr.hostLBAStart    := decodedFullInstr.hostLBAStart
            decodedInstr.hostDataNodeIdx := decodedFullInstr.hostDataNodeIdx
            decodedInstr.opCode          := decodedFullInstr.opCode
            decodedInstr.tag             := tagGenerator.value
          }
          io.readyInstrStream.setIdle()
        }
        is(DedupCoreOp.ERASEREF.asBits){
          // go to waitInstrStream
          isNeededInstr := True
          io.waitingInstrStream.translateFrom(io.rawInstrStream){ (decodedInstr, rawBits) =>
            val decodedFullInstr = ERASEREFInstr(conf)
            ERASEREFInstr(conf).decodeFromRawBits()(decodedFullInstr, rawBits)
            decodedInstr.hostLBALen      := 1
            decodedInstr.hostLBAStart    := 0
            decodedInstr.hostDataNodeIdx := 0
            decodedInstr.opCode          := decodedFullInstr.opCode
            decodedInstr.tag             := tagGenerator.value
          }
          io.readyInstrStream.setIdle()
        }
        is(DedupCoreOp.READSSD.asBits){
          // // go to readyInstrStream
          // isNeededInstr := True
          // io.readyInstrStream.translateFrom(io.rawInstrStream){ (decodedInstr, rawBits) =>
          //   val decodedFullInstr = READSSDInstr(conf)
          //   READSSDInstr(conf).decodeFromRawBits()(decodedFullInstr, rawBits)
          //   decodedInstr.assignSomeByName(decodedFullInstr)
          //   decodedInstr.tag          := tagGenerator.value
          // }
          // io.waitingInstrStream.setIdle()
          // go to waitInstrStream
          isNeededInstr := True
          io.waitingInstrStream.translateFrom(io.rawInstrStream){ (decodedInstr, rawBits) =>
            val decodedFullInstr = READSSDInstr(conf)
            READSSDInstr(conf).decodeFromRawBits()(decodedFullInstr, rawBits)
            decodedInstr.hostLBALen      := 1
            decodedInstr.hostLBAStart    := 0
            decodedInstr.hostDataNodeIdx := 0 
            decodedInstr.opCode          := decodedFullInstr.opCode
            decodedInstr.tag             := tagGenerator.value
          }
          io.readyInstrStream.setIdle()
        }
        default{
          // Throw
          isNeededInstr := False
          io.rawInstrStream.ready := True
          io.readyInstrStream.setIdle()
          io.waitingInstrStream.setIdle()
        }
      }
    }.otherwise{
      // Throw
      isNeededInstr := False
      io.rawInstrStream.ready := True
      io.readyInstrStream.setIdle()
      io.waitingInstrStream.setIdle()
    }
  }
}