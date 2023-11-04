package dedup
package hashtable

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import util.Helpers.AxiUtils

object LockManagerOp extends SpinalEnum(binarySequential) {
  val ACQUIRE, RELEASE = newElement()
}

case class lockTableContent(htConf: HashTableConfig) extends Bundle{
  val lockIsActive = Bool()
  val lockedIdxBucket  = UInt(htConf.idxBucketWidth bits)
}

case class FSMLockRequest(htConf: HashTableConfig) extends Bundle {
  val idxBucket = UInt(htConf.idxBucketWidth bits)
  val FSMId     = UInt(log2Up(htConf.sizeFSMArray) bits)
  val opCode    = LockManagerOp()
}

case class HashTableLookupLockManagerIO(htConf: HashTableConfig) extends Bundle {
  val axiConf         = Axi4ConfigAlveo.u55cHBM
  // FSM array request
  val fsmArrayLockReq = Vec(slave Stream(FSMLockRequest(htConf)), htConf.sizeFSMArray)
  val fsmArrayDRAMReq = Vec(slave(Axi4(axiConf)), htConf.sizeFSMArray)
  /** DRAM interface 
   * multiple memory channels are enabled
   * otherwise use complicated arbitration policy 
   * as shown in deprecated.LockManager
  */
  val axiMem          = Vec(master(Axi4(axiConf)), htConf.sizeFSMArray)
}

case class HashTableLookupLockManager(htConf: HashTableConfig) extends Component {
  /* This is the impl using multiple memory channel
    channel count = # lookup FSM
    simpler than the version in deprecated package, which uses only 1 channel
  */

  val io = HashTableLookupLockManagerIO(htConf)

  val fsmArrayLockReqPipelined = Vec(Stream(FSMLockRequest(htConf)), htConf.sizeFSMArray)
  for (idx <- 0 until htConf.sizeFSMArray){
    // fsmArrayLockReqPipelined(idx) << io.fsmArrayLockReq(idx).pipelined(StreamPipe.FULL)
    fsmArrayLockReqPipelined(idx) << io.fsmArrayLockReq(idx)
  }
  val fsmLockReq = StreamArbiterFactory.roundRobin.transactionLock.on(fsmArrayLockReqPipelined).pipelined(StreamPipe.FULL)

  /*Parking Queue cannot be too shallow
    if all colide and lock acquire requests cannot fit into parking queue,
    the lock release request will be blocked and never be issued
    so a safe way to do this is to set the depth as the number of FSMs(= max outstanding request)
    */
  val parkingQueue = new StreamFifo(FSMLockRequest(htConf), htConf.sizeFSMArray)
  
  val lockTable = Vec(Reg(lockTableContent(htConf)), htConf.sizeFSMArray)
  lockTable.foreach{ content =>
    content.lockIsActive init False
    content.lockedIdxBucket init 0
  }

  def checkIsLocked(lockRequest: FSMLockRequest): Bool = {
    lockTable.sExist{ content =>
      (lockRequest.opCode === LockManagerOp.ACQUIRE) & (content.lockedIdxBucket === lockRequest.idxBucket) & content.lockIsActive
    }
  }

  val newReqIsLocked = checkIsLocked(fsmLockReq.payload)

  val parkingReqIsLocked = checkIsLocked(parkingQueue.io.pop.payload)

  val tableManager = new Area{
    // val parkingReqReadyToFire = (!parkingReqIsLocked) & parkingQueue.io.pop.valid
    // val newReqReadyToFire = (!newReqIsLocked) & fsmLockReq.valid
    
    // Locked -> goto parking queue, else issue
    val fsmLockReqSelect = newReqIsLocked ? U(1) | U(0)
    val dispatchedFSMReq = StreamDemux(fsmLockReq, fsmLockReqSelect, 2)

    dispatchedFSMReq(1) >> parkingQueue.io.push

    val parkingInstrStream = parkingQueue.io.pop.continueWhen(!parkingReqIsLocked)
    // ready-to-fire new instr
    val newInstrStream = dispatchedFSMReq(0)
    // parkingQueue.io.pop.ready := parkingReqReadyToFire
    // dispatchedFSMReq(0).ready := newReqReadyToFire & (!parkingReqReadyToFire)
    // dispatchedFSMReq(1) >> parkingQueue.io.push

    val arbitratedAllInstrStream = StreamArbiterFactory.lowerFirst.transactionLock.onArgs(parkingInstrStream, newInstrStream)

    arbitratedAllInstrStream.ready := True
    when(arbitratedAllInstrStream.fire){
      val instr = arbitratedAllInstrStream.payload
      lockTable(instr.FSMId).lockIsActive := (instr.opCode === LockManagerOp.ACQUIRE) ? True | False
      lockTable(instr.FSMId).lockedIdxBucket := (instr.opCode === LockManagerOp.ACQUIRE) ? instr.idxBucket | U(0)
    }
  }

  // axi connect
  for (FSMIdx <- 0 until htConf.sizeFSMArray){
    io.fsmArrayDRAMReq(FSMIdx).continueWhen(lockTable(FSMIdx).lockIsActive) >> io.axiMem(FSMIdx)
  }
}