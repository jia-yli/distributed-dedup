package dedup
package registerfile

import spinal.core._
import spinal.lib._

// instruction for local/remote hash table execution
case class RegisteredLookupInstr(conf: DedupConfig) extends Bundle{
  val SHA3Hash = Bits(conf.htConf.hashValWidth bits)
  // val routeInfo = UInt(conf.rfConf.nodeIdxWidth bits)
  val tag      = UInt(conf.rfConf.tagWidth bits)
  val opCode   = DedupCoreOp()
}

case class UnregisteredLookupInstr(conf: DedupConfig) extends Bundle{
  val SHA3Hash = Bits(conf.htConf.hashValWidth bits)
  val opCode   = DedupCoreOp()
}

case class rfInstrEntry(conf: DedupConfig) extends Bundle{
  val SHA3Hash = Bits(conf.htConf.hashValWidth bits)
  // val routeInfo = UInt(conf.rfConf.nodeIdxWidth bits)
  val opCode   = DedupCoreOp()
}

// write back lookup result
case class WriteBackLookupRes (conf: DedupConfig) extends Bundle {
  val RefCount = UInt(conf.htConf.ptrWidth bits)
  val SSDLBA   = UInt(conf.htConf.ptrWidth bits)
  val nodeIdx  = UInt(conf.rfConf.nodeIdxWidth bits)
  val tag      = UInt(conf.rfConf.tagWidth bits)
}

case class HashTableLookupRes (conf: DedupConfig) extends Bundle {
  val SHA3Hash = Bits(conf.htConf.hashValWidth bits)
  val RefCount = UInt(conf.htConf.ptrWidth bits)
  val SSDLBA   = UInt(conf.htConf.ptrWidth bits)
  val nodeIdx  = UInt(conf.rfConf.nodeIdxWidth bits)
  val opCode   = DedupCoreOp()
}

case class rfResEntry (conf: DedupConfig) extends Bundle {
  val RefCount = UInt(conf.htConf.ptrWidth bits)
  val SSDLBA   = UInt(conf.htConf.ptrWidth bits)
  val nodeIdx  = UInt(conf.rfConf.nodeIdxWidth bits)
}

// OoO support for RDMA
case class RegisterFileConfig(
  tagWidth: Int = 8,      // 256 regs in register file
  nodeIdxWidth: Int = 4   // up to 16 nodes
)

case class RegisterFile(conf: DedupConfig) extends Component {
  val io = new Bundle {
    // local instr input
    val unregisteredInstrIn = slave Stream(UnregisteredLookupInstr(conf))
    // output registered instr, go for routing
    val registeredInstrOut = master Stream(RegisteredLookupInstr(conf))
    val lookupResWriteBackIn = slave Stream(WriteBackLookupRes(conf))
    val lookupResOut = master Stream(HashTableLookupRes(conf))
  }

  // Register array
  val regNum = 1 << conf.rfConf.tagWidth
  val instrArray = Vec(Reg(rfInstrEntry(conf)), regNum)
  val resArray = Vec(Reg(rfResEntry(conf)), regNum)
  val execStatus = Vec(Reg(Bool()) init False, regNum) // true if exec done

  // instr stream in, register entry in register file
  val youngestPtr = Counter(conf.rfConf.tagWidth bits) // push pointer
  val pushing = io.unregisteredInstrIn.fire
  when(pushing) {
    instrArray(youngestPtr) assignAllByName io.unregisteredInstrIn.payload
    execStatus(youngestPtr) := False
    youngestPtr.increment()
  }

  // retire logic
  val instrRetireLogic = new Area {
    val retirePtr = Counter(conf.rfConf.tagWidth bits)   // pop pointer
    val ptrMatchRetire = youngestPtr === retirePtr
    val risingOccupancyRetire = RegInit(False)
    val retiring = io.lookupResOut.fire
    val emptyRetire = ptrMatchRetire & !risingOccupancyRetire
    val fullRetire = ptrMatchRetire & risingOccupancyRetire

    io.unregisteredInstrIn.ready := !fullRetire
    io.lookupResOut.valid := !emptyRetire & execStatus(retirePtr)

    val lookupRes = HashTableLookupRes(conf)
    lookupRes assignSomeByName instrArray(retirePtr)
    lookupRes assignSomeByName resArray(retirePtr)
    io.lookupResOut.payload := lookupRes

    when(pushing =/= retiring) {
      risingOccupancyRetire := pushing
    }
    when(retiring) {
      retirePtr.increment()
    }

    val ptrDif = youngestPtr - retirePtr
    val rfOccupancyRetire    = out UInt (log2Up(regNum + 1) bits)
    val rfAvailabilityRetire = out UInt (log2Up(regNum + 1) bits)
    if (isPow2(regNum)) {
      rfOccupancyRetire    := ((risingOccupancyRetire && ptrMatchRetire) ## ptrDif).asUInt
      rfAvailabilityRetire := ((!risingOccupancyRetire && ptrMatchRetire) ## (retirePtr - youngestPtr)).asUInt
    } else {
      when(ptrMatchRetire) {
        rfOccupancyRetire    := Mux(risingOccupancyRetire, U(regNum), U(0))
        rfAvailabilityRetire := Mux(risingOccupancyRetire, U(0), U(regNum))
      } otherwise {
        rfOccupancyRetire    := Mux(youngestPtr > retirePtr, ptrDif, U(regNum) + ptrDif)
        rfAvailabilityRetire := Mux(youngestPtr > retirePtr, U(regNum) + (retirePtr - youngestPtr), (retirePtr - youngestPtr))
      }
    }
  }

  // issue logic
  val instrIssueLogic = new Area {
    val issuePtr = Counter(conf.rfConf.tagWidth bits)   // pop pointer
    val ptrMatchIssue = youngestPtr === issuePtr
    val risingOccupancyIssue = RegInit(False)
    val issuing = io.registeredInstrOut.fire
    val emptyIssue = ptrMatchIssue & !risingOccupancyIssue
    val fullIssue = ptrMatchIssue & risingOccupancyIssue

    io.registeredInstrOut.valid := !emptyIssue

    val issueInstr = RegisteredLookupInstr(conf)
    issueInstr assignSomeByName instrArray(issuePtr)
    issueInstr.tag := issuePtr
    io.registeredInstrOut.payload := issueInstr

    when(pushing =/= issuing) {
      risingOccupancyIssue := pushing
    }
    when(issuing) {
      issuePtr.increment()
    }

    val ptrDif = youngestPtr - issuePtr
    val rfOccupancyIssue    = out UInt (log2Up(regNum + 1) bits)
    val rfAvailabilityIssue = out UInt (log2Up(regNum + 1) bits)
    if (isPow2(regNum)) {
      rfOccupancyIssue    := ((risingOccupancyIssue && ptrMatchIssue) ## ptrDif).asUInt
      rfAvailabilityIssue := ((!risingOccupancyIssue && ptrMatchIssue) ## (issuePtr - youngestPtr)).asUInt
    } else {
      when(ptrMatchIssue) {
        rfOccupancyIssue    := Mux(risingOccupancyIssue, U(regNum), U(0))
        rfAvailabilityIssue := Mux(risingOccupancyIssue, U(0), U(regNum))
      } otherwise {
        rfOccupancyIssue    := Mux(youngestPtr > issuePtr, ptrDif, U(regNum) + ptrDif)
        rfAvailabilityIssue := Mux(youngestPtr > issuePtr, U(regNum) + (issuePtr - youngestPtr), (issuePtr - youngestPtr))
      }
    }
  }

  // instr write back logic
  val instrWriteBackLogic = new Area{
    io.lookupResWriteBackIn.ready := True
    val writeBackLookupRes = io.lookupResWriteBackIn.payload
    when(io.lookupResWriteBackIn.fire){
      resArray(writeBackLookupRes.tag) assignSomeByName writeBackLookupRes
      execStatus(writeBackLookupRes.tag) := True
    }
  }
}