package dedup

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axilite._
import spinal.lib.fsm.StateMachine
import coyote._
import util.Helpers._
import util._

class HostIntfIO(axiConf: Axi4Config) extends Bundle {
  // ctrl
  val start = in Bool()
  val rdHostAddr = in UInt(64 bits)
  val wrHostAddr = in UInt(64 bits)
  val len = in UInt(16 bits) // set 16 bits to avoid wide adder
  val cnt = in UInt(64 bits)
  val pid = in UInt(6 bits)
  val rdDone, wrDone = out(Reg(UInt(32 bits))).init(0)

  // host IO
  val hostd = new HostDataIO

  // page stream
  val pgStrm = master Stream(Bits(512 bits))

  // resp stream
  val pgResp = slave Stream(Bits(512 bits))

  // flush Queue
  val initEn = in Bool()

  def regMap(r: AxiLite4SlaveFactory, baseR: Int): Int = {
    implicit val baseReg = baseR
    val rStart = r.rwInPort(start, (baseReg + 0) << log2Up(r.busDataWidth/8), 0)
    rStart.clearWhen(rStart)
    r.rwInPort(rdHostAddr, r.getAddr(1), 0, "HostIntf: rdHostAddr")
    r.rwInPort(wrHostAddr, r.getAddr(2), 0, "HostIntf: wrHostAddr")
    r.rwInPort(len,      r.getAddr(3), 0, "HostIntf: len")
    r.rwInPort(cnt,      r.getAddr(4), 0, "HostIntf: cnt")
    r.rwInPort(pid,      r.getAddr(5), 0, "HostIntf: pid")
    r.read(rdDone,      r.getAddr(6), 0, "HostIntf: rdDone")
    r.read(wrDone,      r.getAddr(7), 0, "HostIntf: wrDone")
    val assignOffs = 8
    assignOffs
  }
}

class HostIntf() extends Component {

  val io = new HostIntfIO(Axi4ConfigAlveo.u55cHBM)

  val rdHostReq, wrHostReq = Reg(UInt(32 bits)).init(0)
  val rdAddr, wrAddr = Reg(UInt(64 bits)).init(0)

  val bpss_rd_req, bpss_wr_req = ReqT()

  /** read path */
  val fsm = new StateMachine {
    val IDLE = new State with EntryPoint
    val RD= new State

    IDLE.whenIsActive {
      when(io.start) (goto(RD))
    }

    IDLE.onExit {
      rdAddr := io.rdHostAddr
      wrAddr := io.wrHostAddr
      List(rdHostReq, wrHostReq, io.rdDone, io.wrDone).foreach(_.clearAll())
    }

    // host read
    RD.whenIsActive {
      when(io.rdDone === io.cnt) (goto(IDLE))
    }
  }

  bpss_rd_req.vaddr := rdAddr.resized
  bpss_rd_req.len := io.len.resized
  bpss_rd_req.stream := False
  bpss_rd_req.sync := False
  bpss_rd_req.ctl := True
  bpss_rd_req.host := True
  bpss_rd_req.dest := 0
  bpss_rd_req.pid := io.pid
  bpss_rd_req.vfid := 0
  bpss_rd_req.rsrvd := 0
  io.hostd.bpss_rd_req.data.assignFromBits(bpss_rd_req.asBits)
  io.hostd.bpss_rd_req.valid := (rdHostReq =/= io.cnt) && fsm.isActive(fsm.RD)
  when(io.hostd.bpss_rd_req.fire) {
    rdHostReq := rdHostReq + 1
    rdAddr := rdAddr + io.len
  }
  io.hostd.bpss_rd_done.freeRun()
  when(io.hostd.bpss_rd_done.fire)(io.rdDone := io.rdDone + 1)
  /** axis_host_sink -> page stream */
  io.pgStrm.translateFrom(io.hostd.axis_host_sink)(_ := _.tdata)


  /** write path */

  val pgRespQWide = StreamFifo(Bits(512 bits), 512)
  pgRespQWide.io.push << io.pgResp
  pgRespQWide.io.flush := io.initEn
  // pgRespQWide.io.push.translateFrom(io.pgResp.slowdown(4)) (_ := _.asBits)

  val wrReqBatchSize = 1024 // batch resp of 16 pages, each page resp is 512b (64B)
  bpss_wr_req.vaddr := wrAddr.resized
  bpss_wr_req.len := wrReqBatchSize
  bpss_wr_req.stream := False
  bpss_wr_req.sync := False
  bpss_wr_req.ctl := True
  bpss_wr_req.host := True
  bpss_wr_req.dest := 0
  bpss_wr_req.pid := io.pid
  bpss_wr_req.vfid := 0
  bpss_wr_req.rsrvd := 0
  io.hostd.bpss_wr_req.data.assignFromBits(bpss_wr_req.asBits)
  // Batch size in word(64B)
  val cntWordToSend = AccumIncDec(16 bits, io.hostd.bpss_wr_req.fire, io.hostd.axis_host_src.fire, wrReqBatchSize / 64, 1)
  io.hostd.bpss_wr_req.valid := (pgRespQWide.io.occupancy - cntWordToSend.accum).asSInt >= (wrReqBatchSize / 64)
  when(io.hostd.bpss_wr_req.fire) {
    wrHostReq := wrHostReq + 1
    wrAddr := wrAddr + wrReqBatchSize
  }

  io.hostd.bpss_wr_done.freeRun()
  when(io.hostd.bpss_wr_done.fire)(io.wrDone := io.wrDone + 1)

  val cntAxiSrcLast = Counter(wrReqBatchSize/64, io.hostd.axis_host_src.fire)
  io.hostd.axis_host_src.translateFrom(pgRespQWide.io.pop)((a,b) => {
    a.tdata := b
    a.tkeep := B(64 bits, default -> True)
    a.tlast := cntAxiSrcLast.willOverflowIfInc
  })

}