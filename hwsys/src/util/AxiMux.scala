package util

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import dedup.Axi4ConfigAlveo


case class AxiMuxIO(inputPortCount: Int, axiConf: Axi4Config = Axi4ConfigAlveo.u55cHBM) extends Bundle {
  /** axi in */ 
  val axiIn  = Vec(slave(Axi4(axiConf)), inputPortCount)
  /** axi out */
  val axiOut = master(Axi4(axiConf))
}

case class AxiMux(inputPortCount: Int, axiConf: Axi4Config = Axi4ConfigAlveo.u55cHBM) extends Component {
  /* This is component is used to arbitrate different channels to 1 channel
    Since the memory model in simulation only has 1 channel(1x axi interface)
  */

  val io = AxiMuxIO(inputPortCount, axiConf)

  // readCmd
  io.axiOut.ar << StreamArbiterFactory.roundRobin.transactionLock.on(Array.tabulate(inputPortCount)(idx => io.axiIn(idx).ar))
  
  // writeCmd and writeData, must keep same relative order in the output side
  val arbitratedWriteCmdStrm = StreamArbiterFactory.roundRobin.transactionLock.on(Array.tabulate(inputPortCount)(idx => io.axiIn(idx).aw))

  val (writeCmdToAxi, writeCmdToSelect) = StreamFork2(arbitratedWriteCmdStrm)
  io.axiOut.aw << writeCmdToAxi

  val writeDataArbitrationStrm = writeCmdToSelect.queue(2)
  writeDataArbitrationStrm.ready :=  io.axiOut.w.fire & io.axiOut.w.payload.last
  // arbitrate w(writeData) using aw(writeCmd), they need to be in same order
  io.axiOut.w.setIdle()
  for (idx <- 0 until inputPortCount){
    when(writeDataArbitrationStrm.valid & (writeDataArbitrationStrm.payload.id === idx)){
      io.axiOut.w << io.axiIn(idx).w
    } otherwise {
      io.axiIn(idx).w.setBlocked()
    }
  }
  
  // back, dispatch based on id
  val axiReadRspDispatcher = StreamDemux(io.axiOut.r, io.axiOut.r.payload.id.resized, inputPortCount)
  val axiWriteRspDispatcher = StreamDemux(io.axiOut.b, io.axiOut.b.payload.id.resized, inputPortCount)
  for (FSMIdx <- 0 until inputPortCount){
    axiWriteRspDispatcher(FSMIdx) >> io.axiIn(FSMIdx).b
    axiReadRspDispatcher(FSMIdx)  >> io.axiIn(FSMIdx).r
  }
}