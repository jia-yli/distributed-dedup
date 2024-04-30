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
      val activeChannelCount = in UInt(log2Up(conf.rtConf.routingChannelCount + 1) bits)
      val routingMode = in Bool()
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
  val rActiveChannelCount = Reg(UInt(log2Up(conf.rtConf.routingChannelCount + 1) bits))
  val rRoutingMode = Reg(Bool())
  // update config
  when (io.updateRoutingTableContent) {
    (hashValueStartTable, io.routingTableContent.hashValueStart).zipped.foreach{_ := _}
    (hashValueLenTable, io.routingTableContent.hashValueLen).zipped.foreach{_ := _}
    hashValueEndTable.zipWithIndex.foreach{ case (element, index) =>
      element := (io.routingTableContent.hashValueStart(index).resized + io.routingTableContent.hashValueLen(index))((conf.rtConf.routingDecisionBits - 1) downto 0)
    }
    (nodeIdxTable, io.routingTableContent.nodeIdx).zipped.foreach{_ := _}
    rActiveChannelCount := io.routingTableContent.activeChannelCount
    rRoutingMode := io.routingTableContent.routingMode
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
      val isSmaller   = Vec(Bool(), conf.rtConf.routingChannelCount)
      // val insideToLocal  = Vec(UInt(conf.rtConf.routingDecisionBits bits), conf.rtConf.routingChannelCount)
      // val insideToTarget = Vec(UInt(conf.rtConf.routingDecisionBits bits), conf.rtConf.routingChannelCount)
      // val rangeLen       = UInt((conf.rtConf.routingDecisionBits + 1) bits)
    }
  )
  nodeDistStrmStage1.translateFrom(io.instrIn.pipelined(StreamPipe.FULL)){(nodeDistStage1, inputInstr) =>
    nodeDistStage1.instr := inputInstr
    val routingHashValue = UInt(conf.rtConf.routingDecisionBits bits)
    routingHashValue.assignFromBits(inputInstr.SHA3Hash(conf.htConf.idxBucketWidth + conf.rtConf.routingDecisionBits - 1 downto conf.htConf.idxBucketWidth))
    for (index <- 0 until conf.rtConf.routingChannelCount){
      // option 1: inside
      nodeDistStage1.insideToStart(index)  := routingHashValue - hashValueStartTable(index)
      nodeDistStage1.insideToEnd(index)    := hashValueEndTable(index) - routingHashValue
      // option 2: nearest
      nodeDistStage1.outsideToStart(index) := hashValueStartTable(index) - routingHashValue
      nodeDistStage1.outsideToEnd(index)   := routingHashValue - hashValueEndTable(index)
      // option 3: chord
      val chordStartVal = hashValueStartTable(0)
      val chordEndVal = routingHashValue
      when (chordStartVal <= chordEndVal) {
        nodeDistStage1.isSmaller(index) := ((hashValueStartTable(index) >= chordStartVal) && (hashValueStartTable(index) <= chordEndVal))
      } otherwise {
        nodeDistStage1.isSmaller(index) := ((hashValueStartTable(index) >= chordStartVal) || (hashValueStartTable(index) <= chordEndVal))
      }
      // nodeDistStage1.insideToLocal(index)  := hashValueEndTable(index) - hashValueStartTable(0)
      // nodeDistStage1.insideToTarget(index) := routingHashValue - hashValueEndTable(index)
      // when(routingHashValue === hashValueStartTable(0)){
      //   when(hashValueLenTable(0) === U(0)){
      //     nodeDistStage1.rangeLen := U(1) << conf.rtConf.routingDecisionBits
      //   } otherwise{
      //     nodeDistStage1.rangeLen := U(0)
      //   }
      // } otherwise {
      //   nodeDistStage1.rangeLen := (routingHashValue - hashValueStartTable(0)).resized
      // }
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
      val isSmallerVec = Vec(new Bundle{val isSmaller = Bool()
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
    // option 3: chord
    nodeDistStage2.isSmallerVec.zipWithIndex.foreach{ case (element, index) =>
      element.isSmaller  := nodeDistStage1.isSmaller(index) && (U(index) < rActiveChannelCount)
      element.channelIdx := U(index)
      // val insideToStart = nodeDistStage1.insideToLocal(index)
      // val insideToEnd = nodeDistStage1.insideToTarget(index)
      // val insideDistSum = insideToStart +^ insideToEnd
      // element.isSmaller := (insideDistSum === hashValueLenTable(index)) && (U(index) < rActiveChannelCount)
      // element.channelIdx := U(index)
    }
  }
  // stage 3: get result from reduced balanced tree
  val routedInstrStrm = Stream(new Bundle{val instr = RoutedLookupInstr(conf) 
                                          val routingChannelIdx = UInt(log2Up(conf.rtConf.routingChannelCount) bits)})
  routedInstrStrm.translateFrom(nodeDistStrmStage2.pipelined(StreamPipe.FULL)){(routedInstr, nodeDist) =>
    // routing decision, log2Up(conf.rtConf.routingChannelCount) bits
    val routingChannelIdx = U(0, log2Up(conf.rtConf.routingChannelCount) bits)
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
    // option 3: chord
    val (chordHitValid, chordHitValue) = nodeDist.isSmallerVec.sFindFirst(a => (!a.isSmaller)) // find first false
    // select result
    when(reducedIsInside.isInside){
      routingChannelIdx := reducedIsInside.channelIdx
    } elsewhen(rRoutingMode === False) {
      // use nearest node
      routingChannelIdx := reducedOutsideDist.channelIdx
    } otherwise {
      // chord routing
      when(!chordHitValid){
        routingChannelIdx := nodeDist.isSmallerVec(chordHitValue).channelIdx
      } elsewhen(chordHitValue =/= 0) {
        routingChannelIdx := nodeDist.isSmallerVec(chordHitValue - 1).channelIdx
      } otherwise {
        routingChannelIdx := nodeDist.isSmallerVec(0).channelIdx
      }
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
      val isSmaller = Vec(Bool(), conf.rtConf.routingChannelCount)
    }
  )
  nodeIdxDistStrmStage1.translateFrom(io.writeBackResIn.pipelined(StreamPipe.FULL)){(nodeIdxDistStage1, inputWriteBackRes) =>
    nodeIdxDistStrmStage1.writeBackRes := inputWriteBackRes
    val dstNodeIdx = inputWriteBackRes.dstNode
    for (index <- 0 until conf.rtConf.routingChannelCount){
      nodeIdxDistStage1.distCw(index) := dstNodeIdx - nodeIdxTable(index)
      nodeIdxDistStage1.distCcw(index) := nodeIdxTable(index) - dstNodeIdx
    }
    // chord
    for (index <- 0 until conf.rtConf.routingChannelCount){
      val chordStartNodeIdx = nodeIdxTable(0)
      val chordEndNodeIdx = dstNodeIdx
      when (chordStartNodeIdx <= chordEndNodeIdx) {
        nodeIdxDistStage1.isSmaller(index) := ((nodeIdxTable(index) >= chordStartNodeIdx) && (nodeIdxTable(index) <= chordEndNodeIdx))
      } otherwise {
        nodeIdxDistStage1.isSmaller(index) := ((nodeIdxTable(index) >= chordStartNodeIdx) || (nodeIdxTable(index) <= chordEndNodeIdx))
      }
      // when (dstNodeIdx > nodeIdxTable(0)) {
      //   val comp1 = nodeIdxTable(index) >= nodeIdxTable(0)
      //   val comp2 = dstNodeIdx > nodeIdxTable(index)
      //   nodeIdxDistStage1.isSmaller(index) := comp1 && comp2
      // } otherwise {
      //   val comp1 = (nodeIdxTable(index) >= nodeIdxTable(0))
      //   val comp2 = (nodeIdxTable(index) >= U(0)) && (dstNodeIdx > nodeIdxTable(index))
      //   nodeIdxDistStage1.isSmaller(index) := comp1 || comp2
      // }
    }

  }
  val nodeIdxDistStrmStage2 = Stream(
    new Bundle{
      val writeBackRes = RoutedWriteBackLookupRes(conf)
      val distVec = Vec(new Bundle{val dist = UInt(conf.nodeIdxWidth bits)
                                   val channelIdx = UInt(log2Up(conf.rtConf.routingChannelCount) bits)}, conf.rtConf.routingChannelCount)
      val isSmallerVec = Vec(new Bundle{val isSmaller = Bool()
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
    // chord
    nodeIdxDistStage2.isSmallerVec.zipWithIndex.foreach{ case (element, index) =>
      element.isSmaller := nodeIdxDistStage1.isSmaller(index) && (U(index) < rActiveChannelCount)
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
    // option 3: chord
    val (chordHitValid, chordHitValue) = nodeIdxDist.isSmallerVec.sFindFirst(a => (!a.isSmaller)) // find first false
    // select result
    when(reducedDist.dist === 0){
      routedWriteBackRes.routingChannelIdx := reducedDist.channelIdx
    } elsewhen(rRoutingMode === False) {
      // use nearest node
      routedWriteBackRes.routingChannelIdx := reducedDist.channelIdx
    } otherwise {
      // chord routing
      when(!chordHitValid){
        routedWriteBackRes.routingChannelIdx := nodeIdxDist.isSmallerVec(chordHitValue).channelIdx
      } elsewhen(chordHitValue =/= 0) {
        routedWriteBackRes.routingChannelIdx := nodeIdxDist.isSmallerVec(chordHitValue - 1).channelIdx
      } otherwise {
        routedWriteBackRes.routingChannelIdx := nodeIdxDist.isSmallerVec(0).channelIdx
      }
    }

    routedWriteBackRes.lookupRes := nodeIdxDist.writeBackRes
    // routedWriteBackRes.routingChannelIdx := reducedDist.channelIdx
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