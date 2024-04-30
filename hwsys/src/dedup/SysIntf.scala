package dedup

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import java.io.DataInputStream

/* 512b wide interface, split instr and data
  inputformat: instr0, data0, instr1,instr2,data2,...
  only write need data
*/
class SysIntfIO() extends Bundle {
  // ctrl
  val initEn = in Bool()

  // host IO
  val mergedStrm = slave Stream(Bits(512 bits))

  // page stream
  val pageStrm = master Stream(Bits(512 bits))

  // instr stream
  val instrStrm = master Stream(Bits(512 bits))
}

class SysIntf() extends Component {

  val conf = DedupConfig()

  val io = new SysIntfIO()

  val isInstr = Reg(Bool()) init True
  val outputSelect = isInstr.asUInt

  val frgmCounter = Counter(conf.pgWord)
  val instrPageCount = Reg(UInt(conf.lbaWidth bits))
  val pageCounter = Counter(conf.lbaWidth bits, frgmCounter.willOverflow)

  /** read path */
  val fsm = new StateMachine {
    val FORWARD_INSTR = new State with EntryPoint
    val FORWARD_PAGE  = new State
    
    always{
      when(io.initEn){
        isInstr := True
        goto(FORWARD_INSTR)
      }
    }

    FORWARD_INSTR.whenIsActive {
      isInstr := True
      when(io.mergedStrm.valid){
        val opCode = DedupCoreOp()
        opCode assignFromBits io.mergedStrm.payload((conf.instrTotalWidth - 1) downto (conf.instrTotalWidth - DedupCoreOp().getBitsWidth))
        when(opCode === DedupCoreOp.WRITE2FREE){
          // is write instr, means the next input will be page data
          val decodedInstr = WRITE2FREEInstr(conf)
          WRITE2FREEInstr(conf).decodeFromRawBits()(decodedInstr, io.mergedStrm.payload)
          when(io.mergedStrm.fire && (decodedInstr.hostLBALen > 0)){
            isInstr        := False
            frgmCounter.clear()
            instrPageCount := decodedInstr.hostLBALen
            pageCounter.clear()
            goto(FORWARD_PAGE)
          }
        }
      }
    }

    FORWARD_PAGE.whenIsActive {
      isInstr := False
      when(io.mergedStrm.fire){
        frgmCounter.increment()
      }

      val receivingLastPage = (pageCounter === (instrPageCount - 1))
      val receivingLastWord = frgmCounter.willOverflow
      when(receivingLastPage & receivingLastWord){
        // page done, go back for instr
        isInstr := True
        goto(FORWARD_INSTR)
      }
    }
  }

  val dispatchedInstrStream = StreamDemux(io.mergedStrm, outputSelect, 2)
  dispatchedInstrStream(0) >> io.pageStrm
  dispatchedInstrStream(1) >> io.instrStrm
}