package coyote

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axilite._

import util.Helpers._
import dedup.DedupConfig

// both BpssReq & RDMAReq
case class ReqT() extends Bundle {
  val rsrvd                   = UInt(96 - 48 - 28 - 4 - 4 - 6 - 1 bits) // 5b
  val vfid                    = UInt(1 bits)                            // only 1 vFPGA
  val pid                     = UInt(6 bits)
  val dest                    = UInt(4 bits)
  val host, ctl, sync, stream = Bool()
  val len                     = UInt(28 bits)
  val vaddr                   = UInt(48 bits)
}

case class BpssDone() extends Bundle {
  val pid = UInt(6 bits)
}

// cast struct to data stream
case class StreamData(width: Int) extends Bundle {
  val data = Bits(width bits)
}

case class BpssData(width: Int) extends Bundle {
  val tdata = Bits(width bits)
  val tkeep = Bits(width / 8 bits) // will be renamed in RenameIO
  val tlast = Bool()
}

case class Axi4StreamData(width: Int) extends Bundle {
  val tdata = Bits(width bits)
  val tkeep = Bits(width / 8 bits) // will be renamed in RenameIO
  val tlast = Bool()
}

// Bypass
class HostDataIO extends Bundle {
  // bpss h2c/c2h
  val bpss_rd_req    = master Stream StreamData(96)
  val bpss_wr_req    = master Stream StreamData(96)
  val bpss_rd_done   = slave Stream StreamData(6)
  val bpss_wr_done   = slave Stream StreamData(6)
  val axis_host_sink = slave Stream BpssData(512)
  val axis_host_src  = master Stream BpssData(512)

  // def tieOff(): Unit = {
  //   bpss_rd_req.setIdle()
  //   bpss_wr_req.setIdle()
  //   bpss_rd_done.setBlocked()
  //   bpss_wr_done.setBlocked()
  //   axis_host_sink.setBlocked()
  //   axis_host_src.setIdle()
  // }
}

class NetworkDataIO extends Bundle {
  val remoteRecvStrmIn  = slave Stream(Bits(512 bits))
  val remoteSendStrmOut = master Stream(Bits(512 bits))
}

// class CMemHostIO(cmemAxiConf: Axi4Config) extends Bundle {
//   // ctrl
//   val mode     = in UInt (2 bits)
//   val hostAddr = in UInt (64 bits)
//   val cmemAddr = in UInt (64 bits)
//   val len      = in UInt (16 bits)
//   val cnt      = in UInt (64 bits)
//   val pid      = in UInt (6 bits)
//   val cntDone  = out(Reg(UInt(64 bits))).init(0)

//   // host data IO
//   val hostd = new HostDataIO

//   // cmem interface
//   val axi_cmem = master(Axi4(cmemAxiConf))

//   def regMap(r: AxiLite4SlaveFactory, baseR: Int): Int = {
//     implicit val baseReg = baseR
//     val rMode            = r.rwInPort(mode, r.getAddr(0), 0, "CMemHost: mode")
//     when(rMode.orR)(rMode.clearAll()) // auto clear
//     r.rwInPort(hostAddr, r.getAddr(1), 0, "CMemHost: hostAddr")
//     r.rwInPort(cmemAddr, r.getAddr(2), 0, "CMemHost: cmemAddr")
//     r.rwInPort(len, r.getAddr(3), 0, "CMemHost: len")
//     r.rwInPort(cnt, r.getAddr(4), 0, "CMemHost: cnt")
//     r.rwInPort(pid, r.getAddr(5), 0, "CMemHost: pid")
//     r.read(cntDone, r.getAddr(6), 0, "CMemHost: cntDone")
//     val assignOffs = 7
//     assignOffs
//   }
// }
