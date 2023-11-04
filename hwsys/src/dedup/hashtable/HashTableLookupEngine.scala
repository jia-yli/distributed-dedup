package dedup
package hashtable

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import util.ReverseStreamArbiterFactory

/* Lookup Engine = Arbitration logic + FSM array + lock table 
  make sure seq instr in, seq res out in same order*/
case class HashTableLookupEngineIO(htConf: HashTableConfig) extends Bundle {
  val initEn      = in Bool()
  val clearInitStatus = in Bool()
  val initDone    = out Bool()
  // execution results
  val instrStrmIn = slave Stream(HashTableLookupFSMInstr(htConf))
  val res         = master Stream(HashTableLookupFSMRes(htConf))
  // To Allocator
  val mallocIdx   = slave Stream(UInt(htConf.ptrWidth bits))
  val freeIdx     = master Stream(UInt(htConf.ptrWidth bits))
  /** DRAM interface */
  val axiConf     = Axi4ConfigAlveo.u55cHBM
  val axiMem      = Vec(master(Axi4(axiConf)), htConf.sizeFSMArray)
}

case class HashTableLookupEngine(htConf: HashTableConfig) extends Component {

  val io = HashTableLookupEngineIO(htConf)

  val memInitializer = HashTableMemInitializer(htConf)

  val dispatchedInstrStream = StreamDispatcherSequential(io.instrStrmIn, htConf.sizeFSMArray)

  val fsmInstrBufferArray = Array.fill(htConf.sizeFSMArray)(new StreamFifo(HashTableLookupFSMInstr(htConf), 8))

  val fsmResBufferArray = Array.fill(htConf.sizeFSMArray)(new StreamFifo(HashTableLookupFSMRes(htConf), 8))

  val lockManager = HashTableLookupLockManager(htConf)

  val fsmArray = Array.tabulate(htConf.sizeFSMArray){idx => 
    val fsmInstance = HashTableLookupFSM(htConf,idx)
    // connect instr dispatcher to fsm
    dispatchedInstrStream(idx) >> fsmInstrBufferArray(idx).io.push
    fsmInstrBufferArray(idx).io.pop >> fsmInstance.io.instrStrmIn
    
    fsmInstrBufferArray(idx).io.flush := io.initEn
    fsmInstance.io.initEn             := io.initEn
    fsmResBufferArray(idx).io.flush   := io.initEn
    // connect fsm results to output
    fsmInstance.io.res >> fsmResBufferArray(idx).io.push
    fsmInstance.io.lockReq.pipelined(StreamPipe.FULL) >> lockManager.io.fsmArrayLockReq(idx)
    fsmInstance.io.axiMem.pipelined(StreamPipe.FULL,StreamPipe.FULL,StreamPipe.FULL,StreamPipe.FULL,StreamPipe.FULL) >> lockManager.io.fsmArrayDRAMReq(idx)
    fsmInstance
  }

  io.res << StreamArbiterFactory.sequentialOrder.transactionLock.on(Array.tabulate(htConf.sizeFSMArray)(idx => fsmResBufferArray(idx).io.pop)).pipelined(StreamPipe.FULL)

  io.freeIdx << StreamArbiterFactory.roundRobin.transactionLock.on(Array.tabulate(htConf.sizeFSMArray)(idx => fsmArray(idx).io.freeIdx.pipelined(StreamPipe.FULL)))

  // connect mallocIdx to fsmArray using roundRobin, but arbitrate on ready signal
  // impl by modifying StreamArbiterFactory.roundRobin.transactionLock
  io.mallocIdx.throwWhen(io.mallocIdx.payload === 0).pipelined(StreamPipe.FULL) >> ReverseStreamArbiterFactory().roundRobin.transactionLock.on(Array.tabulate(htConf.sizeFSMArray)(idx => fsmArray(idx).io.mallocIdx))

  // initialization logic
  memInitializer.io.initEn := io.initEn
  memInitializer.io.clearInitStatus := io.clearInitStatus
  val isMemInitDone = memInitializer.io.initDone
  io.initDone := isMemInitDone

  // arbitrate AXI connection
  when(!isMemInitDone){
    for (idx <- 0 until htConf.sizeFSMArray){
      lockManager.io.axiMem(idx).setBlocked()
      if (idx == 0){
        memInitializer.io.axiMem >> io.axiMem(idx)
      } else {
        io.axiMem(idx).setIdle()
      }
    }
  }.otherwise{
    memInitializer.io.axiMem.setBlocked()
    for (idx <- 0 until htConf.sizeFSMArray){
      lockManager.io.axiMem(idx) >> io.axiMem(idx)
    }
  }
}