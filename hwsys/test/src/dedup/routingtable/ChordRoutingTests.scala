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

class ChordRoutingTests extends AnyFunSuite {
  test("Routing Table Chord Routing Test"){
    val compiledRTL = if (sys.env.contains("VCS_HOME")) SimConfig.withVpdWave.withVCS.compile(RoutingTableTB())
    else SimConfig.withWave.compile(RoutingTableTB())

    compiledRTL.doSim { dut =>
      RoutingTableChordRoutingSim.sim(dut)
    }
  }
}

object RoutingTableChordRoutingSim {
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
    val simNodeIdx = BigInt(3)
    val hashValueStartList : ListBuffer[BigInt] = ListBuffer()
    val hashValueLenList   : ListBuffer[BigInt] = ListBuffer()
    val nodeIdxList        : ListBuffer[BigInt] = ListBuffer()

    val activeChannelCount = 4
    dut.io.routingTableContent.activeChannelCount #= BigInt(activeChannelCount)
    dut.io.routingTableContent.routingMode        #= true

    hashValueStartList.append(BigInt(15) << (conf.rtConf.routingDecisionBits - 4))
    hashValueLenList  .append(BigInt(2) << (conf.rtConf.routingDecisionBits - 4))
    nodeIdxList       .append(simNodeIdx)

    hashValueStartList.append(BigInt(1) << (conf.rtConf.routingDecisionBits - 4))
    hashValueLenList  .append(BigInt(1) << (conf.rtConf.routingDecisionBits - 4))
    nodeIdxList       .append(simNodeIdx + BigInt(1))

    hashValueStartList.append(BigInt(2) << (conf.rtConf.routingDecisionBits - 4))
    hashValueLenList  .append(BigInt(3) << (conf.rtConf.routingDecisionBits - 4))
    nodeIdxList       .append(simNodeIdx + BigInt(2))

    hashValueStartList.append(BigInt(8) << (conf.rtConf.routingDecisionBits - 4))
    hashValueLenList  .append(BigInt(1) << (conf.rtConf.routingDecisionBits - 4))
    nodeIdxList       .append(simNodeIdx + BigInt(4))

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
      val routingChannelIdx = RoutingTableSimHelpers.chordRoutingDecisionInstr(decisionBits, hashValueStartList.take(activeChannelCount), hashValueLenList.take(activeChannelCount), conf.rtConf.routingDecisionBits)
      assert(routingChannelIdx < activeChannelCount)
      goldenInstrOut(routingChannelIdx).append(goldenInstrList(instrIdx))
    }

    val goldenResOut : ListBuffer[ListBuffer[BigInt]] = ListBuffer()
    for (idx <- 0 until conf.rtConf.routingChannelCount){
      goldenResOut.append(ListBuffer())
    }
    for (resIdx <- 0 until totalResCount){
      val nodeIdx = resNodeList(resIdx)
      val routingChannelIdx = RoutingTableSimHelpers.chordRoutingDecisionRes(nodeIdx, nodeIdxList.take(activeChannelCount), conf.nodeIdxWidth)
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