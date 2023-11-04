package dedup
package bloomfilter

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import spinal.crypto.checksum._

import scala.collection.mutable.ArrayBuffer

case class BloomFilterLookupFSMInstr(bfConf: BloomFilterConfig) extends Bundle{
  val CRCHash = Vec(Bits(32 bits), bfConf.k)
  val opCode = DedupCoreOp()
}

object BloomFilterLookupResEnum extends SpinalEnum(binarySequential) {
  val IS_NEW, IS_EXIST = newElement()
}

case class BloomFilterLookupFSMRes() extends Bundle{
  val lookupRes = BloomFilterLookupResEnum()
  val opCode = DedupCoreOp()
}

case class BloomFilterLookupFSMIO(bfConf: BloomFilterConfig) extends Bundle {
  val initEn      = in Bool () // trigger zeroing SRAM content
  val initDone    = out Bool ()
  val instrStrmIn = slave Stream (BloomFilterLookupFSMInstr(bfConf))
  val res         = master Stream (BloomFilterLookupFSMRes())
}

class BloomFilterLookupFSM(bfConf : BloomFilterConfig = BloomFilterConfig(1 << log2Up(131072), 3)) extends Component {
  val io         = BloomFilterLookupFSMIO(bfConf)

  val mem        = Mem(Bits(bfConf.bramWidth bits), wordCount = bfConf.bramDepth)

  val instr      = Reg(BloomFilterLookupFSMInstr(bfConf))

  io.instrStrmIn.setBlocked()
  io.res.setIdle()

  val rInitDone = RegInit(False)
  io.initDone := rInitDone

  val fsm = new StateMachine {
    val START                            = new State with EntryPoint
    val INIT, IF, RUN_LOOKUP, RUN_RETURN = new State

    val lookUpEn, wrBackEn = False

    val cntLookup     = Counter(bfConf.k, isActive(RUN_LOOKUP) & lookUpEn)
    val cntWrBack     = Counter(bfConf.k, isActive(RUN_LOOKUP) & wrBackEn)
    val lookupIsExist = Reg(Bool())

    val memRdAddr = UInt(log2Up(bfConf.bramDepth) bits)
    val memWrAddr = RegNext(RegNext(memRdAddr)) // two cycle rd latency
    val rBitRdAddr = Reg(UInt(log2Up(bfConf.bramWidth) bits)).init(0)
    val memWrData = Bits(bfConf.bramWidth bits)

    val memWrInitEn = False
    val cntMemInit = Counter(log2Up(bfConf.bramDepth) bits, memWrInitEn)

    memRdAddr.clearAll()
    memWrData.clearAll()

    val cntLookupIsOverflowed = Reg(Bool()).init(False)
    
    START.whenIsActive {
      when(io.initEn)(goto(INIT))
    }

    INIT.whenIsActive {
      io.instrStrmIn.ready := False
      io.res.valid         := False
      rInitDone            := False
      memWrInitEn          := True
      when(cntMemInit.willOverflow) {
        rInitDone := True
        goto(IF)
      }
    }

    IF.whenIsActive {
      // instruction fetching
      io.instrStrmIn.ready := True
      io.res.valid         := False
      when(io.initEn){
        goto(INIT)
      } otherwise {
        when(io.instrStrmIn.fire){
          instr := io.instrStrmIn.payload
          goto(RUN_LOOKUP)
        }
      }
    }

    RUN_LOOKUP.onEntry {
      lookupIsExist := True
    }

    RUN_LOOKUP.whenIsActive {
      io.instrStrmIn.ready := False
      io.res.valid         := False
      cntLookupIsOverflowed.setWhen(cntLookup.willOverflow)
      lookUpEn := ~cntLookupIsOverflowed
      val lookUpVld = RegNext(RegNext(lookUpEn)) // two cycle rd latency
      wrBackEn := lookUpVld

      val crcResVec = instr.CRCHash

      memRdAddr  := crcResVec(cntLookup)(bfConf.mWidth - 1 downto log2Up(bfConf.bramWidth)).asUInt
      rBitRdAddr := crcResVec(RegNext(RegNext(cntLookup.value)))(log2Up(bfConf.bramWidth) - 1 downto 0).asUInt

      val memRdData = RegNext(mem.readSync(memRdAddr, lookUpEn)) // one reg pipeline on rd port to meet ultra RAM requirement

      when(lookUpVld) {
        lookupIsExist := lookupIsExist & memRdData(rBitRdAddr)
      }
      
      // TODO: change to Counting Bloom Filter
      when(instr.opCode === DedupCoreOp.WRITE2FREE) {
        memWrData := memRdData | (1 << rBitRdAddr) // bitwise
      }

      when(io.initEn)(goto(INIT)) otherwise {
        when(cntWrBack.willOverflow) (goto(RUN_RETURN))
      }

    }

    RUN_LOOKUP.onExit {
      cntLookupIsOverflowed.clear()
    }

    RUN_RETURN.whenIsActive {
      io.instrStrmIn.ready     := False
      io.res.payload.opCode    := instr.opCode
      io.res.payload.lookupRes assignFromBits (lookupIsExist.asBits)
      io.res.valid             := True
      when(io.initEn)(goto(INIT)) otherwise {
        when(io.res.fire) (goto(IF))
      }
    }

  }

  /** mux write cmd here to avoid multi-port memory for WR */
  val memWrEn = fsm.memWrInitEn | (fsm.isActive(fsm.RUN_LOOKUP) & fsm.wrBackEn)
  val memWrAddress = fsm.isActive(fsm.INIT) ? fsm.cntMemInit.value | fsm.memWrAddr
  val memWrD = fsm.isActive(fsm.INIT) ? B(0, bfConf.bramWidth bits) | fsm.memWrData
  mem.write(memWrAddress, memWrD, memWrEn)

}
