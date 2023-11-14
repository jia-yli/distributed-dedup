package dedup
package routingtable

import spinal.core._
import spinal.lib._
import registerfile.RegisteredLookupInstr
import registerfile.WriteBackLookupRes

case class RoutedLookupInstr(conf: DedupConfig) extends Bundle{
  val SHA3Hash = Bits(conf.htConf.hashValWidth bits)
  val tag      = UInt(conf.rfConf.tagWidth bits)
  val opCode   = DedupCoreOp()
  val srcNode  = UInt(conf.nodeIdxWidth bits)
}

case class RoutedWriteBackLookupRes (conf: DedupConfig) extends Bundle {
  val RefCount = UInt(conf.htConf.ptrWidth bits)
  val SSDLBA   = UInt(conf.htConf.ptrWidth bits)
  val nodeIdx  = UInt(conf.nodeIdxWidth bits)
  val tag      = UInt(conf.rfConf.tagWidth bits)
  val dstNode  = UInt(conf.nodeIdxWidth bits)
}

case class PaddedRoutedLookupInstr(conf: DedupConfig) extends Bundle{
  val payload      = RoutedLookupInstr(conf)
  val paddingWidth = conf.networkWordSizeBit - payload.getBitsWidth - 1
  val padding      = UInt(paddingWidth bits)
  val isInstr      = Bool() // True
}

case class PaddedRoutedWriteBackLookupRes (conf: DedupConfig) extends Bundle {
  val payload      = RoutedWriteBackLookupRes(conf)
  val paddingWidth = conf.networkWordSizeBit - payload.getBitsWidth - 1
  val padding      = UInt(paddingWidth bits)
  val isInstr      = Bool() // False
}

case class RoutingTableConfig(routingChannelCount : Int = 1){
  assert(routingChannelCount > 0, "must have at least 1 port for local lookup")
}

case class RoutingTableTop(conf: DedupConfig) extends Component {
  val io = new Bundle {
    // input: instr, res from local + remote raw input
    val localInstrIn         = slave Stream(RegisteredLookupInstr(conf))
    val localWriteBackResIn  = slave Stream(RoutedWriteBackLookupRes(conf))
    val remoteRecvStrmIn     = slave Stream(Bits(conf.networkWordSizeBit bits))
    // output: instr, res to local + remote raw output
    val localInstrOut        = master Stream(RoutedLookupInstr(conf))
    val localWriteBackResOut = master Stream(WriteBackLookupRes(conf))
    val remoteSendStrmOut    = Vec(master Stream(Bits(conf.networkWordSizeBit bits)), conf.rtConf.routingChannelCount - 1)
    // info for routing
    // init + nodeIdx + routing table info
  }
  val rNodeIdx = Reg(UInt(conf.nodeIdxWidth bits)) init 0
  val rtCore = RoutingTableCore(conf)
  // rtCore.io.instrIn        << preprocessingLogic.instrStrm
  // rtCore.io.writeBackResIn << preprocessingLogic.writeBackResStrm
  // rtCore.io.routedInstrOut = Vec(master Stream(RoutedLookupInstr(conf)), conf.rtConf.routingChannelCount)
  // rtCore.io.routedWriteBackResOut = Vec(master Stream(RoutedWriteBackLookupRes(conf)), conf.rtConf.routingChannelCount)

  val preprocessingLogic = new Area{
    // recv stream decode
    val (recvStrmInstr, recvStrmLookupRes) = StreamFork2(io.remoteRecvStrmIn)
    // decoded instr stream
    val instrDecodedRecvStrm = Stream(PaddedRoutedLookupInstr(conf))
    instrDecodedRecvStrm.translateFrom(recvStrmInstr){_.assignFromBits(_)}
    val remoteInstrStrm = Stream(RoutedLookupInstr(conf))
    remoteInstrStrm.translateFrom(instrDecodedRecvStrm.throwWhen(!instrDecodedRecvStrm.payload.isInstr)){_ := _.payload}
    // decoded lookup res
    val writeBackResDecodedRecvStrm = Stream(PaddedRoutedWriteBackLookupRes(conf))
    writeBackResDecodedRecvStrm.translateFrom(recvStrmLookupRes){_.assignFromBits(_)}
    val remoteWriteBackResStrm = Stream(RoutedWriteBackLookupRes(conf))
    remoteWriteBackResStrm.translateFrom(writeBackResDecodedRecvStrm.throwWhen(writeBackResDecodedRecvStrm.payload.isInstr)){_ := _.payload}

    val localInstrStrm = Stream(RoutedLookupInstr(conf))
    localInstrStrm.translateFrom(io.localInstrIn){(routedInstr, registeredInstr) =>
      routedInstr assignSomeByName registeredInstr
      routedInstr.srcNode  := rNodeIdx
    }
    val localWriteBackResStrm = Stream(RoutedWriteBackLookupRes(conf))
    localWriteBackResStrm << io.localWriteBackResIn

    // merge local and remote streams
    rtCore.io.instrIn        << StreamArbiterFactory.roundRobin.transactionLock.onArgs(localInstrStrm, remoteInstrStrm)
    rtCore.io.writeBackResIn << StreamArbiterFactory.roundRobin.transactionLock.onArgs(localWriteBackResStrm, remoteWriteBackResStrm)
  }

  val postprocessingLogic = new Area{
    // local instr stream
    rtCore.io.routedInstrOut(0) >> io.localInstrOut
    val instrDecodedRecvStrm = Stream(PaddedRoutedLookupInstr(conf))
    // local res write back
    io.localWriteBackResOut assignAllByName rtCore.io.routedWriteBackResOut(0)
    // remote: merge req and res to same dst
    io.remoteSendStrmOut.zipWithIndex.foreach{ case (remoteSendStrm, idx) =>
      val routingChannelIdx = idx + 1
      val encodedInstrStrm = Stream(Bits(conf.networkWordSizeBit bits))
      encodedInstrStrm.translateFrom(rtCore.io.routedInstrOut(routingChannelIdx)){(encodedInstr, inputInstr) =>
        val paddedInstr = PaddedRoutedLookupInstr(conf)
        paddedInstr.payload := inputInstr
        paddedInstr.padding := 0
        paddedInstr.isInstr := True
        encodedInstr        := paddedInstr.asBits
      }
      val encodedWriteBackResStrm = Stream(Bits(conf.networkWordSizeBit bits))
      encodedWriteBackResStrm.translateFrom(rtCore.io.routedWriteBackResOut(routingChannelIdx)){(encodedRes, inputRes) =>
        val paddedRes = PaddedRoutedWriteBackLookupRes(conf)
        paddedRes.payload := inputRes
        paddedRes.padding := 0
        paddedRes.isInstr := False
        encodedRes        := paddedRes.asBits
      }
      remoteSendStrm << StreamArbiterFactory.roundRobin.transactionLock.onArgs(encodedInstrStrm, encodedWriteBackResStrm)
    }
  }
}