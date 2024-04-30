package dedup
package routingtable

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import util.sim._
import util.sim.SimDriver._

import spinal.core._
import spinal.lib._

// import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.Random

import registerfile.RegisteredLookupInstr
import registerfile.WriteBackLookupRes
import spinal.sim.SimThread

object RoutingTableSimHelpers {
  val conf = DedupConfig(nodeIdxWidth = 4, 
                         rtConf = RoutingTableConfig(routingChannelCount = 10, 
                                                     routingDecisionBits = 15))

  val instrBitWidth = DedupCoreOp().getBitsWidth

  // Helpers
  def randInstrGen(printRes : Boolean = false) : BigInt = {
    val sha3 = BigInt(conf.htConf.hashValWidth, Random)
    val tag = BigInt(conf.rfConf.tagWidth, Random)
    val opcode = BigInt(1 + Random.nextInt(3))
    val srcNode = BigInt(conf.nodeIdxWidth, Random)
    // instr gen
    var rawInstr = BigInt(0)
    val bitOffset = SimHelpers.BitOffset()
    bitOffset.next(conf.htConf.hashValWidth)
    rawInstr = rawInstr + (sha3 << bitOffset.low)
    bitOffset.next(conf.rfConf.tagWidth)
    rawInstr = rawInstr + (tag << bitOffset.low)
    bitOffset.next(DedupCoreOp().getBitsWidth)
    rawInstr = rawInstr + (opcode << bitOffset.low)
    bitOffset.next(conf.nodeIdxWidth)
    rawInstr = rawInstr + (srcNode << bitOffset.low)

    rawInstr
  }

  def localInstr(inputInstr : BigInt, localNodeIdx : BigInt) : BigInt = {
    val bitOffset = SimHelpers.BitOffset()
    bitOffset.next(conf.htConf.hashValWidth)
    bitOffset.next(conf.rfConf.tagWidth)
    bitOffset.next(DedupCoreOp().getBitsWidth)
    bitOffset.next(conf.nodeIdxWidth)
    val inputSrcNode = SimHelpers.bigIntTruncVal(inputInstr, bitOffset.high, bitOffset.low)
    var rawInstr = inputInstr
    rawInstr = rawInstr - (inputSrcNode << bitOffset.low)
    rawInstr = rawInstr + (localNodeIdx << bitOffset.low)

    rawInstr
  }

  def instrEncode(rawInstr : BigInt, totalWidth : Int = 512) : BigInt = {
    var encInstr = BigInt(0)
    encInstr = encInstr + rawInstr
    encInstr = encInstr + (BigInt(1) << (totalWidth - 1))
    encInstr
  }

  def randResGen(printRes : Boolean = false) : BigInt = {
    val refCount = BigInt(conf.lbaWidth, Random)
    val ssdLba   = BigInt(conf.lbaWidth, Random)
    val nodeIdx  = BigInt(conf.nodeIdxWidth   , Random)
    val tag      = BigInt(conf.rfConf.tagWidth, Random)
    val dstNode  = BigInt(conf.nodeIdxWidth   , Random)
    // instr gen
    var rawRes = BigInt(0)
    val bitOffset = SimHelpers.BitOffset()
    bitOffset.next(conf.lbaWidth)
    rawRes = rawRes + (refCount << bitOffset.low)
    bitOffset.next(conf.lbaWidth)
    rawRes = rawRes + (ssdLba << bitOffset.low)
    bitOffset.next(conf.nodeIdxWidth)
    rawRes = rawRes + (nodeIdx << bitOffset.low)
    bitOffset.next(conf.rfConf.tagWidth)
    rawRes = rawRes + (tag << bitOffset.low)
    bitOffset.next(conf.nodeIdxWidth)
    rawRes = rawRes + (dstNode << bitOffset.low)

    rawRes
  }

  def resEncode(rawRes : BigInt, totalWidth : Int = 512) : BigInt = {
    var encRes = BigInt(0)
    encRes = encRes + rawRes
    encRes = encRes + (BigInt(0) << (totalWidth - 1))
    encRes
  }

  def isInstr(rawOutput : BigInt, totalWidth : Int = 512) : Boolean = {
    SimHelpers.bigIntTruncVal(rawOutput, totalWidth - 1, totalWidth - 1) == BigInt(1)
  }

  def routingDecisionInstr(hash: BigInt, 
                      nodeStartList: ListBuffer[BigInt], 
                      nodeLenList: ListBuffer[BigInt],
                      routingDecisionBits: Int): Int = {
    val ringSize = BigInt(1) << routingDecisionBits
    // Function to calculate distance on a ring
    def ringDistance(a: BigInt, b: BigInt): BigInt = {
      val directDistance = (a - b).abs
      Seq(directDistance, ringSize - directDistance).min
    }

    // Find the nearest node index
    val distances = nodeStartList.indices.map { index =>
      val nodeEnd = (nodeStartList(index) + nodeLenList(index)) 
      val nodeEndWrapped = (nodeStartList(index) + nodeLenList(index)) % ringSize
      val isWrapped = (nodeStartList(index) + nodeLenList(index)) >= ringSize
      var isInside = false
      if (!isWrapped) {
        isInside = (hash >= nodeStartList(index) && hash < nodeEndWrapped)
      } else{
        isInside = (hash >= nodeStartList(index) && hash < ringSize) || (hash >= 0 && hash < nodeEndWrapped)
      } 
      if (isInside){
        BigInt(0)
      } else {
        // Outside the node, calculate distance
        val startDistance = ringDistance(hash, nodeStartList(index))
        val endDistance = ringDistance(hash, nodeEndWrapped)
        Seq(startDistance, endDistance).min
      }
    }

    // Return the index of the node with the smallest distance
    distances.indexOf(distances.min)
  }

  def routingDecisionRes(dstIdx: BigInt, 
                         nodeIdxList: ListBuffer[BigInt],
                         idxWidthBits: Int): Int = {
    val ringSize = BigInt(1) << idxWidthBits
    // Function to calculate distance on a ring
    def ringDistance(a: BigInt, b: BigInt): BigInt = {
      val directDistance = (a - b).abs
      Seq(directDistance, ringSize - directDistance).min
    }

    // Find the nearest node index
    val distances = nodeIdxList.map{ringDistance(dstIdx, _)}

    // Return the index of the node with the smallest distance
    distances.indexOf(distances.min)
  }

  def chordRoutingDecisionInstr(hash: BigInt, 
                                nodeStartList: ListBuffer[BigInt], 
                                nodeLenList: ListBuffer[BigInt],
                                routingDecisionBits: Int): Int = {
    val ringSize = BigInt(1) << routingDecisionBits
    // Function to calculate the end hash of a node's range
    def nodeEndHash(startHash: BigInt, length: BigInt): BigInt = {
      (startHash + length) % ringSize
    }
    val nodeEndList = nodeStartList.indices.map{ index =>
      nodeEndHash(nodeStartList(index), nodeLenList(index))
    }
    // Determine if the hash is within a node's range
    def inRange(endHash: BigInt): Boolean = {
      val startVal = nodeStartList(0)
      val endVal = hash
      if (startVal <= endVal) {
        endHash >= startVal && endHash <= endVal
      } else {
        endHash >= startVal || endHash <= endVal
      }
    }

    val isEndInside = nodeEndList.map(a => inRange(a))
    val isStartInside = nodeStartList.map(a => inRange(a))
    // println(hash)
    // println()
    // nodeStartList.foreach(println)
    // println()
    // nodeLenList.foreach(println)
    // println()
    // isStartInside.foreach(println)
    // for (index <- 0 until nodeEndList.length){
    //   print(isStartInside(index))
    // }
    val result = isStartInside.lastIndexOf(true)
    result
  }

  def chordRoutingDecisionRes(dstIdx: BigInt, 
                              nodeIdxList: ListBuffer[BigInt],
                              idxWidthBits: Int): Int = {
    val ringSize = BigInt(1) << idxWidthBits
    // Determine if the hash is within a node's range
    def inRange(endHash: BigInt): Boolean = {
      val startVal = nodeIdxList(0)
      val endVal = dstIdx
      if (startVal <= endVal) {
        endHash >= startVal && endHash <= endVal
      } else {
        endHash >= startVal || endHash <= endVal
      }
    }

    val isInside = nodeIdxList.map(a => inRange(a))
    val result = isInside.lastIndexOf(true)
    result
  }
}

case class RoutingTableTB() extends Component{
  val conf = RoutingTableSimHelpers.conf
  val io = new Bundle {
    // input: instr, res from local + remote raw input
    val localInstrIn         = slave Stream(RegisteredLookupInstr(conf))
    val localWriteBackResIn  = slave Stream(RoutedWriteBackLookupRes(conf))
    val remoteRecvStrmIn     = slave Stream(Bits(conf.networkWordSizeBit bits))
    // output: instr, res to local + remote raw output
    val localInstrOut        = master Stream(RoutedLookupInstr(conf))
    val localWriteBackResOut = master Stream(WriteBackLookupRes(conf))
    val remoteSendStrmOut    = Vec(master Stream(Bits(conf.networkWordSizeBit bits)), conf.rtConf.routingChannelCount - 1)
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

  val routingTableTop = RoutingTableTop(conf)
  routingTableTop.io <> io
}

class NearestRoutingTests extends AnyFunSuite {
  test("Routing Table Nearest Routing Test"){
    val compiledRTL = if (sys.env.contains("VCS_HOME")) SimConfig.withVpdWave.withVCS.compile(RoutingTableTB())
    else SimConfig.withWave.compile(RoutingTableTB())

    compiledRTL.doSim { dut =>
      RoutingTableNearestRoutingSim.sim(dut)
    }
  }
}

object RoutingTableNearestRoutingSim {
  def sim(dut: RoutingTableTB): Unit = {
    val conf = RoutingTableSimHelpers.conf
    dut.clockDomain.forkStimulus(period = 2)
    SimTimeout(100000)
    dut.io.localInstrIn       .valid #= false
    dut.io.localWriteBackResIn.valid #= false
    dut.io.remoteRecvStrmIn   .valid #= false

    dut.io.localInstrOut       .ready #= false
    dut.io.localWriteBackResOut.ready #= false
    dut.io.remoteSendStrmOut.foreach(_.ready #= false)

    dut.io.updateRoutingTableContent #= false

    dut.clockDomain.waitSampling(10)

    /* Verification settings */
    val totalInstrCount = 512
    val localInstrCount = 32 // first 32 from local
    val totalResCount = 512
    val localResCount = 32 // first 32 from local

    assert(conf.rtConf.routingChannelCount >= 8)
    val simNodeIdx = BigInt(3) << (conf.nodeIdxWidth - 2)
    val hashValueStartList : ListBuffer[BigInt] = ListBuffer()
    val hashValueLenList   : ListBuffer[BigInt] = ListBuffer()
    val nodeIdxList        : ListBuffer[BigInt] = ListBuffer()

    val activeChannelCount = 4
    dut.io.routingTableContent.activeChannelCount #= BigInt(activeChannelCount)
    dut.io.routingTableContent.routingMode        #= false

    hashValueStartList.append(BigInt(15) << (conf.rtConf.routingDecisionBits - 4))
    hashValueLenList  .append(BigInt(2) << (conf.rtConf.routingDecisionBits - 4))
    nodeIdxList       .append(simNodeIdx)

    hashValueStartList.append(BigInt(1) << (conf.rtConf.routingDecisionBits - 4))
    hashValueLenList  .append(BigInt(1) << (conf.rtConf.routingDecisionBits - 4))
    nodeIdxList       .append(simNodeIdx - BigInt(1))

    hashValueStartList.append(BigInt(11) << (conf.rtConf.routingDecisionBits - 4))
    hashValueLenList  .append(BigInt(4) << (conf.rtConf.routingDecisionBits - 4))
    nodeIdxList       .append(simNodeIdx + BigInt(1))

    hashValueStartList.append(BigInt(5) << (conf.rtConf.routingDecisionBits - 4))
    hashValueLenList  .append(BigInt(1) << (conf.rtConf.routingDecisionBits - 4))
    nodeIdxList       .append(simNodeIdx + BigInt(3))

    for (idx <- activeChannelCount until conf.rtConf.routingChannelCount){
      hashValueStartList.append(0)
      hashValueLenList  .append(BigInt(1) << (conf.rtConf.routingDecisionBits))
      nodeIdxList       .append(BigInt(21 + idx) << (conf.nodeIdxWidth - 5))
    }

    for (idx <- 0 until conf.rtConf.routingChannelCount){
      dut.io.routingTableContent.hashValueStart(idx) #= hashValueStartList(idx)
      dut.io.routingTableContent.hashValueLen  (idx) #= hashValueLenList  (idx)
      dut.io.routingTableContent.nodeIdx       (idx) #= nodeIdxList       (idx)
    }

    // write config
    dut.io.updateRoutingTableContent #= true
    dut.clockDomain.waitSampling(1)
    dut.io.updateRoutingTableContent #= false

    val rawInstrList     : ListBuffer[BigInt] = ListBuffer()
    val goldenInstrList  : ListBuffer[BigInt] = ListBuffer()
    val decisionBitsList : ListBuffer[BigInt] = ListBuffer()
    for (i <- 0 until totalInstrCount) {
      val randInstr = RoutingTableSimHelpers.randInstrGen()
      val localInstr = RoutingTableSimHelpers.localInstr(randInstr, simNodeIdx)
      rawInstrList.append(randInstr)
      if (i < localInstrCount){
        goldenInstrList.append(localInstr)
        decisionBitsList.append(SimHelpers.bigIntTruncVal(localInstr, conf.htConf.idxBucketWidth + conf.rtConf.routingDecisionBits - 1, conf.htConf.idxBucketWidth))
      } else {
        goldenInstrList.append(randInstr)
        decisionBitsList.append(SimHelpers.bigIntTruncVal(randInstr, conf.htConf.idxBucketWidth + conf.rtConf.routingDecisionBits - 1, conf.htConf.idxBucketWidth))
      }
    }

    val resList     : ListBuffer[BigInt] = ListBuffer()
    val resNodeList : ListBuffer[BigInt] = ListBuffer()
    for (i <- 0 until totalResCount) {
      val randRes = RoutingTableSimHelpers.randResGen()
      resList.append(randRes)
      resNodeList.append(SimHelpers.bigIntTruncVal(randRes, RoutedWriteBackLookupRes(conf).getBitsWidth - 1, RoutedWriteBackLookupRes(conf).getBitsWidth - conf.nodeIdxWidth))
    }

    /* golden result */
    val goldenInstrOut : ListBuffer[ListBuffer[BigInt]] = ListBuffer()
    for (idx <- 0 until conf.rtConf.routingChannelCount){
      goldenInstrOut.append(ListBuffer())
    }
    for (instrIdx <- 0 until totalInstrCount){
      val decisionBits = decisionBitsList(instrIdx)
      val routingChannelIdx = RoutingTableSimHelpers.routingDecisionInstr(decisionBits, hashValueStartList.take(activeChannelCount), hashValueLenList.take(activeChannelCount), conf.rtConf.routingDecisionBits)
      assert(routingChannelIdx < activeChannelCount)
      goldenInstrOut(routingChannelIdx).append(goldenInstrList(instrIdx))
    }

    val goldenResOut : ListBuffer[ListBuffer[BigInt]] = ListBuffer()
    for (idx <- 0 until conf.rtConf.routingChannelCount){
      goldenResOut.append(ListBuffer())
    }
    for (resIdx <- 0 until totalResCount){
      val nodeIdx = resNodeList(resIdx)
      val routingChannelIdx = RoutingTableSimHelpers.routingDecisionRes(nodeIdx, nodeIdxList.take(activeChannelCount), conf.nodeIdxWidth)
      assert(routingChannelIdx < activeChannelCount)
      goldenResOut(routingChannelIdx).append(resList(resIdx))
    }

    /* Stimuli injection from local */
    val instrLocalPush = fork {
      for (idx <- 0 until localInstrCount) {
        dut.io.localInstrIn.sendData(dut.clockDomain, rawInstrList(idx))
      }
    }

    val resLocalPush = fork {
      for (idx <- 0 until localResCount) {
        dut.io.localWriteBackResIn.sendData(dut.clockDomain, resList(idx))
      }
    }

    /* Res Watch*/
    val localInstrWatch = fork {
      val localInstrOutCount = goldenInstrOut(0).length
      for (idx <- 0 until localInstrOutCount) {
        val resp = dut.io.localInstrOut.recvData(dut.clockDomain)
        assert(resp == goldenInstrOut(0)(idx))
        // if (resp != goldenInstrOut(0)(idx)) {
        //   println("local")
        //   println(resp.toString(16))
        //   println(goldenInstrOut(0)(idx).toString(16))
        //   println(rawInstrList(0).toString(16))
        // }
      }
    }

    val localResWatch = fork {
      val localResOutCount = goldenResOut(0).length
      for (idx <- 0 until localResOutCount) {
        val resp = dut.io.localWriteBackResOut.recvData(dut.clockDomain)
        val respWithDstNode = resp + (simNodeIdx << WriteBackLookupRes(conf).getBitsWidth)
        assert(respWithDstNode == goldenResOut(0)(idx))
        // if (respWithDstNode != goldenResOut(0)(idx)) {
          // println("local")
          // println(resp.toString(16))
          // println(respWithDstNode.toString(16))
          // println(goldenResOut(0)(idx).toString(16))
          // println(resList(0).toString())
        // }
      }
    }

    val remoteWatchThreads : ListBuffer[SimThread] = ListBuffer() 
    for (remoteChannelIdx <- 0 until conf.rtConf.routingChannelCount - 1){
      val watchThread = fork {
        val threadId = remoteChannelIdx
        val remoteInstrOutCount = goldenInstrOut(remoteChannelIdx + 1).length
        val remoteResOutCount = goldenResOut(remoteChannelIdx + 1).length
        val totalOutCount = remoteInstrOutCount + remoteResOutCount
        var instrIdx = 0
        var resIdx = 0
        for (idx <- 0 until totalOutCount) {
          val resp = dut.io.remoteSendStrmOut(remoteChannelIdx).recvData(dut.clockDomain)
          if (SimHelpers.bigIntTruncVal(resp, 511, 511) == BigInt(1)){
            // is Instr
            val instrResp = SimHelpers.bigIntTruncVal(resp, RoutedLookupInstr(conf).getBitsWidth - 1, 0)
            assert(instrResp == goldenInstrOut(remoteChannelIdx + 1)(instrIdx))
            // if (instrResp != goldenInstrOut(remoteChannelIdx + 1)(instrIdx)) {
            //   println(threadId)
            //   println(instrResp.toString(16))
            //   println(goldenInstrOut(remoteChannelIdx + 1)(instrIdx).toString(16))
            //   println(rawInstrList(0).toString(16))
            // }
            instrIdx = instrIdx + 1
          } else {
            // is res
            val resResp = SimHelpers.bigIntTruncVal(resp, RoutedWriteBackLookupRes(conf).getBitsWidth - 1, 0)
            assert(resResp == goldenResOut(remoteChannelIdx + 1)(resIdx))
            // if (resResp != goldenResOut(remoteChannelIdx + 1)(resIdx)) {
            //   println(threadId)
            //   println(resResp.toString(16))
            //   println(goldenResOut(remoteChannelIdx + 1)(resIdx).toString(16))
            //   // println(resList(0).toString())
            // }
            resIdx = resIdx + 1
          }
        }
      }
      remoteWatchThreads.append(watchThread)
    }

    instrLocalPush.join()
    resLocalPush.join()

    val remotePush = fork {
      for (idx <- localInstrCount until totalInstrCount) {
        dut.io.remoteRecvStrmIn.sendData(dut.clockDomain, RoutingTableSimHelpers.instrEncode(rawInstrList(idx)))
      }
      for (idx <- localResCount until totalResCount) {
        dut.io.remoteRecvStrmIn.sendData(dut.clockDomain, RoutingTableSimHelpers.resEncode(resList(idx)))
      }
    }
    remotePush.join()
    localInstrWatch.join()
    localResWatch.join()
    remoteWatchThreads.map(_.join())
  }
}