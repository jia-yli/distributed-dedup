package dedup

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._

import dedup.pagewriter.PageWriterResp
import dedup.pagewriter.SSDInstr
import dedup.hashtable.HashTableConfig
import dedup.registerfile.RegisterFileConfig
import dedup.routingtable.RoutingTableConfig

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import util.sim._
import util.sim.SimDriver._
import util.AxiMux

import scala.collection.mutable
import scala.collection.mutable._
import scala.util.Random

class SingleDedupCoreTests extends AnyFunSuite {
  def singleDedupCoreSim(): Unit = {

    val compiledRTL = if (sys.env.contains("VCS_HOME")) SimConfig.withVpdWave.withVCS.compile(new SingleDedupCoreTB())
    else SimConfig.withWave.compile(new SingleDedupCoreTB())

    compiledRTL.doSim { dut =>
      SingleDedupCoreSim.doSim(dut)
    }
  }

  test("Single DedupCore Test"){
    singleDedupCoreSim()
  }
}


object SingleDedupCoreSim {

  def doSim(dut: SingleDedupCoreTB): Unit = {
    dut.clockDomain.forkStimulus(period = 2)
    SimTimeout(1000000)
    dut.io.opStrmIn.valid #= false
    dut.io.pgStrmIn.valid #= false
    dut.io.initEn #=false
    dut.io.clearInitStatus #= false
    dut.io.updateRoutingTableContent #= false
    // dut.io.SSDDataOut.valid  #= false
    /** memory model for HashTab */
    SimDriver.instAxiMemSim(dut.io.axiMem, dut.clockDomain, None)

    dut.clockDomain.waitSampling(10)

    /** init */
    dut.io.initEn #= true
    dut.io.clearInitStatus #= true
    dut.clockDomain.waitSampling()
    dut.io.initEn #= false
    dut.io.clearInitStatus #= false
    dut.clockDomain.waitSamplingWhere(dut.io.initDone.toBoolean)

    // dut.io.factorThrou         #= 16
    // dut.io.SSDDataIn .ready    #= true
    // dut.io.SSDDataOut.valid    #= false
    // dut.io.SSDDataOut.fragment #= 0
    // dut.io.SSDDataOut.last     #= false
    // dut.io.SSDInstrIn.ready    #= true

    // init routing table
    val simNodeIdx = 3
    val conf = DedupCoreSimHelpers.singleCoreConf
    for (index <- 0 until conf.rtConf.routingChannelCount){
      dut.io.routingTableContent.hashValueStart(index) #= 0
      dut.io.routingTableContent.hashValueLen(index) #= {if (index == 0) (1 << conf.rtConf.routingDecisionBits) else 0}
      dut.io.routingTableContent.nodeIdx(index) #= {if (index == 0) simNodeIdx else (simNodeIdx + 1)}
    }
    dut.io.routingTableContent.activeChannelCount #= 1
    dut.io.updateRoutingTableContent #= true
    dut.clockDomain.waitSampling()
    dut.io.updateRoutingTableContent #= false

    /** generate page stream */
    val pageNum =  128
    val dupFacotr = 4
    val opNum = 128
    assert(pageNum%dupFacotr==0, "pageNumber must be a multiple of dupFactor")
    assert(pageNum%opNum==0, "pageNumber must be a multiple of operation number")
    val uniquePageNum = pageNum/dupFacotr
    val pagePerOp = pageNum/opNum
    val pageSize = 4096
    val bytePerWord = 64
    // random data
    val uniquePgData = List.fill[BigInt](uniquePageNum*pageSize/bytePerWord)(BigInt(bytePerWord*8, Random))
    // fill all word with page index
    // val uniquePgData = List.tabulate[BigInt](uniquePageNum*pageSize/bytePerWord){idx => idx/(pageSize/bytePerWord)}
    
    val simDataNode = BigInt(77)
    var opStrmData: ListBuffer[BigInt] = ListBuffer()
    for (i <- 0 until opNum) {
      opStrmData.append(DedupCoreSimHelpers.writeInstrGen(2*i*pagePerOp, pagePerOp, simDataNode))
    }

    var pgStrmData: ListBuffer[BigInt] = ListBuffer()

    // 1,1,2,2,...,N,N
    // for (i <- 0 until uniquePageNum) {
    //   for (j <- 0 until dupFacotr) {
    //     for (k <- 0 until pageSize/bytePerWord) {
    //       pgStrmData.append(uniquePgData(i*pageSize/bytePerWord+k))
    //     }
    //   }
    // }

    // 1,...,N, 1,...,N, 1,...,N
    for (j <- 0 until dupFacotr) {
      for (i <- 0 until uniquePageNum) {
        for (k <- 0 until pageSize/bytePerWord) {
          pgStrmData.append(uniquePgData(i*pageSize/bytePerWord+k))
        }
      }
    }
    val goldenPgIdx = List.tabulate[BigInt](pageNum){idx => ((idx/pagePerOp)) * pagePerOp + idx} // start + idx

    // random shuffle
    // var initialPgOrder: List[Int] = List()
    // for (j <- 0 until dupFacotr) {
    //   initialPgOrder = initialPgOrder ++ List.range(0, uniquePageNum) // page order: 1,2,...,N,1,2,...,N,...
    // }

    // val shuffledPgOrder = Random.shuffle(initialPgOrder)
    // assert(shuffledPgOrder.length == pageNum, "Total page number must be the same as the predefined parameter")
    // for (pgIdx <- 0 until pageNum) {
    //   val uniquePgIdx = shuffledPgOrder(pgIdx)
    //   for (k <- 0 until pageSize/bytePerWord) {
    //     pgStrmData.append(uniquePgData(uniquePgIdx * pageSize/bytePerWord+k))
    //   }
    // }

    /* Stimuli injection */
    val opStrmPush = fork {
      var opIdx: Int = 0
      for (opIdx <- 0 until opNum) {
        dut.io.opStrmIn.sendData(dut.clockDomain, opStrmData(opIdx))
      }
    }

    val pgStrmPush = fork {
      var wordIdx: Int = 0
      for (_ <- 0 until pageNum) {
        for (_ <- 0 until pageSize / bytePerWord) {
          dut.io.pgStrmIn.sendData(dut.clockDomain, pgStrmData(wordIdx))
          wordIdx += 1
        }
      }
    }

    val goldenPgRefCountAppeared = ListBuffer.tabulate[ListBuffer[Boolean]](uniquePageNum){idx => 
      ListBuffer.tabulate[Boolean](dupFacotr){idx => 
        false
      }
    }

    val pgWrRespWatch = fork {
      for (pageIdx <- 0 until pageNum) {
        val respData  = dut.io.pgResp.recvData(dut.clockDomain)
        DedupCoreSimHelpers.singleDedupCoreDecodeAndCheck(respData = respData,
                                                          uniquePageIdx = pageIdx % uniquePageNum,
                                                          goldenPgRefCountAppeared = goldenPgRefCountAppeared,
                                                          goldenNodeIdx = simNodeIdx,
                                                          goldenPgIdx = goldenPgIdx(pageIdx),
                                                          goldenOpCode = 1,
                                                          goldenDataNode = simDataNode)
        // val bitOffset = SimHelpers.BitOffset()

        // bitOffset.next(conf.htConf.hashValWidth)
        // val sha3Hash     = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
        // bitOffset.next(conf.lbaWidth)
        // val RefCount     = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
        // bitOffset.next(conf.lbaWidth)
        // val SSDLBA       = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
        // bitOffset.next(conf.nodeIdxWidth)
        // val nodeIdx      = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
        // bitOffset.next(conf.lbaWidth)
        // val hostLBAStart = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
        // bitOffset.next(conf.lbaWidth)
        // val hostLBALen   = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
        // bitOffset.next(Bool().getBitsWidth)
        // val isExec       = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
        // bitOffset.next(DedupCoreOp().getBitsWidth)
        // val opCode       = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)

        // val uniquePageIdx = pageIdx % uniquePageNum
        // // print(pageIdx, uniquePageIdx)
        // // print(goldenPgRefCountAppeared(uniquePageIdx))
        // assert(goldenPgRefCountAppeared(uniquePageIdx)(RefCount.toInt - 1) == false)
        // goldenPgRefCountAppeared(uniquePageIdx)(RefCount.toInt - 1) = true
        // assert(nodeIdx == simNodeIdx)
        // assert(hostLBAStart == goldenPgIdx(pageIdx))
        // assert(hostLBALen == 1)
        // assert(isExec == (RefCount == 1).toInt)
        // assert(opCode == 1)

        // // println(s"pageIdx: ${pageIdx}")
        // // println(s"${RefCount} == ${goldenpgRefCount(pageIdx)}")
        // // println(s"${hostLBAStart} == ${goldenPgIdx(pageIdx)}")
        // // println(s"${hostLBALen} == 1")
        // // println(s"${isExec} == ${goldenPgIsNew(pageIdx)}")
        // // println(s"${opCode} == 0")
      }
    }

    opStrmPush.join()
    pgStrmPush.join()
    pgWrRespWatch.join()

  }
}

case class SingleDedupCoreTB() extends Component{

  val conf = DedupCoreSimHelpers.singleCoreConf

  val io = new Bundle {
    /** input */
    val opStrmIn = slave Stream (Bits(conf.instrTotalWidth bits))
    val pgStrmIn = slave Stream (Bits(conf.wordSizeBit bits))
    /** output */
    val pgResp   = master Stream (PageWriterResp(conf))
    /** inter-board via network */
    // val remoteRecvStrmIn  = slave Stream(Bits(conf.networkWordSizeBit bits))
    // val remoteSendStrmOut = Vec(master Stream(Bits(conf.networkWordSizeBit bits)), conf.rtConf.routingChannelCount - 1)
    /** control signals */
    val initEn   = in Bool()
    val clearInitStatus = in Bool()
    val initDone = out Bool()

    // routing table config port
    val updateRoutingTableContent = in Bool()
    val routingTableContent = new Bundle{
      val hashValueStart = in Vec(UInt(conf.rtConf.routingDecisionBits bits), conf.rtConf.routingChannelCount)
      val hashValueLen = in Vec(UInt((conf.rtConf.routingDecisionBits + 1) bits), conf.rtConf.routingChannelCount)
      val nodeIdx = in Vec(UInt(conf.nodeIdxWidth bits), conf.rtConf.routingChannelCount)
      val activeChannelCount = in UInt(log2Up(conf.rtConf.routingChannelCount + 1) bits)
      val routingMode = in Bool()
    }

    /** hashTab memory interface */
    val axiMem   = master(Axi4(Axi4ConfigAlveo.u55cHBM))
    
    // SSD Intf for TB
    // val SSDDataIn  = master Stream (Fragment(Bits(conf.wordSizeBit bits)))
    // val SSDDataOut = slave Stream (Fragment(Bits(conf.wordSizeBit bits)))
    // val SSDInstrIn = master Stream (SSDInstr(conf))

    /** pgStore throughput control factor */
    // val factorThrou = in UInt(5 bits)
  }

  val dedupCore = WrapDedupCore()

  // auto connect
  dedupCore.io.opStrmIn                  <> io.opStrmIn
  dedupCore.io.pgStrmIn                  <> io.pgStrmIn
  dedupCore.io.pgResp                    <> io.pgResp
  dedupCore.io.remoteRecvStrmIn.setIdle()
  dedupCore.io.remoteSendStrmOut.map(_.freeRun())
  dedupCore.io.initEn                    <> io.initEn
  dedupCore.io.clearInitStatus           <> io.clearInitStatus
  dedupCore.io.initDone                  <> io.initDone
  dedupCore.io.updateRoutingTableContent <> io.updateRoutingTableContent
  dedupCore.io.routingTableContent       <> io.routingTableContent
  dedupCore.io.SSDDataIn.freeRun()
  dedupCore.io.SSDDataOut.setIdle()
  dedupCore.io.SSDInstrIn.freeRun()
  dedupCore.io.factorThrou := 16

  

  // axi mux, RR arbitration
  val axiMux = AxiMux(conf.htConf.sizeFSMArray + 1)
  for (idx <- 0 until conf.htConf.sizeFSMArray + 1){
    axiMux.io.axiIn(idx) << dedupCore.io.axiMem(idx)
  }
  axiMux.io.axiOut >> io.axiMem
}

object DedupCoreSimHelpers {
  val conf = DedupConfig()
  val singleCoreConf = DedupConfig(rfConf = RegisterFileConfig(tagWidth = 5),
                                   rtConf = RoutingTableConfig(routingChannelCount = 10,
                                                               routingDecisionBits = 16),
                                   htConf = HashTableConfig (hashValWidth = 256, 
                                                             ptrWidth = 32, 
                                                             hashTableSize = 128 * 64, 
                                                             expBucketSize = 128, 
                                                             hashTableOffset = (BigInt(1) << 30), 
                                                             bfEnable = true,
                                                             bfOptimizedReconstruct = false,
                                                             sizeFSMArray = 6))
  val dualCoreConf = DedupConfig(rfConf = RegisterFileConfig(tagWidth = 5),
                                  rtConf = RoutingTableConfig(routingChannelCount = 10,
                                                              routingDecisionBits = 16),
                                  htConf = HashTableConfig (hashValWidth = 256, 
                                                            ptrWidth = 32, 
                                                            hashTableSize = 128 * 64, 
                                                            expBucketSize = 128, 
                                                            hashTableOffset = (BigInt(1) << 30), 
                                                            bfEnable = true,
                                                            bfOptimizedReconstruct = false,
                                                            sizeFSMArray = 6))
  val instrBitWidth = DedupCoreOp().getBitsWidth

  // generate 512 bit representation of instruction
  def nopGen(printRes : Boolean = false) : BigInt = {
    if (printRes){
      println(s"[NOP] opcode = 0")
    }
    BigInt(0)
  }

  def writeInstrGen(start:BigInt, len:BigInt, dataNode:BigInt, printRes : Boolean = false) : BigInt = {
    val start_trunc = SimHelpers.bigIntTruncVal(start, conf.lbaWidth - 1, 0)
    val len_trunc   = SimHelpers.bigIntTruncVal(len  , conf.lbaWidth - 1, 0)
    if (printRes){
      println(s"[WRITE] opcode = 1, start=$start_trunc, len=$len_trunc")
    }
    var rawInstr = BigInt(0)
    rawInstr = rawInstr + (BigInt(1) << (conf.instrTotalWidth - instrBitWidth))
    rawInstr = rawInstr + (dataNode << 2*conf.lbaWidth)
    rawInstr = rawInstr + (start_trunc << conf.lbaWidth)
    rawInstr = rawInstr + (len_trunc << 0)
    rawInstr
  }

  def eraseInstrGen(sha3:BigInt, printRes : Boolean = false) : BigInt = {
    val sha3_trunc = SimHelpers.bigIntTruncVal(sha3, 255, 0)
    if (printRes){
      println(s"[ERASE] opcode = 2, sha3=$sha3")
    }
    var rawInstr = BigInt(0)
    rawInstr = rawInstr + (BigInt(2) << (conf.instrTotalWidth - instrBitWidth))
    rawInstr = rawInstr + (sha3 << 0)
    rawInstr
  }

  def readInstrGen(sha3:BigInt, printRes : Boolean = false) : BigInt = {
    val sha3_trunc = SimHelpers.bigIntTruncVal(sha3, 255, 0)
    if (printRes){
      println(s"[READ] opcode = 3, sha3=$sha3")
    }
    var rawInstr = BigInt(0)
    rawInstr = rawInstr + (BigInt(3) << (conf.instrTotalWidth - instrBitWidth))
    rawInstr = rawInstr + (sha3 << 0)
    rawInstr
  }

  def singleDedupCoreDecodeAndCheck(respData : BigInt,
                                    uniquePageIdx : Int,
                                    goldenPgRefCountAppeared : ListBuffer[ListBuffer[Boolean]],
                                    goldenNodeIdx : Int,
                                    goldenPgIdx : BigInt,
                                    goldenOpCode : Int,
                                    goldenDataNode : BigInt){
    val bitOffset = SimHelpers.BitOffset()
    bitOffset.next(conf.htConf.hashValWidth)
    val sha3Hash     = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
    bitOffset.next(conf.lbaWidth)
    val RefCount     = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
    bitOffset.next(conf.lbaWidth)
    val SSDLBA       = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
    bitOffset.next(conf.nodeIdxWidth)
    val nodeIdx      = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
    bitOffset.next(conf.lbaWidth)
    val hostLBAStart = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
    bitOffset.next(conf.lbaWidth)
    val hostLBALen   = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
    bitOffset.next(conf.dataNodeIdxWidth)
    val hostDataNodeIdx   = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
    bitOffset.next(Bool().getBitsWidth)
    val isExec       = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
    bitOffset.next(DedupCoreOp().getBitsWidth)
    val opCode       = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)

    // val uniquePageIdx = pageIdx % uniquePageNum
    // print(pageIdx, uniquePageIdx)
    // print(goldenpgRefCountAppeared(uniquePageIdx))
    assert(nodeIdx == goldenNodeIdx)
    assert(hostLBAStart == goldenPgIdx)
    assert(hostLBALen == 1)
    assert(hostDataNodeIdx == goldenDataNode)
    assert(opCode == goldenOpCode)
    if (goldenOpCode == 1){
      // write
      assert(isExec == (RefCount == 1).toInt)
      assert(goldenPgRefCountAppeared(uniquePageIdx)(RefCount.toInt - 1) == false)
      goldenPgRefCountAppeared(uniquePageIdx)(RefCount.toInt - 1) = true
    } else if (goldenOpCode == 2){
      // erase
      assert(isExec == (RefCount == 0).toInt)
    } else if (goldenOpCode == 3){
      // read
      assert(isExec == 1)
      assert(goldenPgRefCountAppeared(uniquePageIdx)(RefCount.toInt) == false)
      goldenPgRefCountAppeared(uniquePageIdx)(RefCount.toInt) = true
    } else {
      assert (false)
    }
    
    // println(s"pageIdx: ${pageIdx}")
    // println(s"${RefCount} == ${goldenpgRefCount(pageIdx)}")
    // println(s"${hostLBAStart} == ${goldenPgIdx(pageIdx)}")
    // println(s"${hostLBALen} == 1")
    // println(s"${isExec} == ${goldenPgIsNew(pageIdx)}")
    // println(s"${opCode} == 0")
  }

  def singleDedupSysDecodeAndCheck(respData : BigInt,
                                   uniquePageIdx : Int,
                                   goldenPgRefCountAppeared : ListBuffer[ListBuffer[Boolean]],
                                   goldenNodeIdx : Int,
                                   goldenPgIdx : BigInt,
                                   goldenOpCode : Int,
                                   checkSha3 : Boolean = false,
                                   goldenSha3 : BigInt = 0,
                                   goldenDataNode : BigInt) : BigInt = {
    val bitOffset = SimHelpers.BitOffset()
    bitOffset.next(conf.htConf.hashValWidth)
    val sha3Hash     = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
    bitOffset.next(conf.lbaWidth)
    val RefCount     = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
    bitOffset.next(conf.lbaWidth)
    val SSDLBA       = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
    bitOffset.next(32)
    val nodeIdx      = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
    bitOffset.next(conf.lbaWidth)
    val hostLBAStart = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
    bitOffset.next(conf.lbaWidth)
    val hostLBALen   = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
    bitOffset.next(conf.dataNodeIdxWidth)
    val hostDataNodeIdx   = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
    val isExec       = SimHelpers.bigIntTruncVal(respData, 509, 509)
    val opCode       = SimHelpers.bigIntTruncVal(respData, 511, 510)

    // val uniquePageIdx = pageIdx % uniquePageNum
    // print(pageIdx, uniquePageIdx)
    // print(goldenPgRefCountAppeared(uniquePageIdx))
    if (checkSha3) {
      // println(uniquePageIdx)
      // println(SimHelpers.bigIntTruncVal(sha3Hash, 63, 0))
      // println(SimHelpers.bigIntTruncVal(goldenSha3, 63, 0))
      assert (sha3Hash == goldenSha3)
    }
    assert(nodeIdx == goldenNodeIdx)
    assert(hostLBAStart == goldenPgIdx)
    assert(hostLBALen == 1)
    assert(hostDataNodeIdx == goldenDataNode)
    assert(opCode == goldenOpCode)
    if (goldenOpCode == 1){
      // write
      assert(isExec == (RefCount == 1).toInt)
      assert(goldenPgRefCountAppeared(uniquePageIdx)(RefCount.toInt - 1) == false)
      goldenPgRefCountAppeared(uniquePageIdx)(RefCount.toInt - 1) = true
    } else if (goldenOpCode == 2){
      // erase
      assert(isExec == (RefCount == 0).toInt)
    } else if (goldenOpCode == 3){
      // read
      assert(isExec == 1)
      assert(goldenPgRefCountAppeared(uniquePageIdx)(RefCount.toInt) == false)
      goldenPgRefCountAppeared(uniquePageIdx)(RefCount.toInt) = true
    } else {
      assert (false)
    }
    
    // println(s"pageIdx: ${pageIdx}")
    // println(s"${RefCount} == ${goldenpgRefCount(pageIdx)}")
    // println(s"${hostLBAStart} == ${goldenPgIdx(pageIdx)}")
    // println(s"${hostLBALen} == 1")
    // println(s"${isExec} == ${goldenPgIsNew(pageIdx)}")
    // println(s"${opCode} == 0")
    sha3Hash
  }

  def dualDedupSysDecodeAndCheck(respData : BigInt,
                                 uniquePageIdx : Int,
                                 goldenPgRefCountAppeared : ListBuffer[ListBuffer[Boolean]],
                                //  goldenNodeIdx : Int,
                                 goldenPgIdx : BigInt,
                                 goldenOpCode : Int,
                                 checkSha3 : Boolean = false,
                                 goldenSha3 : BigInt = 0,
                                 goldenDataNode : BigInt) : BigInt = {
    val bitOffset = SimHelpers.BitOffset()
    bitOffset.next(conf.htConf.hashValWidth)
    val sha3Hash     = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
    bitOffset.next(conf.lbaWidth)
    val RefCount     = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
    bitOffset.next(conf.lbaWidth)
    val SSDLBA       = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
    bitOffset.next(32)
    val nodeIdx      = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
    bitOffset.next(conf.lbaWidth)
    val hostLBAStart = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
    bitOffset.next(conf.lbaWidth)
    val hostLBALen   = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
    bitOffset.next(conf.dataNodeIdxWidth)
    val hostDataNodeIdx   = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
    val isExec       = SimHelpers.bigIntTruncVal(respData, 509, 509)
    val opCode       = SimHelpers.bigIntTruncVal(respData, 511, 510)

    // val uniquePageIdx = pageIdx % uniquePageNum
    // print(pageIdx, uniquePageIdx)
    // print(goldenPgRefCountAppeared(uniquePageIdx))
    if (checkSha3) {
      // println(uniquePageIdx)
      // println(SimHelpers.bigIntTruncVal(sha3Hash, 63, 0))
      // println(SimHelpers.bigIntTruncVal(goldenSha3, 63, 0))
      assert (sha3Hash == goldenSha3)
    }
    // assert(nodeIdx == goldenNodeIdx)
    assert(hostLBAStart == goldenPgIdx)
    assert(hostLBALen == 1)
    assert(hostDataNodeIdx == goldenDataNode)
    assert(opCode == goldenOpCode)
    if (goldenOpCode == 1){
      // write
      assert(isExec == (RefCount == 1).toInt)
      assert(goldenPgRefCountAppeared(uniquePageIdx)(RefCount.toInt - 1) == false)
      goldenPgRefCountAppeared(uniquePageIdx)(RefCount.toInt - 1) = true
    } else if (goldenOpCode == 2){
      // erase
      assert(isExec == (RefCount == 0).toInt)
    } else if (goldenOpCode == 3){
      // read
      assert(isExec == 1)
      assert(goldenPgRefCountAppeared(uniquePageIdx)(RefCount.toInt) == false)
      goldenPgRefCountAppeared(uniquePageIdx)(RefCount.toInt) = true
    } else {
      assert (false)
    }
    
    // println(s"pageIdx: ${pageIdx}")
    // println(s"${RefCount} == ${goldenpgRefCount(pageIdx)}")
    // println(s"${hostLBAStart} == ${goldenPgIdx(pageIdx)}")
    // println(s"${hostLBALen} == 1")
    // println(s"${isExec} == ${goldenPgIsNew(pageIdx)}")
    // println(s"${opCode} == 0")
    sha3Hash
  }
}