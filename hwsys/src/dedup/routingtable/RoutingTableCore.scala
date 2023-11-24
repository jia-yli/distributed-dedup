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
    // config port
    val updateRoutingTableContent = in Bool()
    val routingTableContent = new Bundle{
      val hashValueStart = in Vec(UInt(conf.rtConf.routingDecisionBits bits), conf.rtConf.routingChannelCount)
      val hashValueLen = in Vec(UInt((conf.rtConf.routingDecisionBits + 1) bits), conf.rtConf.routingChannelCount)
      val nodeIdx = in Vec(UInt(conf.nodeIdxWidth bits), conf.rtConf.routingChannelCount)
      val activeChannelCount = in UInt((conf.rtConf.routingChannelLogCount + 1) bits)
    }
  }
  // dummy fields for now
  val hashValueStartTable = Vec(Reg(UInt(conf.rtConf.routingDecisionBits bits)), conf.rtConf.routingChannelCount)
  val hashValueLenTable = Vec(Reg(UInt((conf.rtConf.routingDecisionBits + 1) bits)), conf.rtConf.routingChannelCount)
  val hashValueEndTable = Vec(Reg(UInt(conf.rtConf.routingDecisionBits bits)), conf.rtConf.routingChannelCount)
  val nodeIdxTable = Vec(Reg(UInt(conf.nodeIdxWidth bits)), conf.rtConf.routingChannelCount)
  val rActiveChannelCount = Reg(UInt((conf.rtConf.routingChannelLogCount + 1) bits))
  // default: use all channel, one channel to one node
  for (idx <- 0 until conf.rtConf.routingChannelCount){
    val idxHashStart = U(idx << (conf.rtConf.routingDecisionBits - conf.rtConf.routingChannelLogCount), conf.rtConf.routingDecisionBits bits)
    val idxHashLen = U(1 << (conf.rtConf.routingDecisionBits - conf.rtConf.routingChannelLogCount), (conf.rtConf.routingDecisionBits + 1) bits)
    hashValueStartTable(idx) init idxHashStart
    hashValueLenTable(idx) init idxHashLen
    hashValueEndTable(idx) init ((idxHashStart.resized + idxHashLen)((conf.rtConf.routingDecisionBits - 1) downto 0))
    nodeIdxTable(idx) init U(idx)
    rActiveChannelCount init U(conf.rtConf.routingChannelCount)
  }
  // update config
  when (io.updateRoutingTableContent) {
    (hashValueStartTable, io.routingTableContent.hashValueStart).zipped.foreach{_ := _}
    (hashValueLenTable, io.routingTableContent.hashValueLen).zipped.foreach{_ := _}
    hashValueEndTable.zipWithIndex.foreach{ case (element, index) =>
      element := (io.routingTableContent.hashValueStart(index).resized + io.routingTableContent.hashValueLen(index))((conf.rtConf.routingDecisionBits - 1) downto 0)
    }
    (nodeIdxTable, io.routingTableContent.nodeIdx).zipped.foreach{_ := _}
    rActiveChannelCount := io.routingTableContent.activeChannelCount
  }

  // part 1: route instr
  val routedInstrStrm = Stream(new Bundle{val instr = RoutedLookupInstr(conf) 
                                          val routingChannelIdx = UInt(log2Up(conf.rtConf.routingChannelCount) bits)})
  routedInstrStrm.translateFrom(io.instrIn.pipelined(StreamPipe.FULL)){(routedInstr, inputInstr) =>
    val routingHashValue = UInt(conf.rtConf.routingDecisionBits bits)
    routingHashValue.assignFromBits(inputInstr.SHA3Hash(conf.htConf.idxBucketWidth + conf.rtConf.routingDecisionBits - 1 downto conf.htConf.idxBucketWidth))
    // routing decision, log2Up(conf.rtConf.routingChannelCount) bits
    val routingChannelIdx = U(0)
    // option 1: inside
    val isInsideVec = Vec(new Bundle{val isInside = Bool()
                                     val channelIdx = UInt(log2Up(conf.rtConf.routingChannelCount) bits)}, conf.rtConf.routingChannelCount)
    isInsideVec.zipWithIndex.foreach{ case (element, index) =>
      val insideToStart = UInt(conf.rtConf.routingDecisionBits bits)
      insideToStart := routingHashValue - hashValueStartTable(index)
      val insideToEnd = UInt(conf.rtConf.routingDecisionBits bits)
      insideToEnd := hashValueEndTable(index) - routingHashValue
      val insideDistSum = insideToStart +^ insideToEnd
      element.isInside := (insideDistSum > hashValueLenTable(index)) && (!(insideToEnd === U(0)))
      element.channelIdx := U(index)
    }
    // find if exist one and get index
    val reducedIsInside = isInsideVec.reduceBalancedTree{ (input1, input2) =>
      val output = new Bundle{val isInside = Bool(); val channelIdx = UInt(log2Up(conf.rtConf.routingChannelCount) bits)}
      when(input2.isInside && (input2.channelIdx.resized < rActiveChannelCount)){
        // inside input2 and input2 is active
        output assignAllByName input2
      } otherwise {
        output assignAllByName input1
      }
      output
    }
    // option 2: nearest
    val outsideDistVec = Vec(new Bundle{val dist = UInt(conf.rtConf.routingDecisionBits bits)
                                        val channelIdx = UInt(log2Up(conf.rtConf.routingChannelCount) bits)}, conf.rtConf.routingChannelCount)
    outsideDistVec.zipWithIndex.foreach{ case (element, index) =>
      val outsideToStart = UInt(conf.rtConf.routingDecisionBits bits)
      outsideToStart := hashValueStartTable(index) - routingHashValue
      val outsideToEnd = UInt(conf.rtConf.routingDecisionBits bits)
      outsideToEnd := routingHashValue - hashValueEndTable(index)
      // get min distance
      element.dist := (outsideToStart < outsideToEnd) ? outsideToStart | outsideToEnd
      element.channelIdx := U(index)
    }
    val reducedOutsideDist = outsideDistVec.reduceBalancedTree{ (input1, input2) =>
      val output = new Bundle{val dist = UInt(conf.rtConf.routingDecisionBits bits)
                              val channelIdx = UInt(log2Up(conf.rtConf.routingChannelCount) bits)}
      when((input2.dist < input1.dist) && (input2.channelIdx.resized < rActiveChannelCount)){
        // closer to input2 and input2 is active
        output assignAllByName input2
      } otherwise {
        output assignAllByName input1
      }
      output
    }
    // select result
    when(reducedIsInside.isInside && (reducedIsInside.channelIdx.resized < rActiveChannelCount)){
      routingChannelIdx := reducedIsInside.channelIdx
    } otherwise {
      // use nearest node
      routingChannelIdx := reducedOutsideDist.channelIdx
    }
    routedInstr.instr := inputInstr
    routedInstr.routingChannelIdx := routingChannelIdx
  }
  // output
  val routedInstrStrmPipelined = routedInstrStrm.pipelined(StreamPipe.FULL)
  val demuxedRoutedInstrStrm = StreamDemux(routedInstrStrmPipelined, 
                                           routedInstrStrmPipelined.routingChannelIdx, 
                                           conf.rtConf.routingChannelCount)
  (demuxedRoutedInstrStrm, io.routedInstrOut).zipped.foreach{(a, b) =>
    b.translateFrom(a){_ := _.instr}
  }
  
  // part 2: route write back res
  val routedWriteBackResStrm = Stream(new Bundle{val lookupRes = RoutedWriteBackLookupRes(conf) 
                                                 val routingChannelIdx = UInt(log2Up(conf.rtConf.routingChannelCount) bits)})
  routedWriteBackResStrm.translateFrom(io.writeBackResIn.pipelined(StreamPipe.FULL)){(routedWriteBackRes, inputWriteBackRes) =>
    val dstNodeIdx = inputWriteBackRes.dstNode
    val distVec = Vec(new Bundle{val dist = UInt(conf.nodeIdxWidth bits)
                                 val channelIdx = UInt(log2Up(conf.rtConf.routingChannelCount) bits)}, conf.rtConf.routingChannelCount)
    distVec.zipWithIndex.foreach{ case (element, index) =>
      val distCw = UInt(conf.nodeIdxWidth bits)
      distCw := dstNodeIdx - nodeIdxTable(index)
      val distCcw = UInt(conf.nodeIdxWidth bits)
      distCcw := nodeIdxTable(index) - dstNodeIdx
      // get min distance
      element.dist := (distCw < distCcw) ? distCw | distCcw
      element.channelIdx := U(index)
    }
    val reducedDist = distVec.reduceBalancedTree{ (input1, input2) =>
      val output = new Bundle{val dist = UInt(conf.nodeIdxWidth bits)
                              val channelIdx = UInt(log2Up(conf.rtConf.routingChannelCount) bits)}
      when((input2.dist < input1.dist) && (input2.channelIdx.resized < rActiveChannelCount)){
        // closer to input2 and input2 is active
        output assignAllByName input2
      } otherwise {
        output assignAllByName input1
      }
      output
    }

    routedWriteBackRes.lookupRes := inputWriteBackRes
    routedWriteBackRes.routingChannelIdx := reducedDist.channelIdx
  }
  // output
  val routedWriteBackResStrmPipelined = routedWriteBackResStrm.pipelined(StreamPipe.FULL)
  val demuxedRoutedWriteBackResStrm = StreamDemux(routedWriteBackResStrmPipelined, 
                                                  routedWriteBackResStrmPipelined.routingChannelIdx,
                                                  conf.rtConf.routingChannelCount)
  (demuxedRoutedWriteBackResStrm, io.routedWriteBackResOut).zipped.foreach{(a, b) =>
    b.translateFrom(a){_ := _.lookupRes}
  }
}