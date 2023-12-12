package dedup
package registerfile

import spinal.core._
import spinal.lib._

case class RegisterFileBram(conf: DedupConfig) extends Component with RegisterFileImpl {
  // Register array
  require(conf.rfConf.tagWidth > 0)
  val wordCount = 1 << conf.rfConf.tagWidth
  val instrRam = Mem(RfInstrEntry(conf), wordCount)
  val resRam = Mem(RfResEntry(conf), wordCount)
  val execStatus = Vec(Reg(Bool()) init False, wordCount) // true if exec done
  val rExecStatus = Vec(Reg(Bool()) init False, wordCount) // 1 cycle latency
  (rExecStatus, execStatus).zipped.map{_ := _}

  // instr stream in, register entry in register file
  val youngestPtr = Counter(conf.rfConf.tagWidth bits) // push pointer
  val pushing = io.unregisteredInstrIn.fire
  when(pushing) {
    val pushingVal = RfInstrEntry(conf)
    pushingVal assignAllByName io.unregisteredInstrIn.payload
    instrRam(youngestPtr) := pushingVal
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
    io.lookupResOut.valid := !emptyRetire & rExecStatus(retirePtr) & !(RegNext(retirePtr.valueNext === youngestPtr, False) & !fullRetire)

    val lookupResPart1 = RfInstrEntry(conf)
    lookupResPart1 := instrRam.readSync(retirePtr.valueNext)
    val lookupResPart2 = RfResEntry(conf)
    lookupResPart2 := resRam.readSync(retirePtr.valueNext)

    io.lookupResOut.payload assignSomeByName lookupResPart1
    io.lookupResOut.payload assignSomeByName lookupResPart2

    when(pushing =/= retiring) {
      risingOccupancyRetire := pushing
    }
    when(retiring) {
      retirePtr.increment()
    }

    val ptrDif = youngestPtr - retirePtr
    val rfOccupancyRetire    = out UInt (log2Up(wordCount + 1) bits)
    val rfAvailabilityRetire = out UInt (log2Up(wordCount + 1) bits)
    if (isPow2(wordCount)) {
      rfOccupancyRetire    := ((risingOccupancyRetire && ptrMatchRetire) ## ptrDif).asUInt
      rfAvailabilityRetire := ((!risingOccupancyRetire && ptrMatchRetire) ## (retirePtr - youngestPtr)).asUInt
    } else {
      when(ptrMatchRetire) {
        rfOccupancyRetire    := Mux(risingOccupancyRetire, U(wordCount), U(0))
        rfAvailabilityRetire := Mux(risingOccupancyRetire, U(0), U(wordCount))
      } otherwise {
        rfOccupancyRetire    := Mux(youngestPtr > retirePtr, ptrDif, U(wordCount) + ptrDif)
        rfAvailabilityRetire := Mux(youngestPtr > retirePtr, U(wordCount) + (retirePtr - youngestPtr), (retirePtr - youngestPtr))
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

    io.registeredInstrOut.valid := !emptyIssue & !(RegNext(issuePtr.valueNext === youngestPtr, False) & !fullIssue)

    val issueInstrPart = RfInstrEntry(conf)
    issueInstrPart := instrRam.readSync(issuePtr.valueNext)
    io.registeredInstrOut.payload assignSomeByName issueInstrPart
    io.registeredInstrOut.payload.tag := issuePtr

    when(pushing =/= issuing) {
      risingOccupancyIssue := pushing
    }
    when(issuing) {
      issuePtr.increment()
    }

    val ptrDif = youngestPtr - issuePtr
    val rfOccupancyIssue    = out UInt (log2Up(wordCount + 1) bits)
    val rfAvailabilityIssue = out UInt (log2Up(wordCount + 1) bits)
    if (isPow2(wordCount)) {
      rfOccupancyIssue    := ((risingOccupancyIssue && ptrMatchIssue) ## ptrDif).asUInt
      rfAvailabilityIssue := ((!risingOccupancyIssue && ptrMatchIssue) ## (issuePtr - youngestPtr)).asUInt
    } else {
      when(ptrMatchIssue) {
        rfOccupancyIssue    := Mux(risingOccupancyIssue, U(wordCount), U(0))
        rfAvailabilityIssue := Mux(risingOccupancyIssue, U(0), U(wordCount))
      } otherwise {
        rfOccupancyIssue    := Mux(youngestPtr > issuePtr, ptrDif, U(wordCount) + ptrDif)
        rfAvailabilityIssue := Mux(youngestPtr > issuePtr, U(wordCount) + (issuePtr - youngestPtr), (issuePtr - youngestPtr))
      }
    }
  }

  // instr write back logic
  val instrWriteBackLogic = new Area{
    io.lookupResWriteBackIn.ready := True
    val writeBackLookupRes = io.lookupResWriteBackIn.payload
    when(io.lookupResWriteBackIn.fire){
      val writeBackVal = RfResEntry(conf)
      writeBackVal assignSomeByName writeBackLookupRes
      resRam(writeBackLookupRes.tag) := writeBackVal
      execStatus(writeBackLookupRes.tag) := True
    }
  }
}