package dedup

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axilite._

import util.RenameIO
import util.Helpers._
import coyote.HostDataIO

case class SysResp (conf: DedupConfig = DedupConfig()) extends Bundle {
  val SHA3Hash     = Bits(conf.htConf.hashValWidth bits)
  val RefCount     = UInt(conf.htConf.ptrWidth bits)
  val SSDLBA       = UInt(conf.htConf.ptrWidth bits)
  val hostLBAStart = UInt(conf.htConf.ptrWidth bits)
  val hostLBALen   = UInt(conf.htConf.ptrWidth bits)
  val padding      = UInt((512 - conf.htConf.hashValWidth - conf.htConf.ptrWidth * 4 - 1 - DedupCoreOp().getBitsWidth) bits)
  val isExec       = Bool() 
  val opCode       = DedupCoreOp()
}

class WrapDedupSys() extends Component with RenameIO {

  val io = new Bundle {
    val axi_ctrl = slave(AxiLite4(AxiLite4Config(64, 64)))
    val hostd = new HostDataIO
    val axi_mem = Vec(master(Axi4(Axi4ConfigAlveo.u55cHBM)), DedupConfig().htConf.sizeFSMArray + 1)
  }

  val dedupCore = new WrapDedupCore()
  val sysIntf   = new SysIntf()
  val hostIntf  = new HostIntf()

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
  //REG: host interface (addr: 2-8)
  val ctrlRNumHostIntf = hostIntf.io.regMap(ctrlR, 2)

  //Resp logic
  val pgRespPad = Stream(Bits(512 bits))
  pgRespPad.translateFrom(dedupCore.io.pgResp)((bitsPaddedResp, rawResp) => {
    val paddedResp = SysResp()
    paddedResp assignSomeByName rawResp
    paddedResp.padding := 0
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

  /** pgStore throughput control [0:15] */
  dedupCore.io.factorThrou := ctrlR.createReadAndWrite(UInt(5 bits), (ctrlRNumHostIntf+2) << log2Up(ctrlRByteSize), 0)

}
