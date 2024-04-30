package dedup.hashtable

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import spinal.lib.bus.amba4.axi._

import util.AccumIncDec
import dedup.Axi4ConfigAlveo

case class MemManagerConfig (htConf: HashTableConfig) {
  val totalIdxCount = htConf.hashTableSize // number of entries
  val idxWidth = htConf.ptrWidth
  val idxPerWord = Axi4ConfigAlveo.u55cHBM.dataWidth / idxWidth // 512 / 32 = 16
  val axiTransferWordNum = 4
  val idxPerTransfer = axiTransferWordNum * idxPerWord // 64

  val idxBlockByteSize = (axiTransferWordNum * Axi4ConfigAlveo.u55cHBM.dataWidth / 8)
  val idxBlockAddrBitShift = log2Up(idxBlockByteSize)

  // axiFifo config
  val axiStartByte = 0
  val blockCount = (htConf.hashTableOffset - axiStartByte) / idxBlockByteSize
  val blockNeeded = (totalIdxCount + idxPerTransfer - 1)/idxPerTransfer
  val axiId = htConf.sizeFSMArray // for verification, only 1 mem channel in verification
}

case class MemManagerIO(htConf: HashTableConfig) extends Bundle {
  val axiConf     = Axi4ConfigAlveo.u55cHBM
  // interface to other units
  val mallocIdx   = master Stream(UInt(htConf.ptrWidth bits))
  val freeIdx     = slave Stream(UInt(htConf.ptrWidth bits))
  // others
  // val isEmpty     = out Bool()
  val axiMem      = master(Axi4(axiConf))
}
/*
  manage Block indices(not addr)
*/
case class MemManager(htConf: HashTableConfig) extends Component {
  val conf = MemManagerConfig(htConf)

  val idxBufferDepth = conf.idxPerTransfer * 2
  // val initIdxBlockCount = 16

  val idxBufferThr = idxBufferDepth - conf.idxPerTransfer

  val io = MemManagerIO(htConf)
  
  // val rInitDone = RegInit(False)
  // io.initDone := rInitDone

  // on-chip buffer for free-up idx
  val idxBuffer = StreamFifo(UInt(htConf.ptrWidth bits), idxBufferDepth)
  // idxBuffer.io.flush := io.initEn

  val idxBufferNeedPop = (idxBuffer.io.occupancy > idxBufferThr)

  // off-chip index bank in AXI mem
  val idxBank = AxiFifo(conf)
  // idxBank.io.flush  := io.initEn
  idxBank.io.axiMem >> io.axiMem

  // counter for unreleased idx
  val idxCounter     = Counter(1, conf.totalIdxCount) // 0: invalid, idx: 1 -> totalIdxCount
  val idxAllReleased = RegInit(False)
  when(idxCounter.willOverflow){
    idxAllReleased  := True
  }

  val idxReleaseStream    = Stream(UInt(htConf.ptrWidth bits))
  val idxReleaseStreamCut = Stream(UInt(htConf.ptrWidth bits))
  idxReleaseStream.payload := idxCounter.resized
  idxReleaseStream.valid   := !idxAllReleased

  when(idxReleaseStream.fire){
    idxCounter.increment()
  }

  // 0 to malloc, 1 to idx bank(axi mem)
  val idxBufferPortSelect = UInt(1 bits)
  val demuxIdxBufferPop = StreamDemux(idxBuffer.io.pop, idxBufferPortSelect, 2)
  demuxIdxBufferPop(1) >> idxBank.io.push

  // on-chip buffer to off-chip buffer controller
  val idxBufferController = new Area{
    val cntIdxToAxi = Counter(conf.idxPerTransfer)
    when(demuxIdxBufferPop(1).fire){
      cntIdxToAxi.increment()
    }

    val locked = RegInit(False)
    val idxBufferPortSelectProposal = UInt(1 bits)
    val idxBufferPortSelectLocked   = Reg(UInt(1 bits)) init 0
    val idxBufferPortSelectRouted   = Mux(locked, idxBufferPortSelectLocked, idxBufferPortSelectProposal)
    idxBufferPortSelect := idxBufferPortSelectRouted

    // go aximem when too much
    idxBufferPortSelectProposal := idxBufferNeedPop.asUInt

    locked.setWhen(idxBufferNeedPop)
    locked.clearWhen(cntIdxToAxi.willOverflow) // this will overwrite prev one

    when(idxBufferNeedPop || locked){
      idxBufferPortSelectLocked := idxBufferPortSelectRouted
    }

    // when(io.initEn){
    //   locked := False
    //   idxBufferPortSelectLocked := 0
    //   cntIdxToAxi.clear()
    // }
  }
  
  io.mallocIdx << StreamArbiterFactory.lowerFirst.transactionLock.onArgs(demuxIdxBufferPop(0).queue(conf.idxPerTransfer * 16).pipelined(StreamPipe.FULL), idxBank.io.pop.pipelined(StreamPipe.FULL), idxReleaseStreamCut.pipelined(StreamPipe.FULL))

  // when(io.initEn){
  //   idxCounter.clear()
  //   idxAllReleased := False
  // }

  io.freeIdx >> idxBuffer.io.push
  idxReleaseStream >> idxReleaseStreamCut

//   val fsm = new StateMachine {
//     val IDLE         = new State with EntryPoint
//     val INIT, ONLINE = new State
    
//     val initIdxCounter = (initIdxBlockCount > 0) generate Counter(conf.idxPerTransfer)
//     val initBlockCounter = (initIdxBlockCount > 0) generate Counter(initIdxBlockCount)

//     // default status
//     io.freeIdx.setBlocked()
//     idxBuffer.io.push.setIdle()
//     idxReleaseStream.setBlocked()
//     idxReleaseStreamCut.setIdle()

//     always{
//       when(io.initEn){
//         goto(INIT)
//       }
//     }

//     IDLE.whenIsActive{
//       io.freeIdx.setBlocked()
//       idxBuffer.io.push.setIdle()
//       idxReleaseStream.setBlocked()
//       idxReleaseStreamCut.setIdle()
//     }

//     INIT.onEntry{
//       if (initIdxBlockCount > 0){
//         initIdxCounter.clear()
//         initBlockCounter.clear()
//       }
//       idxCounter.clear()
//       idxAllReleased := False
//     }

//     INIT.whenIsActive{
//       if (initIdxBlockCount > 0){
//         io.freeIdx.setBlocked()
//         idxReleaseStream >> idxBuffer.io.push
//         idxReleaseStreamCut.setIdle()
//         when(idxReleaseStream.fire){
//           initIdxCounter.increment()
//         }
//         when(initIdxCounter.willOverflow){
//           initBlockCounter.increment()
//         }
//         when(initBlockCounter.willOverflow){
//           rInitDone := True
//           goto(ONLINE)
//         }
//       } else {
//         io.freeIdx.setBlocked()
//         idxBuffer.io.push.setIdle()
//         idxReleaseStream.setBlocked()
//         idxReleaseStreamCut.setIdle()
//         rInitDone := True
//         goto(ONLINE)
//       }
//     }

//     ONLINE.whenIsActive{
//       io.freeIdx >> idxBuffer.io.push
//       idxReleaseStream >> idxReleaseStreamCut
//     }
//   }
}

case class AxiFifo(conf: MemManagerConfig) extends Component {
  val io = new Bundle{
    val push = slave Stream (UInt(conf.idxWidth bits))
    val pop  = master Stream (UInt(conf.idxWidth bits))
    val flush = in Bool() default(False)
    val idxCount = out UInt((1 + conf.idxWidth) bits)

    val axiConf = Axi4ConfigAlveo.u55cHBM
    val axiMem  = master(Axi4(axiConf))
  }

  val freeIdxWideQ   = StreamFifo (Bits(Axi4ConfigAlveo.u55cHBM.dataWidth bits), conf.axiTransferWordNum * 2)
  StreamWidthAdapter(io.push, freeIdxWideQ.io.push)
  freeIdxWideQ.io.flush   := io.flush

  val mallocIdxWideQ = StreamFifo (Bits(Axi4ConfigAlveo.u55cHBM.dataWidth bits), conf.axiTransferWordNum * 2)
  StreamWidthAdapter(mallocIdxWideQ.io.pop, io.pop)
  mallocIdxWideQ.io.flush := io.flush

  val cntIdxInside = AccumIncDec((1 + conf.idxWidth) bits, io.push.fire, io.pop.fire, 1, 1)
  io.idxCount := cntIdxInside.accum

  // axiCtrlLogic
  val popCmdPtr   = Counter(conf.blockCount)
  val popRspPtr   = Counter(conf.blockCount)
  val pushCmdPtr  = Counter(conf.blockCount)
  val pushDataPtr = Counter(conf.blockCount)
  val pushRspPtr  = Counter(conf.blockCount)

  val popCmdPtrMatch   = (popCmdPtr   === pushRspPtr)
  val popRspPtrMatch   = (popRspPtr   === pushRspPtr)
  val pushCmdPtrMatch  = (pushCmdPtr  === popRspPtr)
  val pushDataPtrMatch = (pushDataPtr === popRspPtr)
  val pushRspPtrMatch  = (pushRspPtr  === popRspPtr)

  val risingOccupancyPopCmd   = RegInit(False)
  val risingOccupancyPopRsp   = RegInit(False)
  val risingOccupancyPushCmd  = RegInit(False)
  val risingOccupancyPushData = RegInit(False)
  val risingOccupancyPushRsp  = RegInit(False)

  val poppingCmd  = io.axiMem.readCmd.fire
  val poppingRsp  = io.axiMem.readRsp.fire & io.axiMem.readRsp.last
  val pushingCmd  = io.axiMem.writeCmd.fire
  val pushingData = io.axiMem.writeData.fire & io.axiMem.writeData.last
  val pushingRsp  = io.axiMem.writeRsp.fire

  // 2x empty, for cmd, rsp
  when(pushingRsp =/= poppingCmd) {
    risingOccupancyPopCmd := pushingRsp
  }
  val emptyPopCmd = popCmdPtrMatch & !risingOccupancyPopCmd

  when(pushingRsp =/= poppingRsp) {
    risingOccupancyPopRsp := pushingRsp
  }
  val emptyPopRsp = popRspPtrMatch & !risingOccupancyPopRsp

  // 3x full, for cmd, data, rsp
  when(pushingCmd =/= poppingRsp) {
    risingOccupancyPushCmd := pushingCmd
  }
  val fullPushCmd = pushCmdPtrMatch & risingOccupancyPushCmd

  when(pushingData =/= poppingRsp) {
    risingOccupancyPushData := pushingData
  }
  val fullPushData = pushDataPtrMatch & risingOccupancyPushData

  when(pushingRsp =/= poppingRsp) {
    risingOccupancyPushRsp := pushingRsp
  }
  val fullPushRsp = pushRspPtrMatch & risingOccupancyPushRsp

  // free index push logic
  val axiPushCmd = Stream (Axi4Aw(io.axiConf))
  val idxBlockPushStartAddr = conf.axiStartByte + (pushCmdPtr << conf.idxBlockAddrBitShift)
  axiPushCmd.addr  := idxBlockPushStartAddr.resized
  axiPushCmd.id    := conf.axiId
  axiPushCmd.len   := conf.axiTransferWordNum - 1
  axiPushCmd.size  := log2Up(io.axiConf.dataWidth/8)
  axiPushCmd.setBurstINCR()

  val cntWordToWrite = AccumIncDec((1 + 1 + log2Up(conf.axiTransferWordNum * 2)) bits, axiPushCmd.fire, io.axiMem.writeData.fire, conf.axiTransferWordNum, 1).accum
  axiPushCmd.valid := ((freeIdxWideQ.io.occupancy - cntWordToWrite).asSInt >= conf.axiTransferWordNum)
  when(axiPushCmd.fire){
    pushCmdPtr.increment()
  }

  io.axiMem.writeCmd << axiPushCmd.continueWhen(!fullPushCmd)

  val cntDataLast = Counter(conf.axiTransferWordNum, io.axiMem.writeData.fire)
  io.axiMem.writeData.translateFrom(freeIdxWideQ.io.pop.continueWhen(!fullPushData))((a,b) => {
    a.data := b
    a.last := cntDataLast.willOverflowIfInc
    a.strb.setAll()
  })
  when(io.axiMem.writeData.fire && io.axiMem.writeData.last){
    pushDataPtr.increment()
  }

  io.axiMem.writeRsp.freeRun()
  when(io.axiMem.writeRsp.fire){
    pushRspPtr.increment()
  }
  
  // malloc index pop logic
  // val axiCtrlPopValid = !empty & !(RegNext(popPtr.valueNext === pushPtr, False) & !full) //mem write to read propagation
  val axiPopCmd = Stream (Axi4Ar(io.axiConf))
  val idxBlockPopStartAddr = conf.axiStartByte + (popCmdPtr << conf.idxBlockAddrBitShift)
  axiPopCmd.addr  := idxBlockPopStartAddr.resized
  axiPopCmd.id    := conf.axiId
  axiPopCmd.len   := conf.axiTransferWordNum - 1
  axiPopCmd.size  := log2Up(io.axiConf.dataWidth/8)
  axiPopCmd.setBurstINCR()
  
  val cntWordToRead = AccumIncDec((1 + log2Up(conf.axiTransferWordNum * 2)) bits, axiPopCmd.fire, io.axiMem.readRsp.fire, conf.axiTransferWordNum, 1).accum
  axiPopCmd.valid := ((mallocIdxWideQ.io.occupancy + cntWordToRead) <= conf.axiTransferWordNum)
  when(axiPopCmd.fire){
    popCmdPtr.increment()
  }

  io.axiMem.readCmd << axiPopCmd.continueWhen(!emptyPopCmd)

  mallocIdxWideQ.io.push.translateFrom(io.axiMem.readRsp)(_ := _.data)
  when(io.axiMem.readRsp.fire && io.axiMem.readRsp.last){
    popRspPtr.increment()
  }

  when(io.flush){
    popCmdPtr  .clear()
    popRspPtr  .clear()
    pushCmdPtr .clear()
    pushDataPtr.clear()
    pushRspPtr .clear()

    risingOccupancyPopCmd   := False
    risingOccupancyPopRsp   := False
    risingOccupancyPushCmd  := False
    risingOccupancyPushData := False
    risingOccupancyPushRsp  := False
  }
}

