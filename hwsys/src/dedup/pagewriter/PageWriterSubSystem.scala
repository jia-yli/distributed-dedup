package dedup
package pagewriter

import spinal.core.Component.push
import spinal.core._
import spinal.lib._

import registerfile.HashTableLookupRes
import util.{CntDynmicBound, FrgmDemux}

case class PageWriterConfig(readyQueueLogDepth : Int = 2,
                            waitingQueueLogDepth : Int = 8,
                            // old impl: 8192 x 2
                            // 128 x 64 fragment = 8192 = 1 << 13 ~ SHA3 latency x2
                            // + network latency + margin: (1 << 13) x 4 = 1 << 15
                            pageFrgmQueueLogDepth : Int = 15,
                            pageFrgmQueueCount : Int = 4) {
  val instrTagWidth = if (readyQueueLogDepth > waitingQueueLogDepth) (readyQueueLogDepth + 1) else (waitingQueueLogDepth + 1)
  assert(pageFrgmQueueCount >= 1)
}

object SSDOp extends SpinalEnum(binarySequential) {
  /*write: write page and header
    erase: erase page and header
    read: read region(normal read)
    updateheader: only operate on header
  */
  val WRITE, ERASE, READ, UPDATEHEADER = newElement()
}

case class CombinedFullInstr (conf: DedupConfig) extends Bundle {
  // contain all info for Resp and SSD, and also tag for arbitration
  val SHA3Hash        = Bits(conf.htConf.hashValWidth bits)
  val RefCount        = UInt(conf.lbaWidth bits)
  // fpga store position
  val SSDLBA          = UInt(conf.lbaWidth bits)
  val nodeIdx         = UInt(conf.nodeIdxWidth bits)
  // original instr
  val hostLBAStart    = UInt(conf.lbaWidth bits)
  val hostLBALen      = UInt(conf.lbaWidth bits)
  val hostDataNodeIdx = UInt(conf.dataNodeIdxWidth bits)
  val opCode          = DedupCoreOp()
  val tag             = UInt(conf.pwConf.instrTagWidth bits)
}

case class PageWriterResp (conf: DedupConfig) extends Bundle {
  val SHA3Hash        = Bits(conf.htConf.hashValWidth bits)
  val RefCount        = UInt(conf.lbaWidth bits)
  // fpga store position
  val SSDLBA          = UInt(conf.lbaWidth bits)
  val nodeIdx         = UInt(conf.nodeIdxWidth bits)
  // original instr
  val hostLBAStart    = UInt(conf.lbaWidth bits)
  val hostLBALen      = UInt(conf.lbaWidth bits)
  val hostDataNodeIdx = UInt(conf.dataNodeIdxWidth bits)
  // True means, new page(write exec), or GC (del exec), always True in read
  val isExec          = Bool() 
  val opCode          = DedupCoreOp()
}

case class SSDInstr (conf: DedupConfig) extends Bundle {
  // page header + operation, since header is small
  val SHA3Hash    = Bits(conf.htConf.hashValWidth bits)
  val RefCount    = UInt(conf.lbaWidth bits)
  val SSDNodeIdx  = UInt(conf.dataNodeIdxWidth bits)
  val SSDLBAStart = UInt(conf.lbaWidth bits)
  // in dedup, write/erase LBALen = 1, read LBALen = input LBALen
  val SSDLBALen   = UInt(conf.lbaWidth bits)
  val nodeIdx     = UInt(conf.nodeIdxWidth bits)
  val opCode      = SSDOp()
}

/* the job of page writer is:
  1. buffer the input pages and wait for hash table lookup results
  2. based on the lookup results, do:
    1. decide (for insertion)write the page or not/(for deletion)delete the page or not
    2. update (or delete) page header
    3. send metadata/resp/read data back to host
*/
case class PageWriterSSIO(conf: DedupConfig) extends Bundle {
  val initEn       = in Bool ()
  val opStrmIn     = slave Stream (Bits(conf.instrTotalWidth bits))
  val pgStrmFrgmIn = slave Stream (Fragment(Bits(conf.wordSizeBit bits)))
  val lookupRes    = slave Stream (HashTableLookupRes(conf))
  val res          = master Stream (PageWriterResp(conf))
  /** mock SSD interface */
  /* all instr and datain go to sink, and there will be no resp for write/read/erase
    3 ports: dataIn, instrIn, dataOut, header is packed in the instrIn(512-bit wide)
  */
  val SSDDataIn        = master Stream (Fragment(Bits(conf.wordSizeBit bits)))
  val SSDDataOut       = slave Stream (Fragment(Bits(conf.wordSizeBit bits)))
  val SSDInstrIn       = master Stream (SSDInstr(conf))
  // val axiConf = Axi4ConfigAlveo.u55cHBM
  // val axiStore = master(Axi4(axiConf))
  /** bandwidth controller */
  val factorThrou = in UInt(5 bits) // dummy for now
}

case class PageWriterSubSystem(conf: DedupConfig) extends Component {
  val io     = PageWriterSSIO(conf)

  val pwConf = conf.pwConf

  /** queue here to avoid blocking the wrap pgIn, which is also forked to BF & SHA3 */
  val frgmInBufferArray = Array.tabulate(conf.pwConf.pageFrgmQueueCount){idx =>
    StreamFifo(Fragment(Bits(conf.wordSizeBit bits)), 1 << (conf.pwConf.pageFrgmQueueLogDepth - log2Up(conf.pwConf.pageFrgmQueueCount)))}
  val lookupResBuffer = StreamFifo(HashTableLookupRes(conf), 4)

  io.pgStrmFrgmIn.pipelined(StreamPipe.FULL) >> frgmInBufferArray(0).io.push
  if (conf.pwConf.pageFrgmQueueCount > 1){
    for (idx <- 0 until conf.pwConf.pageFrgmQueueCount - 1){
      frgmInBufferArray(idx).io.pop >> frgmInBufferArray(idx + 1).io.push
    }
  }
  io.lookupRes.pipelined(StreamPipe.FULL)    >>  lookupResBuffer.io.push

  val frgmInQ    = frgmInBufferArray(conf.pwConf.pageFrgmQueueCount - 1).io.pop   
  val lookupResQ = lookupResBuffer.io.pop

  val instrDecoder             = PageWriterInstrDecoder(conf)
  val decodedReadyInstrQueue   = StreamFifo(DecodedReadyInstr(conf), 1 << pwConf.readyQueueLogDepth)
  val decodedWaitingInstrQueue = StreamFifo(DecodedWaitingInstr(conf), 1 << pwConf.waitingQueueLogDepth)

  // decoder + decoded instr Queue
  io.opStrmIn.pipelined(StreamPipe.FULL) >> instrDecoder.io.rawInstrStream
  instrDecoder.io.readyInstrStream       >> decodedReadyInstrQueue.io.push
  instrDecoder.io.waitingInstrStream     >> decodedWaitingInstrQueue.io.push
  
  val (lookupResToData, lookupResToInstr) = StreamFork2(lookupResQ)
  // output 0 Data in
  // dataIn = input page, drop when(lookup exist)
  // lookup exist = lookupRes.drop(not write).when(#ref > 1)
  // extract results only for write instr
  val insertionLookupRes = lookupResToData.throwWhen(!(lookupResToData.payload.opCode === DedupCoreOp.WRITE2FREE))
  val pgNeedStore        = Reg(Bool()) init False
  val pgNeedStoreValid   = Reg(Bool()) init False
  insertionLookupRes.ready := !pgNeedStoreValid
  when(frgmInQ.lastFire){
    // wait for next lookup Res
    // if insertionLookupRes valid
    insertionLookupRes.ready := True
    pgNeedStoreValid := False
  }
  when(insertionLookupRes.fire){
    pgNeedStore := !(insertionLookupRes.RefCount > 1)
    pgNeedStoreValid := True
  }

  io.SSDDataIn << frgmInQ.continueWhen(pgNeedStoreValid).throwWhen(!pgNeedStore).pipelined(StreamPipe.FULL)
  
  // output 1 Instr & header in, also Resp back to host
  val instrIssuer = PageWriterInstrIssuer(conf)
  instrIssuer.io.initEn              := io.initEn
  instrIssuer.io.readyInstrStream    << decodedReadyInstrQueue.io.pop.pipelined(StreamPipe.FULL)
  instrIssuer.io.waitingInstrStream  << decodedWaitingInstrQueue.io.pop.pipelined(StreamPipe.FULL)
  instrIssuer.io.lookupResStream     << lookupResToInstr.pipelined(StreamPipe.FULL)

  // send instr to SSD and send resp back
  val mockSSDController = new Area{
    val forkedFullInstrStream = StreamFork2(instrIssuer.io.instrIssueStream.pipelined(StreamPipe.FULL))
    io.SSDInstrIn.translateFrom(forkedFullInstrStream._1){(storageInstr, fullInstr) =>
      storageInstr.SHA3Hash    := fullInstr.SHA3Hash
      storageInstr.RefCount    := fullInstr.RefCount
      storageInstr.SSDNodeIdx  := fullInstr.hostDataNodeIdx
      storageInstr.SSDLBAStart := fullInstr.SSDLBA
      storageInstr.SSDLBALen   := fullInstr.hostLBALen
      storageInstr.nodeIdx     := fullInstr.nodeIdx
      when(fullInstr.opCode === DedupCoreOp.WRITE2FREE){
        storageInstr.opCode := (fullInstr.RefCount === 1) ? SSDOp.WRITE | SSDOp.UPDATEHEADER
      }.elsewhen(fullInstr.opCode === DedupCoreOp.ERASEREF){
        storageInstr.opCode := (fullInstr.RefCount === 0) ? SSDOp.ERASE | SSDOp.UPDATEHEADER
      }.elsewhen(fullInstr.opCode === DedupCoreOp.READSSD){
        storageInstr.opCode := SSDOp.READ
      }.otherwise{
        storageInstr.opCode := SSDOp.READ
      }
    }
    
    val fullInstrResQ = StreamFifo(CombinedFullInstr(conf), 4)
    fullInstrResQ.io.push  << forkedFullInstrStream._2

    io.res.translateFrom(fullInstrResQ.io.pop){(resp, fullInstr) =>
      resp.SHA3Hash      := fullInstr.SHA3Hash
      resp.RefCount      := fullInstr.RefCount
      resp.SSDLBA        := fullInstr.SSDLBA
      resp.nodeIdx       := fullInstr.nodeIdx
      resp.hostLBAStart  := fullInstr.hostLBAStart
      resp.hostLBALen    := fullInstr.hostLBALen
      resp.hostDataNodeIdx := fullInstr.hostDataNodeIdx
      resp.opCode        := fullInstr.opCode

      when(fullInstr.opCode === DedupCoreOp.WRITE2FREE){
        resp.isExec := (fullInstr.RefCount === 1) ? True | False
      }.elsewhen(fullInstr.opCode === DedupCoreOp.ERASEREF){
       resp.isExec := (fullInstr.RefCount === 0) ? True | False
      }.elsewhen(fullInstr.opCode === DedupCoreOp.READSSD){
       resp.isExec := True
      }.otherwise{
        resp.isExec := True
      }
    }
  }
  // instrIssuer.io.instrIssueStream    >> bfLookupFSM.io.instrStrmIn
  
  // SSD output: read resp
  io.SSDDataOut.setBlocked()
}

case class CntDynamic(upBoundEx: UInt, incFlag: Bool) {
  val cnt = Reg(UInt(upBoundEx.getWidth bits)).init(0)
  val willOverflowIfInc = (cnt === upBoundEx -1)
  val willClear = False.allowOverride
  def clearAll(): Unit = willClear := True
  when(~willOverflowIfInc & incFlag) {
    cnt := cnt + 1
  }
  when(willClear) (cnt.clearAll())
}

case class MockSSD(conf: DedupConfig) extends Component {
  // val io = new Bundle{
  //   val dataIn        = slave Stream (Fragment(Bits(conf.wordSizeBit bits)))
  //   val dataOut       = master Stream (Fragment(Bits(conf.wordSizeBit bits)))
  //   // val instrIn       = 
  //   // val headerDataIn  = 
  //   // val headerDataOut = 
  //   // val headerInstrIn = 
  // }

}