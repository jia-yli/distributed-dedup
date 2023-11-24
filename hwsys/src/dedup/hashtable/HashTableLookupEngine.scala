package dedup
package hashtable

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import routingtable.RoutedLookupInstr
import routingtable.RoutedWriteBackLookupRes
import util.ReverseStreamArbiterFactory

/* Lookup Engine = Arbitration logic + FSM array + lock table 
  make sure seq instr in, seq res out in same order*/
case class HashTableLookupEngineIO(conf: DedupConfig) extends Bundle {
  val initEn      = in Bool()
  val clearInitStatus = in Bool()
  val initDone    = out Bool()
  val updateRoutingTableContent = in Bool()
  val nodeIdx     = in UInt(conf.nodeIdxWidth bits)
  // execution results
  val instrStrmIn = slave Stream(RoutedLookupInstr(conf))
  val res         = master Stream(RoutedWriteBackLookupRes(conf))
  // To Allocator
  val mallocIdx   = slave Stream(UInt(conf.htConf.ptrWidth bits))
  val freeIdx     = master Stream(UInt(conf.htConf.ptrWidth bits))
  /** DRAM interface */
  val axiConf     = Axi4ConfigAlveo.u55cHBM
  val axiMem      = Vec(master(Axi4(axiConf)), conf.htConf.sizeFSMArray)
}

case class HashTableLookupEngine(conf: DedupConfig) extends Component {
  val htConf = conf.htConf
  val io = HashTableLookupEngineIO(conf)

  val fsmInstrInArray = Array.fill(htConf.sizeFSMArray)(Stream(RoutedLookupInstr(conf)))
  val fsmResBufferArray = Array.fill(htConf.sizeFSMArray)(new StreamFifo(RoutedWriteBackLookupRes(conf), 4))
  val memInitializer = HashTableMemInitializer(htConf)
  val lockManager = HashTableLookupLockManager(htConf)

  val fsmArray = Array.tabulate(htConf.sizeFSMArray){idx => 
    val fsmInstance = HashTableLookupFSM(conf, idx)
    fsmInstance.io.initEn := io.initEn
    fsmInstance.io.updateRoutingTableContent := io.updateRoutingTableContent
    fsmInstance.io.nodeIdx := io.nodeIdx
    fsmInstrInArray(idx) >> fsmInstance.io.instrStrmIn
    fsmInstance.io.res >> fsmResBufferArray(idx).io.push
    fsmInstance.io.lockReq.pipelined(StreamPipe.FULL) >> lockManager.io.fsmArrayLockReq(idx)
    fsmInstance.io.axiMem.pipelined(StreamPipe.FULL,StreamPipe.FULL,StreamPipe.FULL,StreamPipe.FULL,StreamPipe.FULL) >> lockManager.io.fsmArrayDRAMReq(idx)
    fsmInstance
  }
  // connect instr dispatcher to fsm
  io.instrStrmIn >> ReverseStreamArbiterFactory().roundRobin.transactionLock.on(Array.tabulate(htConf.sizeFSMArray)(idx => fsmInstrInArray(idx)))
  io.res << StreamArbiterFactory.roundRobin.transactionLock.on(Array.tabulate(htConf.sizeFSMArray)(idx => fsmResBufferArray(idx).io.pop)).pipelined(StreamPipe.FULL)
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