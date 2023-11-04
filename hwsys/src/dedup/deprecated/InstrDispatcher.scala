package dedup.deprecated

import spinal.core._
import spinal.lib._

import dedup.DedupConfig

object DedupCoreOp extends SpinalEnum(binarySequential) {
  val WRITE2FREE, ERASEREF, READSSD = newElement()
}

// trait DedupCoreInstr{
//   def decodeFromRawBits() : (this.type, Bits) => Unit 
// }
/* instr format: total 512 bit wide
  WRITE2FREE: opcode, host LBA start, host LBA len
  ERASEREF:   opcode, SSD LBA, CRC1, CRC2, CRC3, SHA3
  READSSD:    opcode, SSD LBA start, SSD LBA len */

case class WRITE2FREEInstr (conf: DedupConfig) extends Bundle{ // with DedupCoreInstr
  val hostLBALen = UInt(conf.LBAWidth bits)
  val hostLBAStart = UInt(conf.LBAWidth bits)
  val opCode = DedupCoreOp()

  def decodeFromRawBits() : (WRITE2FREEInstr, Bits) => Unit = {
    (decoded, raw) => {
      decoded.hostLBALen   assignFromBits raw(0, conf.LBAWidth bits)
      decoded.hostLBAStart assignFromBits raw(conf.LBAWidth, conf.LBAWidth bits)
      decoded.opCode       assignFromBits raw((conf.instrTotalWidth - 1) downto (conf.instrTotalWidth - DedupCoreOp().getBitsWidth))
    }
  }
}

case class ERASEREFInstr (conf: DedupConfig) extends Bundle{ // with DedupCoreInstr
  val SHA3Hash = Bits(256 bits)
  val CRCHash = Vec(Bits(32 bits), 3)
  val opCode = DedupCoreOp()

  def decodeFromRawBits() : (ERASEREFInstr, Bits) => Unit = {
    (decoded, raw) => {
      decoded.SHA3Hash     :=             raw(0, 256 bits)
      decoded.CRCHash      assignFromBits raw(256, 96 bits)
      decoded.opCode       assignFromBits raw((conf.instrTotalWidth - 1) downto (conf.instrTotalWidth - DedupCoreOp().getBitsWidth))
    }
  }
}

case class READSSDInstr (conf: DedupConfig) extends Bundle{ // with DedupCoreInstr
  val SSDLBALen = UInt(conf.LBAWidth bits)
  val SSDLBAStart = UInt(conf.LBAWidth bits)
  val opCode = DedupCoreOp()

  def decodeFromRawBits() : (READSSDInstr, Bits) => Unit = {
    (decoded, raw) => {
      decoded.SSDLBALen    assignFromBits raw(0, conf.LBAWidth bits)
      decoded.SSDLBAStart  assignFromBits raw(conf.LBAWidth, conf.LBAWidth bits)
      decoded.opCode       assignFromBits raw((conf.instrTotalWidth - 1) downto (conf.instrTotalWidth - DedupCoreOp().getBitsWidth))
    }
  }
}

case class InstrDispatcher(conf: DedupConfig) extends Component{

  val instrQueueNum = conf.instrQueueLogDepth.length
  assert(instrQueueNum == 3)
  val instrBitWidth = DedupCoreOp().getBitsWidth

  val io = new Bundle {
    /* input raw Instr Stream: 512bits*/
    val rawInstrStream = slave Stream (Bits(conf.instrTotalWidth bits))

    /* output instr stream */
    // val instrIssueStream: Vec[Stream[Bundle with DedupCoreInstr]] = out Vec(Stream (WRITE2FREEInstr(conf)), Stream (ERASEREFInstr(conf)), Stream (READSSDInstr(conf)))
    val writeInstrStream = master Stream (WRITE2FREEInstr(conf))
    val eraseInstrStream = master Stream (ERASEREFInstr(conf))
    val readInstrStream  = master Stream (READSSDInstr(conf))
    
    /* flush */
    val flush = in Bool() default(False)

    /* instr queue states */
    val occupancy    = out Vec(UInt(conf.instrQueueLogDepth(0) + 1 bits), UInt(conf.instrQueueLogDepth(1) + 1 bits), UInt(conf.instrQueueLogDepth(2) + 1 bits))
    val availability = out Vec(UInt(conf.instrQueueLogDepth(0) + 1 bits), UInt(conf.instrQueueLogDepth(1) + 1 bits), UInt(conf.instrQueueLogDepth(2) + 1 bits))
  }

  val instrDispatcher = new Area {
    val instrQueueSelect = UInt(log2Up(instrQueueNum) bits)

    when(io.rawInstrStream.valid){

      switch(io.rawInstrStream.payload((conf.instrTotalWidth - 1) downto (conf.instrTotalWidth - instrBitWidth))){
        is(DedupCoreOp.WRITE2FREE.asBits){
          instrQueueSelect := 0
        }
        is(DedupCoreOp.ERASEREF.asBits){
          instrQueueSelect := 1
        }
        is(DedupCoreOp.READSSD.asBits){
          instrQueueSelect := 2
        }
        default{
          instrQueueSelect := 0
        }
      }

    }.otherwise{
      instrQueueSelect := 0
    }
  }

  val dispatchedInstrStream = StreamDemux(io.rawInstrStream, instrDispatcher.instrQueueSelect, instrQueueNum)

  // val FIFOConfigList: List[Bundle with DedupCoreInstr] = List(WRITE2FREEInstr(conf), ERASEREFInstr(conf), READSSDInstr(conf))

  // var instrFIFOArray:Array[StreamFifo[Bundle with DedupCoreInstr]] = Array()

  // FIFOConfigList.zipWithIndex.foreach{case (instrType, instrIdx) => 
  //   // create fifo with instr type and config
  //   val instrFIFO = StreamFifo(instrType, 1 << conf.instrQueueLogDepth(instrIdx))
    
  //   // Add to instr FIFO array
  //   instrFIFOArray = instrFIFOArray :+ instrFIFO
    
  //   // use decodeFromRawBits to decode instruction
  //   /* problem: 
  //     instrFIFO has type StreamFifo[Bundle with DedupCoreInstr]
  //     decodeFromRawBits returns function of (this.type, spinal.core.Bits) => Unit, 'this' depends on the instruction queue, could be write, read, erase
  //     what I wanted to realize is to translate raw bits to different instructions:
  //     StreamFifo(WRITE2FREEInstr(conf), 1 << conf.instrQueueLogDepth(0)).io.push.translateFrom(dispatchedInstrStream(0)){WRITE2FREEInstr(conf).decodeFromRawBits()}
  //   */
  //   instrFIFO.io.push.translateFrom(dispatchedInstrStream(instrIdx)){
  //     instrType.decodeFromRawBits()
  //   }

  //   // connect other wires
  //   instrFIFO.io.flush := io.flush
  //   instrFIFO.io.pop >> io.instrIssueStream(instrIdx)
  //   instrFIFO.io.availability >> io.availability(instrIdx)
  //   instrFIFO.io.occupancy >> io.occupancy(instrIdx)
    
  // }

  val writeInstrFIFO = StreamFifo(WRITE2FREEInstr(conf), 1 << conf.instrQueueLogDepth(0))
  writeInstrFIFO.io.push.translateFrom(dispatchedInstrStream(0)){WRITE2FREEInstr(conf).decodeFromRawBits()}
  writeInstrFIFO.io.pop >> io.writeInstrStream
  writeInstrFIFO.io.flush := io.flush
  io.availability(0) := writeInstrFIFO.io.availability
  io.occupancy(0) := writeInstrFIFO.io.occupancy

  val eraseInstrFIFO = StreamFifo(ERASEREFInstr(conf), 1 << conf.instrQueueLogDepth(1))
  eraseInstrFIFO.io.push.translateFrom(dispatchedInstrStream(1)){ERASEREFInstr(conf).decodeFromRawBits()}
  eraseInstrFIFO.io.pop >> io.eraseInstrStream
  eraseInstrFIFO.io.flush := io.flush
  io.availability(1) := eraseInstrFIFO.io.availability
  io.occupancy(1) := eraseInstrFIFO.io.occupancy

  val readInstrFIFO = StreamFifo(READSSDInstr(conf), 1 << conf.instrQueueLogDepth(2))
  readInstrFIFO.io.push.translateFrom(dispatchedInstrStream(2)){READSSDInstr(conf).decodeFromRawBits()}
  readInstrFIFO.io.pop >> io.readInstrStream
  readInstrFIFO.io.flush := io.flush
  io.availability(2) := readInstrFIFO.io.availability
  io.occupancy(2) := readInstrFIFO.io.occupancy
  
}