package dedup
package routingtable

import spinal.core._
import spinal.lib._
import registerfile.RegisteredLookupInstr

/**
  * Core of Routing Table
  * Dispatch to corresponding port
  *
  * @param conf
  */
case class RoutingTableCore(conf: DedupConfig) extends Component {
  val io = new Bundle {
    val instrIn        = slave Stream(RoutedLookupInstr(conf))
    val writeBackResIn = slave Stream(RoutedWriteBackLookupRes(conf))
    // routing decision
    val routedInstrOut = Vec(master Stream(RoutedLookupInstr(conf)), conf.rtConf.routingChannelCount)
    val routedWriteBackResOut = Vec(master Stream(RoutedWriteBackLookupRes(conf)), conf.rtConf.routingChannelCount)
  }
  // dummy fields for now
  val hashValueRangeTable = Vec(Reg(UInt(conf.nodeIdxWidth bits)), conf.rtConf.routingChannelCount)
  val nodeIdxTable = Vec(Reg(UInt(conf.nodeIdxWidth bits)), conf.rtConf.routingChannelCount)
  val activeChannelCount = 0

  // part 1: route instr
  val routedInstrStrm = Stream(new Bundle{val instr = RoutedLookupInstr(conf) 
                                          val routingChannelIdx = UInt(log2Up(conf.rtConf.routingChannelCount) bits)})
  routedInstrStrm.translateFrom(io.instrIn.pipelined(StreamPipe.FULL)){(routedInstr, inputInstr) =>
    routedInstr.instr := inputInstr
    val routingChannelIdx = 0 // dummy routing
    routedInstr.routingChannelIdx := routingChannelIdx
  }
  // output
  val demuxedRoutedInstrStrm = StreamDemux(routedInstrStrm.pipelined(StreamPipe.FULL), 
                                           routedInstrStrm.pipelined(StreamPipe.FULL).routingChannelIdx, 
                                           conf.rtConf.routingChannelCount)
  (demuxedRoutedInstrStrm, io.routedInstrOut).zipped.foreach{(a, b) =>
    b.translateFrom(a){_ := _.instr}
  }
  
  // part 2: route write back res
  val routedWriteBackResStrm = Stream(new Bundle{val lookupRes = RoutedWriteBackLookupRes(conf) 
                                                 val routingChannelIdx = UInt(log2Up(conf.rtConf.routingChannelCount) bits)})
  routedWriteBackResStrm.translateFrom(io.writeBackResIn.pipelined(StreamPipe.FULL)){(routedWriteBackRes, inputWriteBackRes) =>
    routedWriteBackRes.lookupRes := inputWriteBackRes
    val routingChannelIdx = 0 // dummy routing
    routedWriteBackRes.routingChannelIdx := routingChannelIdx
  }
  // output
  val demuxedRoutedWriteBackResStrm = StreamDemux(routedWriteBackResStrm.pipelined(StreamPipe.FULL), 
                                                  routedWriteBackResStrm.pipelined(StreamPipe.FULL).routingChannelIdx,
                                                  conf.rtConf.routingChannelCount)
  (demuxedRoutedWriteBackResStrm, io.routedWriteBackResOut).zipped.foreach{(a, b) =>
    b.translateFrom(a){_ := _.lookupRes}
  }
}