package dedup

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import spinal.lib.bus.amba4.axi._
import coyote._


class NetworkIntfIO(conf: DedupConfig, axiConf: Axi4Config) extends Bundle {
  // network IO
  val networkData = new NetworkDataIO()

  // dedupcore intf
  val recvDataStrm = master Stream(Bits(512 bits))
  val sendDataStrmVec = Vec(slave Stream(Bits(512 bits)), conf.rtConf.routingChannelCount - 1)
}

class NetworkIntf(conf: DedupConfig) extends Component {

  val io = new NetworkIntfIO(conf, Axi4ConfigAlveo.u55cHBM)

  io.recvDataStrm << io.networkData.remoteRecvStrmIn
  io.networkData.remoteSendStrmOut << StreamArbiterFactory.roundRobin.transactionLock.on(io.sendDataStrmVec)
}