package dedup

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import spinal.lib.bus.amba4.axi._
import coyote._
import util.BatchedStreamArbiterFactory
import util.AccumIncDec


class NetworkIntfIO(conf: DedupConfig, axiConf: Axi4Config) extends Bundle {
  // network IO
  val networkData = new NetworkDataIO()

  // config lower layer rounting info (e.g., nodeIdx -> rdma q pair number)
  val updateRoutingTableContent = in Bool()
  val rdmaQpnVec = in Vec(UInt(conf.rdmaQpnWidth bits), conf.rtConf.routingChannelCount - 1)

  // dedupcore IO
  val recvDataStrm = master Stream(Bits(512 bits))
  val sendDataStrmVec = Vec(slave Stream(Bits(512 bits)), conf.rtConf.routingChannelCount - 1)
}

class NetworkIntf(conf: DedupConfig) extends Component {

  val io = new NetworkIntfIO(conf, Axi4ConfigAlveo.u55cHBM)

  /** routing info */
  val rdmaQpnTable = Vec(Reg(UInt(conf.rdmaQpnWidth bits)) init 0, conf.rtConf.routingChannelCount - 1)
  // update config
  when (io.updateRoutingTableContent) {
    (rdmaQpnTable, io.rdmaQpnVec).zipped.foreach{_ := _}
  }
  /** rdma */
  io.networkData.rdma_0_ack   .freeRun()
  io.networkData.rdma_0_rd_req.freeRun()
  /** recv path */
  io.networkData.rdma_0_wr_req.freeRun()
  val networkRecvBuffer = StreamFifo(Bits(512 bits), 512)
  networkRecvBuffer.io.push.translateFrom(io.networkData.axis_rdma_0_sink)(_ := _.tdata)
  io.recvDataStrm << networkRecvBuffer.io.pop

  /** send path */
  val sendQDepth = 128
  val pipelinedSendDataStrmVec = Vec(Stream(Bits(512 bits)), conf.rtConf.routingChannelCount - 1)
  (pipelinedSendDataStrmVec, io.sendDataStrmVec).zipped.foreach(_ << _.pipelined(StreamPipe.FULL))
  val batchedStreamArbiterOutput = BatchedStreamArbiterFactory().roundRobin.fragmentLock.on(sendQDepth, pipelinedSendDataStrmVec)
  val batchedArbitratedSendStream = batchedStreamArbiterOutput.output.pipelined(StreamPipe.FULL)
  val routingChannelIdxOH = RegNext(batchedStreamArbiterOutput.chosenOH)

  val (batchedStreamArbiterOutputToReq, batchedStreamArbiterOutputToData) = StreamFork2(batchedArbitratedSendStream)
  // val batchCmdExec = RegSetUnset(io.networkData.rdma_0_sq.fire, io.networkData.axis_rdma_0_src.fire && io.networkData.axis_rdma_0_src.tlast)
  // io.networkData.rdma_0_sq.valid := (!batchCmdExec.value) && batchedArbitratedSendStream.valid
  // True if req fired and waiting for data last fire
  // val batchCmdExec = RegSetUnset(batchedStreamArbiterOutputToReq.fire, batchedStreamArbiterOutputToData.fire && batchedStreamArbiterOutputToData.payload.last)
  val reqFireCounter = Counter(sendQDepth)
  when(batchedStreamArbiterOutputToReq.fire){
    reqFireCounter.increment()
  }
  when(batchedStreamArbiterOutputToReq.fire && batchedStreamArbiterOutputToReq.payload.last){
    reqFireCounter.clear()
  }
  io.networkData.rdma_0_sq.translateFrom(batchedStreamArbiterOutputToReq.throwWhen(reqFireCounter =/= 0)){ (a, b) =>
    val sendBatchByteSize = b.fragment.wordLen << 6 // each word is 512b = 64B = 6 bit shift
    val rdma_0_sq = RdmaReqT()
    rdma_0_sq.opcode := 2 // rdma send
    rdma_0_sq.qpn    := MuxOH(routingChannelIdxOH, rdmaQpnTable)
    rdma_0_sq.host   := False
    rdma_0_sq.mode   := False
    rdma_0_sq.last   := True  // use last signal
    rdma_0_sq.cmplt  := False // no need to ack
    rdma_0_sq.ssn    := 0
    rdma_0_sq.offs   := 0
    rdma_0_sq.msg    := 0
    rdma_0_sq.msg(159 downto 128) := sendBatchByteSize.resized
    rdma_0_sq.rsrvd  := 0
    a.data.assignFromBits(rdma_0_sq.asBits)
  }
  
  io.networkData.axis_rdma_0_src.translateFrom(batchedStreamArbiterOutputToData.pipelined(StreamPipe.FULL))((a,b) => {
    a.tdata := b.fragment.payload
    a.tkeep := B(64 bits, default -> True)
    a.tid   := 0
    a.tlast := b.last
  })
}

case class RegSetUnset(setFlag: Bool, unsetFlag: Bool) {

  val value = Reg(Bool()) init False

  switch(setFlag ## unsetFlag) {
    is(True ## True) (value := value)
    is(True ## False) (value := True)
    is(False ## True) (value := False)
    is(False ## False) (value := value)
  }
}