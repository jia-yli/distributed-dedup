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
  // ##################################################
  //
  // part 0: routing table content and initialization logic
  //
  // ##################################################
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
  // ##################################################
  //
  // part 1: route instr
  //
  // ##################################################
  // stage 1: get all needed distance step 1
  val nodeDistStrmStage1 = Stream(
    new Bundle{
      val instr = RoutedLookupInstr(conf) 
      val insideToStart  = Vec(UInt(conf.rtConf.routingDecisionBits bits), conf.rtConf.routingChannelCount)
      val insideToEnd    = Vec(UInt(conf.rtConf.routingDecisionBits bits), conf.rtConf.routingChannelCount)
      val outsideToStart = Vec(UInt(conf.rtConf.routingDecisionBits bits), conf.rtConf.routingChannelCount)
      val outsideToEnd   = Vec(UInt(conf.rtConf.routingDecisionBits bits), conf.rtConf.routingChannelCount)
    }
  )
  nodeDistStrmStage1.translateFrom(io.instrIn.pipelined(StreamPipe.FULL)){(nodeDistStage1, inputInstr) =>
    nodeDistStage1.instr := inputInstr
    val routingHashValue = UInt(conf.rtConf.routingDecisionBits bits)
    routingHashValue.assignFromBits(inputInstr.SHA3Hash(conf.htConf.idxBucketWidth + conf.rtConf.routingDecisionBits - 1 downto conf.htConf.idxBucketWidth))
    for (index <- 0 until conf.rtConf.routingChannelCount){
      // option 1: inside
      nodeDistStage1.insideToStart(index) := routingHashValue - hashValueStartTable(index)
      nodeDistStage1.insideToEnd(index)   := hashValueEndTable(index) - routingHashValue
      // option 2: nearest
      nodeDistStage1.outsideToStart(index) := hashValueStartTable(index) - routingHashValue
      nodeDistStage1.outsideToEnd(index)   := routingHashValue - hashValueEndTable(index)
    }
  }
  // stage 2: get all needed distance step 2
  val nodeDistStrmStage2 = Stream(
    new Bundle{
      val instr = RoutedLookupInstr(conf) 
      val isInsideVec = Vec(new Bundle{val isInside = Bool()
                                       val channelIdx = UInt(log2Up(conf.rtConf.routingChannelCount) bits)}, conf.rtConf.routingChannelCount)
      val outsideDistVec = Vec(new Bundle{val dist = UInt(conf.rtConf.routingDecisionBits bits)
                                          val channelIdx = UInt(log2Up(conf.rtConf.routingChannelCount) bits)}, conf.rtConf.routingChannelCount)
    }
  )
  nodeDistStrmStage2.translateFrom(nodeDistStrmStage1.pipelined(StreamPipe.FULL)){(nodeDistStage2, nodeDistStage1) =>
    nodeDistStage2.instr := nodeDistStage1.instr
    // option 1: inside
    nodeDistStage2.isInsideVec.zipWithIndex.foreach{ case (element, index) =>
      val insideToStart = nodeDistStage1.insideToStart(index)
      val insideToEnd = nodeDistStage1.insideToEnd(index)
      val insideDistSum = insideToStart +^ insideToEnd
      when(hashValueLenTable(index).msb){
        // one entry for all
        element.isInside := True
      } otherwise{
        // otherwise
        element.isInside := (insideDistSum === hashValueLenTable(index)) && (!(insideToEnd === U(0)))
      }
      element.channelIdx := U(index)
    }
    // option 2: nearest
    nodeDistStage2.outsideDistVec.zipWithIndex.foreach{ case (element, index) =>
      val outsideToStart = nodeDistStage1.outsideToStart(index)
      val outsideToEnd = nodeDistStage1.outsideToEnd(index)
      // get min distance
      element.dist := (outsideToStart < outsideToEnd) ? outsideToStart | outsideToEnd
      element.channelIdx := U(index)
    }
  }
  // stage 3: get result from reduced balanced tree
  val routedInstrStrm = Stream(new Bundle{val instr = RoutedLookupInstr(conf) 
                                          val routingChannelIdx = UInt(log2Up(conf.rtConf.routingChannelCount) bits)})
  routedInstrStrm.translateFrom(nodeDistStrmStage2.pipelined(StreamPipe.FULL)){(routedInstr, nodeDist) =>
    // routing decision, log2Up(conf.rtConf.routingChannelCount) bits
    val routingChannelIdx = U(0, conf.rtConf.routingChannelLogCount bits)
    // option 1: inside
    // find if exist one and get index
    val reducedIsInside = nodeDist.isInsideVec.reduceBalancedTree{ (input1, input2) =>
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
    val reducedOutsideDist = nodeDist.outsideDistVec.reduceBalancedTree{ (input1, input2) =>
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
    routedInstr.instr := nodeDist.instr
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

  // ##################################################
  //
  // part 2: route write back res
  //
  // ##################################################
  // node index dist
  val nodeIdxDistStrmStage1 = Stream(
    new Bundle{
      val writeBackRes = RoutedWriteBackLookupRes(conf)
      val distCw  = Vec(UInt(conf.nodeIdxWidth bits), conf.rtConf.routingChannelCount)
      val distCcw = Vec(UInt(conf.nodeIdxWidth bits), conf.rtConf.routingChannelCount)
    }
  )
  nodeIdxDistStrmStage1.translateFrom(io.writeBackResIn.pipelined(StreamPipe.FULL)){(nodeIdxDistStage1, inputWriteBackRes) =>
    nodeIdxDistStrmStage1.writeBackRes := inputWriteBackRes
    val dstNodeIdx = inputWriteBackRes.dstNode
    for (index <- 0 until conf.rtConf.routingChannelCount){
      nodeIdxDistStage1.distCw(index) := dstNodeIdx - nodeIdxTable(index)
      nodeIdxDistStage1.distCcw(index) := nodeIdxTable(index) - dstNodeIdx
    }
  }
  val nodeIdxDistStrmStage2 = Stream(
    new Bundle{
      val writeBackRes = RoutedWriteBackLookupRes(conf)
      val distVec = Vec(new Bundle{val dist = UInt(conf.nodeIdxWidth bits)
                                   val channelIdx = UInt(log2Up(conf.rtConf.routingChannelCount) bits)}, conf.rtConf.routingChannelCount)
    }
  )
  nodeIdxDistStrmStage2.translateFrom(nodeIdxDistStrmStage1.pipelined(StreamPipe.FULL)){(nodeIdxDistStage2, nodeIdxDistStage1) =>
    nodeIdxDistStage2.writeBackRes := nodeIdxDistStage1.writeBackRes
    nodeIdxDistStage2.distVec.zipWithIndex.foreach{ case (element, index) =>
      val distCw = nodeIdxDistStage1.distCw(index)
      val distCcw = nodeIdxDistStage1.distCcw(index)
      // get min distance
      element.dist := (distCw < distCcw) ? distCw | distCcw
      element.channelIdx := U(index)
    }
  }
  val routedWriteBackResStrm = Stream(new Bundle{val lookupRes = RoutedWriteBackLookupRes(conf) 
                                                 val routingChannelIdx = UInt(log2Up(conf.rtConf.routingChannelCount) bits)})
  routedWriteBackResStrm.translateFrom(nodeIdxDistStrmStage2.pipelined(StreamPipe.FULL)){(routedWriteBackRes, nodeIdxDist) =>
    val reducedDist = nodeIdxDist.distVec.reduceBalancedTree{ (input1, input2) =>
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

    routedWriteBackRes.lookupRes := nodeIdxDist.writeBackRes
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