package dedup

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axilite._

import util.RenameIO
import util.Helpers._
import coyote.HostDataIO
import coyote.NetworkDataIO

case class SysResp (conf: DedupConfig) extends Bundle {
  val SHA3Hash      = Bits(conf.htConf.hashValWidth bits)
  val RefCount      = UInt(conf.htConf.ptrWidth bits)
  val SSDLBA        = UInt(conf.htConf.ptrWidth bits)
  val paddedNodeIdx = UInt(32 bits)
  val hostLBAStart  = UInt(conf.htConf.ptrWidth bits)
  val hostLBALen    = UInt(conf.htConf.ptrWidth bits)
  val padding       = UInt((512 - conf.htConf.hashValWidth - conf.htConf.ptrWidth * 4 - 32 - 1 - DedupCoreOp().getBitsWidth) bits)
  val isExec        = Bool() 
  val opCode        = DedupCoreOp()
}

class WrapDedupSys(conf: DedupConfig = DedupConfig()) extends Component with RenameIO {

  val io = new Bundle {
    val axi_ctrl = slave(AxiLite4(AxiLite4Config(64, 64)))
    val hostd = new HostDataIO
    val networkIo = new NetworkDataIO
    val axi_mem = Vec(master(Axi4(Axi4ConfigAlveo.u55cHBM)), DedupConfig().htConf.sizeFSMArray + 1)
  }

  val hostIntf    = new HostIntf()
  val networkIntf = new NetworkIntf(conf)
  val dedupCore   = new WrapDedupCore()
  val sysIntf     = new SysIntf()
  

  /** pipeline the axi_mem & pgStrmIn for SLR crossing
   * dedupCore is arranged to SLR1, pgMover and other logic are in SLR0 */
  sysIntf.io.mergedStrm << hostIntf.io.pgStrm.pipelined(StreamPipe.FULL)
  dedupCore.io.pgStrmIn << sysIntf.io.pageStrm
  dedupCore.io.opStrmIn << sysIntf.io.instrStrm
  for (idx <- 0 until DedupConfig().htConf.sizeFSMArray + 1){
    dedupCore.io.axiMem(idx).pipelined(StreamPipe.FULL) >> io.axi_mem(idx)
  }
  // io.axi_mem_0 << dedupCore.io.axiMem.pipelined(StreamPipe.FULL)
  hostIntf.io.hostd.connectAllByName(io.hostd)
  networkIntf.io.networkData.connectAllByName(io.networkIo)
  dedupCore.io.remoteRecvStrmIn << networkIntf.io.recvDataStrm
  (dedupCore.io.remoteSendStrmOut, networkIntf.io.sendDataStrmVec).zipped.foreach{_ >> _}

  val ctrlR = new AxiLite4SlaveFactory(io.axi_ctrl, useWriteStrobes = true)
  val ctrlRByteSize = ctrlR.busDataWidth/8
  //REG: initEn (write only, addr: 0, bit: 0, auto-reset)
  val rInitExtended = ctrlR.createWriteOnly(Bits(2 bits), 0 << log2Up(ctrlRByteSize), 0)
  when (rInitExtended(0)){
    rInitExtended := 0
  }
  val rInit = rInitExtended(0)
  dedupCore.io.initEn := rInit
  sysIntf.io.initEn   := rInit
  hostIntf.io.initEn  := rInit
  dedupCore.io.clearInitStatus := rInitExtended(1) && rInitExtended(0)
  //REG: initDone (readOnly, addr: 1, bit: 0)
  ctrlR.read(dedupCore.io.initDone, 1 << log2Up(ctrlRByteSize), 0)
  //REG: host interface (addr: 2-2+8)
  val ctrlRNumHostIntf = hostIntf.io.regMap(ctrlR, 2) // 8
  /** pgStore throughput control [0:15] */
  dedupCore.io.factorThrou := ctrlR.createReadAndWrite(UInt(5 bits), (ctrlRNumHostIntf + 2) << log2Up(ctrlRByteSize), 0) // 10
  val routingRegStart = ctrlRNumHostIntf + 3 // 11
  val rUpdateRoutingTableContent = ctrlR.createWriteOnly(Bool(), routingRegStart << log2Up(ctrlRByteSize), 0) // 11
  rUpdateRoutingTableContent.clearWhen(rUpdateRoutingTableContent)
  val rActiveChannelCount = ctrlR.createReadAndWrite(UInt((conf.rtConf.routingChannelLogCount + 1) bits), (routingRegStart + 1) << log2Up(ctrlRByteSize), 0) // 12

  networkIntf.io.updateRoutingTableContent := rUpdateRoutingTableContent
  dedupCore.io.updateRoutingTableContent := rUpdateRoutingTableContent
  dedupCore.io.routingTableContent.activeChannelCount := rActiveChannelCount

  val rNodeIdx        = Vec(UInt(conf.nodeIdxWidth bits), conf.rtConf.routingChannelCount)
  val rHashValueStart = Vec(UInt(conf.rtConf.routingDecisionBits bits), conf.rtConf.routingChannelCount)
  val rHashValueLen   = Vec(UInt((conf.rtConf.routingDecisionBits + 1) bits), conf.rtConf.routingChannelCount)
  for (i <- 0 until conf.rtConf.routingChannelCount){ // 13 -> 36
    rNodeIdx       (i) := ctrlR.createReadAndWrite(Reg(UInt(conf.nodeIdxWidth bits))                    , (routingRegStart + 2 + 3 * i) << log2Up(ctrlRByteSize), 0)
    rHashValueStart(i) := ctrlR.createReadAndWrite(Reg(UInt(conf.rtConf.routingDecisionBits bits))      , (routingRegStart + 3 + 3 * i) << log2Up(ctrlRByteSize), 0)
    rHashValueLen  (i) := ctrlR.createReadAndWrite(Reg(UInt((conf.rtConf.routingDecisionBits + 1) bits)), (routingRegStart + 4 + 3 * i) << log2Up(ctrlRByteSize), 0)
  }
  val rdmaQpnRegStart = routingRegStart + 2 + 3 * conf.rtConf.routingChannelCount // 37 -> 43
  for (i <- 0 until conf.rtConf.routingChannelCount - 1){
    networkIntf.io.rdmaQpnVec(i) := ctrlR.createReadAndWrite(Reg(UInt(conf.rdmaQpnWidth bits)), (rdmaQpnRegStart + i) << log2Up(ctrlRByteSize), 0)
  }

  dedupCore.io.routingTableContent.hashValueStart := rHashValueStart
  dedupCore.io.routingTableContent.hashValueLen   := rHashValueLen
  dedupCore.io.routingTableContent.nodeIdx        := rNodeIdx

  //Resp logic
  val pgRespPad = Stream(Bits(512 bits))
  pgRespPad.translateFrom(dedupCore.io.pgResp)((bitsPaddedResp, rawResp) => {
    val paddedResp = SysResp(conf)
    paddedResp assignSomeByName rawResp
    paddedResp.padding := 0
    paddedResp.paddedNodeIdx := rawResp.nodeIdx.resized
    bitsPaddedResp     := paddedResp.asBits
  })

  // dummy SSD interface
  dedupCore.io.SSDDataIn .ready    := True
  dedupCore.io.SSDDataOut.valid    := False
  dedupCore.io.SSDDataOut.fragment := 0
  dedupCore.io.SSDDataOut.last     := False
  dedupCore.io.SSDInstrIn.ready    := True

  /** SLR0 << SLR1 */
  hostIntf.io.pgResp << pgRespPad.pipelined(StreamPipe.FULL)
}
