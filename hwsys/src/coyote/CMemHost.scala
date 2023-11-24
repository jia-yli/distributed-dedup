// package coyote

// import spinal.core._
// import spinal.lib.fsm._
// import spinal.lib.bus.amba4.axi._
// import spinal.lib.fsm.StateMachine

// //
// class CMemHost(cmemAxiConf: Axi4Config) extends Component {

//   val io = new CMemHostIO(cmemAxiConf)

//   val cntWrData, cntRdData, cntHostReq, cntHbmReq = Reg(UInt(64 bits)).init(0)
//   val cmemRdAddr, cmemWrAddr, reqHostAddr         = Reg(UInt(64 bits)).init(0)

//   // req assignment
//   val bpss_rd_req, bpss_wr_req = ReqT()
//   List(bpss_rd_req, bpss_wr_req).foreach { e =>
//     e.vaddr  := reqHostAddr.resized
//     e.len    := io.len.resized
//     e.stream := False
//     e.sync   := False
//     e.ctl    := True
//     e.host   := True
//     e.dest   := 0
//     e.pid    := io.pid
//     e.vfid   := 0
//     e.rsrvd  := 0
//   }
//   io.hostd.bpss_rd_req.data.assignFromBits(bpss_rd_req.asBits)
//   io.hostd.bpss_wr_req.data.assignFromBits(bpss_wr_req.asBits)

//   val fsm = new StateMachine {
//     val IDLE   = new State with EntryPoint
//     val RD, WR = new State

//     IDLE.whenIsActive {
//       when(io.mode(0))(goto(RD))
//       when(io.mode(1))(goto(WR))
//       // cleanup the status
//       when(io.mode(0) || io.mode(1)) {
//         reqHostAddr := io.hostAddr
//         cmemRdAddr  := io.cmemAddr
//         cmemWrAddr  := io.cmemAddr
//         List(cntRdData, cntWrData, cntHostReq, cntHbmReq, io.cntDone).foreach(_.clearAll())
//       }
//     }

//     // rd from host
//     RD.whenIsActive {
//       when(io.cntDone === io.cnt)(goto(IDLE))
//     }

//     // wr to host
//     WR.whenIsActive {
//       when(io.cntDone === io.cnt)(goto(IDLE))
//     }
//   }

//   // deft behavior of rd from host
//   // axi_cmem wr
//   io.axi_cmem.aw.setBurstINCR()
//   io.axi_cmem.aw.len  := ((io.len >> 6) - 1).resized
//   io.axi_cmem.aw.size := log2Up(512 / 8)
//   io.axi_cmem.aw.addr := cmemWrAddr.resized
//   io.axi_cmem.aw.id   := 0

//   io.axi_cmem.w.strb.setAll()
//   io.axi_cmem.w.data <> io.hostd.axis_host_sink.tdata
//   io.axi_cmem.w.last <> io.hostd.axis_host_sink.tlast
//   io.axi_cmem.w.valid <> io.hostd.axis_host_sink.valid
//   io.axi_cmem.w.ready <> io.hostd.axis_host_sink.ready

//   io.axi_cmem.b.ready := True

//   // axi_cmem rd
//   io.axi_cmem.ar.setBurstINCR()
//   io.axi_cmem.ar.len  := ((io.len >> 6) - 1).resized
//   io.axi_cmem.ar.size := log2Up(512 / 8)
//   io.axi_cmem.ar.addr := cmemRdAddr.resized
//   io.axi_cmem.ar.id   := 0

//   io.axi_cmem.r.data <> io.hostd.axis_host_src.tdata
//   io.axi_cmem.r.valid <> io.hostd.axis_host_src.valid
//   io.axi_cmem.r.ready <> io.hostd.axis_host_src.ready

//   io.hostd.axis_host_src.tlast := False
//   io.hostd.axis_host_src.tkeep.setAll()

//   // inc cnt
//   when(io.axi_cmem.aw.fire || io.axi_cmem.ar.fire)(cntHbmReq := cntHbmReq + 1)
//   when(io.axi_cmem.aw.fire)(cmemWrAddr                       := cmemWrAddr + io.len)
//   when(io.axi_cmem.ar.fire)(cmemRdAddr                       := cmemRdAddr + io.len)

//   when(io.hostd.bpss_rd_req.fire || io.hostd.bpss_wr_req.fire) {
//     cntHostReq  := cntHostReq + 1
//     reqHostAddr := reqHostAddr + io.len
//   }

//   val cmemToReq = cntHbmReq =/= io.cnt
//   val hostToReq = cntHostReq =/= io.cnt

//   io.axi_cmem.aw.valid := cmemToReq && fsm.isActive(fsm.RD)
//   io.axi_cmem.ar.valid := cmemToReq && fsm.isActive(fsm.WR)

//   io.hostd.bpss_rd_req.valid := hostToReq && fsm.isActive(fsm.RD)
//   io.hostd.bpss_wr_req.valid := hostToReq && fsm.isActive(fsm.WR)

//   when(io.hostd.bpss_rd_done.fire || io.hostd.bpss_wr_done.fire)(io.cntDone := io.cntDone + 1)
//   when(io.hostd.axis_host_sink.fire)(cntRdData                              := cntRdData + 1)
//   when(io.hostd.axis_host_src.fire)(cntWrData                               := cntWrData + 1)

//   io.hostd.bpss_rd_done.ready := True
//   io.hostd.bpss_wr_done.ready := True

// }
