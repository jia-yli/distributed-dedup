package dedup

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._

import spinal.lib.bus.amba4.axilite._
import coyote.HostDataIO
import coyote.NetworkDataIO

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import util.sim._
import util.sim.SimDriver._
import util.AxiMux

import scala.collection.mutable
import scala.collection.mutable._
import scala.util.Random

class DualDedupSysTests extends AnyFunSuite {
  def dualDedupSysSim(): Unit = {

    val compiledRTL = if (sys.env.contains("VCS_HOME")) SimConfig.withVpdWave.withVCS.compile(new DualDedupSysTB())
    else SimConfig.withWave.compile(new DualDedupSysTB())

    compiledRTL.doSim { dut =>
      DualDedupSysSim.doSim(dut)
    }
  }

  test("Dual DedupSys Test"){
    dualDedupSysSim()
  }
}


object DualDedupSysSim {

  def doSim(dut: DualDedupSysTB, verbose: Boolean = false): Unit = {
    dut.clockDomain.forkStimulus(period = 2)
    SimTimeout(1000000)
    /** init */
    initAxi4LiteBus(dut.io.board_0_axi_ctrl )
    initHostDataIO (dut.io.board_0_hostd    )
    initNetworkIO  (dut.io.board_0_networkIo)

    initAxi4LiteBus(dut.io.board_1_axi_ctrl )
    initHostDataIO (dut.io.board_1_hostd    )
    initNetworkIO  (dut.io.board_1_networkIo)

    /** memory model for HashTab */
    SimDriver.instAxiMemSim(dut.io.board_0_axi_mem, dut.clockDomain, None)
    SimDriver.instAxiMemSim(dut.io.board_1_axi_mem, dut.clockDomain, None)
    dut.clockDomain.waitSampling(10)

    /** Stage 1: insert pages and get SHA3*/
    val pageNum = 256
    val dupFacotr = 2
    val opNum = 4
    assert(pageNum%dupFacotr==0, "pageNumber must be a multiple of dupFactor")
    assert(pageNum%opNum==0, "pageNumber must be a multiple of operation number")
    val uniquePageNum = pageNum/dupFacotr
    val pagePerOp = pageNum/opNum
    val pageSize = 4096
    val bytePerWord = 64

    // random data
    // val uniquePgData = List.fill[BigInt](uniquePageNum*pageSize/bytePerWord)(BigInt(bytePerWord*8, Random))
    // fill all word with page index
    val uniquePgDataSys0 = List.tabulate[BigInt](uniquePageNum*pageSize/bytePerWord){idx => idx/(pageSize/bytePerWord)}
    val uniquePgDataSys1 = List.tabulate[BigInt](uniquePageNum*pageSize/bytePerWord){idx => (idx + uniquePageNum*pageSize/bytePerWord)/(pageSize/bytePerWord)}
    
    val opStrmDataSys0: ListBuffer[BigInt] = ListBuffer()
    for (i <- 0 until opNum) {
      val newInstr = DedupCoreSimHelpers.writeInstrGen(2*i*pagePerOp,pagePerOp)
      opStrmDataSys0.append(newInstr)
    }
    val opStrmDataSys1: ListBuffer[BigInt] = ListBuffer()
    for (i <- 0 until opNum) {
      val newInstr = DedupCoreSimHelpers.writeInstrGen(2*i*pagePerOp,pagePerOp)
      opStrmDataSys1.append(newInstr)
    }

    var pgStrmDataSys0: ListBuffer[BigInt] = ListBuffer()
    var pgStrmDataSys1: ListBuffer[BigInt] = ListBuffer()

    // 1,...,N, 1,...,N
    for (j <- 0 until dupFacotr) {
      for (i <- 0 until uniquePageNum) {
        for (k <- 0 until pageSize/bytePerWord) {
          pgStrmDataSys0.append(uniquePgDataSys0(i*pageSize/bytePerWord+k))
        }
      }
    }
    for (j <- 0 until dupFacotr) {
      for (i <- 0 until uniquePageNum) {
        for (k <- 0 until pageSize/bytePerWord) {
          pgStrmDataSys1.append(uniquePgDataSys1(i*pageSize/bytePerWord+k))
        }
      }
    }

    val goldenPgRefCountAppearedSys0 = ListBuffer.tabulate[ListBuffer[Boolean]](uniquePageNum){idx => 
      ListBuffer.tabulate[Boolean](dupFacotr){idx => 
        false
      }
    }
    val goldenPgRefCountAppeared2Sys0 = ListBuffer.tabulate[ListBuffer[Boolean]](uniquePageNum){idx => 
      ListBuffer.tabulate[Boolean](dupFacotr){idx => 
        false
      }
    }
    val goldenPgIdxSys0 = List.tabulate[BigInt](pageNum){idx => ((idx/pagePerOp)) * pagePerOp + idx} // start + idx

    val goldenPgRefCountAppearedSys1 = ListBuffer.tabulate[ListBuffer[Boolean]](uniquePageNum){idx => 
      ListBuffer.tabulate[Boolean](dupFacotr){idx => 
        false
      }
    }
    val goldenPgRefCountAppeared2Sys1 = ListBuffer.tabulate[ListBuffer[Boolean]](uniquePageNum){idx => 
      ListBuffer.tabulate[Boolean](dupFacotr){idx => 
        false
      }
    }
    val goldenPgIdxSys1 = List.tabulate[BigInt](pageNum){idx => ((idx/pagePerOp)) * pagePerOp + idx} // start + idx

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

    // merge page stream and operation stream
    val inputBufferUsefulLen = pageNum * pageSize / bytePerWord + opNum * 1
    // round up to 64 word
    val inputBufferTotalLen = if (inputBufferUsefulLen % 64 == 0) {inputBufferUsefulLen} else {(inputBufferUsefulLen/64 + 1) *64}
    val inputBufferPaddingLen = inputBufferTotalLen - inputBufferUsefulLen

    // sys 0 input data
    val dedupSysInputDataSys0: mutable.Queue[BigInt] = mutable.Queue()
    var pgStartSys0 = 0
    for (opIdx <- 0 until opStrmDataSys0.length){
      val currentInstr = opStrmDataSys0(opIdx)
      val opCode = SimHelpers.bigIntTruncVal(currentInstr, 511, 510)
      dedupSysInputDataSys0.enqueue(currentInstr)
      if (opCode == 1){
        // write, get data
        for(pgIdx <- pgStartSys0 until (pgStartSys0 + pagePerOp)){
          for (k <- 0 until pageSize / bytePerWord) {
            dedupSysInputDataSys0.enqueue(pgStrmDataSys0(pgIdx * pageSize / bytePerWord + k))
          }
        }
        pgStartSys0 = pgStartSys0 + pagePerOp
      }
    }
    for (padIdx <- 0 until inputBufferPaddingLen){
      dedupSysInputDataSys0.enqueue(DedupCoreSimHelpers.nopGen())
    }

    // sys 1 input data
    val dedupSysInputDataSys1: mutable.Queue[BigInt] = mutable.Queue()
    var pgStartSys1 = 0
    for (opIdx <- 0 until opStrmDataSys1.length){
      val currentInstr = opStrmDataSys1(opIdx)
      val opCode = SimHelpers.bigIntTruncVal(currentInstr, 511, 510)
      dedupSysInputDataSys1.enqueue(currentInstr)
      if (opCode == 1){
        // write, get data
        for(pgIdx <- pgStartSys1 until (pgStartSys1 + pagePerOp)){
          for (k <- 0 until pageSize / bytePerWord) {
            dedupSysInputDataSys1.enqueue(pgStrmDataSys1(pgIdx * pageSize / bytePerWord + k))
          }
        }
        pgStartSys1 = pgStartSys1 + pagePerOp
      }
    }
    for (padIdx <- 0 until inputBufferPaddingLen){
      dedupSysInputDataSys1.enqueue(DedupCoreSimHelpers.nopGen())
    }
    
    val pgRespQSys0: mutable.Queue[BigInt] = mutable.Queue()
    val pgRespQSys1: mutable.Queue[BigInt] = mutable.Queue()
    println(s"Total input buffer size board 0: ${dedupSysInputDataSys0.size}")
    println(s"Total input buffer size board 1: ${dedupSysInputDataSys1.size}")
    // host model
    hostModel(dut.clockDomain, dut.io.board_0_hostd, dedupSysInputDataSys0, pgRespQSys0, pageNum)
    hostModel(dut.clockDomain, dut.io.board_1_hostd, dedupSysInputDataSys1, pgRespQSys1, pageNum)
    // network model
    val qpn0 = 123
    val qpn1 = 456
    networkModel2Ends(dut.clockDomain, List(dut.io.board_0_networkIo, dut.io.board_1_networkIo), List(qpn0, qpn1))

    /** AXIL Control Reg */
    setAxi4LiteReg(dut.clockDomain, dut.io.board_0_axi_ctrl, 0, 1) // INITEN
    setAxi4LiteReg(dut.clockDomain, dut.io.board_0_axi_ctrl, 3<<3, 0) // RDHOSTADDR
    setAxi4LiteReg(dut.clockDomain, dut.io.board_0_axi_ctrl, 4<<3, 0x1000) // WRHOSTADDR
    setAxi4LiteReg(dut.clockDomain, dut.io.board_0_axi_ctrl, 5<<3, 4096) // LEN
    setAxi4LiteReg(dut.clockDomain, dut.io.board_0_axi_ctrl, 6<<3, inputBufferTotalLen/64) // CNT = pageNum
    setAxi4LiteReg(dut.clockDomain, dut.io.board_0_axi_ctrl, 7<<3, 0) // PID
    setAxi4LiteReg(dut.clockDomain, dut.io.board_0_axi_ctrl, 10 << 3, 8) // factorThrou

    setAxi4LiteReg(dut.clockDomain, dut.io.board_1_axi_ctrl, 0, 1) // INITEN
    setAxi4LiteReg(dut.clockDomain, dut.io.board_1_axi_ctrl, 3<<3, 0) // RDHOSTADDR
    setAxi4LiteReg(dut.clockDomain, dut.io.board_1_axi_ctrl, 4<<3, 0x1000) // WRHOSTADDR
    setAxi4LiteReg(dut.clockDomain, dut.io.board_1_axi_ctrl, 5<<3, 4096) // LEN
    setAxi4LiteReg(dut.clockDomain, dut.io.board_1_axi_ctrl, 6<<3, inputBufferTotalLen/64) // CNT = pageNum
    setAxi4LiteReg(dut.clockDomain, dut.io.board_1_axi_ctrl, 7<<3, 0) // PID
    setAxi4LiteReg(dut.clockDomain, dut.io.board_1_axi_ctrl, 10 << 3, 8) // factorThrou
    // init routing table
    val simNode0Idx = 3
    val simNode1Idx = 7
    val conf = DedupCoreSimHelpers.dualCoreConf
    setAxi4LiteReg(dut.clockDomain, dut.io.board_0_axi_ctrl, 12 << 3, 2) // active channel count
    setAxi4LiteReg(dut.clockDomain, dut.io.board_1_axi_ctrl, 12 << 3, 2) // active channel count
    // board 0 
    // entry 0
    setAxi4LiteReg(dut.clockDomain, dut.io.board_0_axi_ctrl, 13 << 3, simNode0Idx) // node
    setAxi4LiteReg(dut.clockDomain, dut.io.board_0_axi_ctrl, 14 << 3, 0) // start
    setAxi4LiteReg(dut.clockDomain, dut.io.board_0_axi_ctrl, 15 << 3, 1 << (conf.rtConf.routingDecisionBits - 1)) // len
    // entry 1
    setAxi4LiteReg(dut.clockDomain, dut.io.board_0_axi_ctrl, 16 << 3, simNode1Idx) // node
    setAxi4LiteReg(dut.clockDomain, dut.io.board_0_axi_ctrl, 17 << 3, 1 << (conf.rtConf.routingDecisionBits - 1)) // start
    setAxi4LiteReg(dut.clockDomain, dut.io.board_0_axi_ctrl, 18 << 3, 1 << (conf.rtConf.routingDecisionBits - 1)) // len
    // qpn
    setAxi4LiteReg(dut.clockDomain, dut.io.board_0_axi_ctrl, 37 << 3, qpn1) // node
    setAxi4LiteReg(dut.clockDomain, dut.io.board_0_axi_ctrl, 11 << 3, 1)

    // board 1
    // entry 0
    setAxi4LiteReg(dut.clockDomain, dut.io.board_1_axi_ctrl, 13 << 3, simNode1Idx) // node
    setAxi4LiteReg(dut.clockDomain, dut.io.board_1_axi_ctrl, 14 << 3, 1 << (conf.rtConf.routingDecisionBits - 1)) // start
    setAxi4LiteReg(dut.clockDomain, dut.io.board_1_axi_ctrl, 15 << 3, 1 << (conf.rtConf.routingDecisionBits - 1)) // len
    // entry 1
    setAxi4LiteReg(dut.clockDomain, dut.io.board_1_axi_ctrl, 16 << 3, simNode0Idx) // node
    setAxi4LiteReg(dut.clockDomain, dut.io.board_1_axi_ctrl, 17 << 3, 0) // start
    setAxi4LiteReg(dut.clockDomain, dut.io.board_1_axi_ctrl, 18 << 3, 1 << (conf.rtConf.routingDecisionBits - 1)) // len
    // qpn
    setAxi4LiteReg(dut.clockDomain, dut.io.board_1_axi_ctrl, 37 << 3, qpn0) // node
    setAxi4LiteReg(dut.clockDomain, dut.io.board_1_axi_ctrl, 11 << 3, 1)

    // confirm initDone
    while(readAxi4LiteReg(dut.clockDomain, dut.io.board_0_axi_ctrl, 1<<3) == 0) {
      dut.clockDomain.waitSampling(10)
    }
    while(readAxi4LiteReg(dut.clockDomain, dut.io.board_1_axi_ctrl, 1<<3) == 0) {
      dut.clockDomain.waitSampling(10)
    }
    // set start
    setAxi4LiteReg(dut.clockDomain, dut.io.board_0_axi_ctrl, 2<<3, 1)
    setAxi4LiteReg(dut.clockDomain, dut.io.board_1_axi_ctrl, 2<<3, 1)

    while(dedupSysInputDataSys0.nonEmpty) {
      dut.clockDomain.waitSampling(10)
    }
    while(dedupSysInputDataSys1.nonEmpty) {
      dut.clockDomain.waitSampling(10)
    }

    // wait for tail pages processing
    while (readAxi4LiteReg(dut.clockDomain, dut.io.board_0_axi_ctrl, 9 << 3) < pageNum/16) {
      dut.clockDomain.waitSampling(100)
    }
    val rdDone0 = readAxi4LiteReg(dut.clockDomain, dut.io.board_0_axi_ctrl, 8<<3)
    val wrDone0 = readAxi4LiteReg(dut.clockDomain, dut.io.board_0_axi_ctrl, 9<<3)
    println(s"Board 0: rdDone: $rdDone0/${inputBufferTotalLen/64}, wrDone: $wrDone0/${pageNum/16}")

    while (readAxi4LiteReg(dut.clockDomain, dut.io.board_1_axi_ctrl, 9 << 3) < pageNum/16) {
      dut.clockDomain.waitSampling(100)
    }
    val rdDone1 = readAxi4LiteReg(dut.clockDomain, dut.io.board_1_axi_ctrl, 8<<3)
    val wrDone1 = readAxi4LiteReg(dut.clockDomain, dut.io.board_1_axi_ctrl, 9<<3)
    println(s"Board 1: rdDone: $rdDone1/${inputBufferTotalLen/64}, wrDone: $wrDone1/${pageNum/16}")

    // parse the pgRespQ
    val dedupSysInputData1p5Sys0: mutable.Queue[BigInt] = mutable.Queue()
    val dedupSysInputData2Sys0: mutable.Queue[BigInt] = mutable.Queue()
    val uniquePageSha3Sys0: mutable.ListBuffer[BigInt] = ListBuffer()

    var respIdx0 = 0
    while(pgRespQSys0.nonEmpty) {
      val respData = pgRespQSys0.dequeue()
      val sha3Hash = DedupCoreSimHelpers.dualDedupSysDecodeAndCheck(respData = respData,
                                                                    uniquePageIdx = respIdx0 % uniquePageNum,
                                                                    goldenPgRefCountAppeared = goldenPgRefCountAppeared2Sys0,
                                                                    goldenPgIdx =  goldenPgIdxSys0(respIdx0),
                                                                    goldenOpCode = 1,
                                                                    checkSha3 = false,
                                                                    goldenSha3  = 0)

      // val bitOffset = SimHelpers.BitOffset()
      // bitOffset.next(conf.htConf.hashValWidth)
      // val SHA3Hash     = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
      // bitOffset.next(conf.htConf.ptrWidth)
      // val RefCount     = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
      // bitOffset.next(conf.htConf.ptrWidth)
      // val SSDLBA       = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
      // bitOffset.next(conf.nodeIdxWidth)
      // val nodeIdx      = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
      // bitOffset.next(conf.htConf.ptrWidth)
      // val hostLBAStart = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
      // bitOffset.next(conf.htConf.ptrWidth)
      // val hostLBALen   = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
      // val isExec       = SimHelpers.bigIntTruncVal(respData, 509, 509)
      // val opCode       = SimHelpers.bigIntTruncVal(respData, 511, 510)
      
      // assert(RefCount == goldenpgRefCount(respIdx))
      // assert(hostLBAStart == goldenPgIdx(respIdx))
      // assert(hostLBALen == 1)
      // assert(isExec == goldenPgIsNew(respIdx))
      // assert(opCode == 1)
      if (respIdx0 < uniquePageNum){
        dedupSysInputData1p5Sys0.enqueue(DedupCoreSimHelpers.readInstrGen(sha3Hash))
        uniquePageSha3Sys0.append(sha3Hash)
      }

      dedupSysInputData2Sys0.enqueue(DedupCoreSimHelpers.eraseInstrGen(sha3Hash))

      // println(s"pageIdx: ${respIdx}")
      // println(s"${RefCount} == ${goldenpgRefCount(respIdx)}")
      // println(s"${hostLBAStart} == ${goldenPgIdx(respIdx)}")
      // println(s"${hostLBALen} == 1")
      // println(s"${isExec} == ${goldenPgIsNew(respIdx)}")
      // println(s"${opCode} == 0")
      respIdx0 = respIdx0 + 1
    }
    assert(respIdx0 == pageNum)
    assert(pgRespQSys0.isEmpty, "Ah???")

    val dedupSysInputData1p5Sys1: mutable.Queue[BigInt] = mutable.Queue()
    val dedupSysInputData2Sys1: mutable.Queue[BigInt] = mutable.Queue()
    val uniquePageSha3Sys1: mutable.ListBuffer[BigInt] = ListBuffer()

    var respIdx1 = 0
    while(pgRespQSys1.nonEmpty) {
      val respData = pgRespQSys1.dequeue()
      val sha3Hash = DedupCoreSimHelpers.dualDedupSysDecodeAndCheck(respData = respData,
                                                                    uniquePageIdx = respIdx1 % uniquePageNum,
                                                                    goldenPgRefCountAppeared = goldenPgRefCountAppeared2Sys1,
                                                                    goldenPgIdx =  goldenPgIdxSys1(respIdx1),
                                                                    goldenOpCode = 1,
                                                                    checkSha3 = false,
                                                                    goldenSha3  = 0)

      // val bitOffset = SimHelpers.BitOffset()
      // bitOffset.next(conf.htConf.hashValWidth)
      // val SHA3Hash     = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
      // bitOffset.next(conf.htConf.ptrWidth)
      // val RefCount     = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
      // bitOffset.next(conf.htConf.ptrWidth)
      // val SSDLBA       = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
      // bitOffset.next(conf.nodeIdxWidth)
      // val nodeIdx      = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
      // bitOffset.next(conf.htConf.ptrWidth)
      // val hostLBAStart = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
      // bitOffset.next(conf.htConf.ptrWidth)
      // val hostLBALen   = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
      // val isExec       = SimHelpers.bigIntTruncVal(respData, 509, 509)
      // val opCode       = SimHelpers.bigIntTruncVal(respData, 511, 510)
      
      // assert(RefCount == goldenpgRefCount(respIdx))
      // assert(hostLBAStart == goldenPgIdx(respIdx))
      // assert(hostLBALen == 1)
      // assert(isExec == goldenPgIsNew(respIdx))
      // assert(opCode == 1)
      if (respIdx1 < uniquePageNum){
        dedupSysInputData1p5Sys1.enqueue(DedupCoreSimHelpers.readInstrGen(sha3Hash))
        uniquePageSha3Sys1.append(sha3Hash)
      }

      dedupSysInputData2Sys1.enqueue(DedupCoreSimHelpers.eraseInstrGen(sha3Hash))

      // println(s"pageIdx: ${respIdx}")
      // println(s"${RefCount} == ${goldenpgRefCount(respIdx)}")
      // println(s"${hostLBAStart} == ${goldenPgIdx(respIdx)}")
      // println(s"${hostLBALen} == 1")
      // println(s"${isExec} == ${goldenPgIsNew(respIdx)}")
      // println(s"${opCode} == 0")
      respIdx1 = respIdx1 + 1
    }
    assert(respIdx1 == pageNum)
    assert(pgRespQSys1.isEmpty, "Ah???")

    

    // // read all
    // println("read all")
    // val inputBufferUsefulLen1p5 = uniquePageNum
    // val inputBufferTotalLen1p5  = ((inputBufferUsefulLen1p5 + 63)/ 64)*64
    // val inputBufferPaddingLen1p5 = inputBufferTotalLen1p5 - inputBufferUsefulLen1p5
    // for (opIdx <- 0 until inputBufferPaddingLen1p5){
    //   dedupSysInputData1p5.enqueue(DedupCoreSimHelpers.nopGen())
    // }
    
    // val pgRespQ1p5: mutable.Queue[BigInt] = mutable.Queue()
    // println(s"read data len = ${dedupSysInputData1p5.length} words")
    // hostModel(dut.clockDomain, dut.io.hostd, dedupSysInputData1p5, pgRespQ1p5, uniquePageNum)
    // // for (opIdx <- 0 until inputBufferTotalLen1p5){
    // //   dedupSysInputData.enqueue(dedupSysInputData.dequeue())
    // // }

    // setAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 6<<3, inputBufferTotalLen1p5/64) // CNT = pageNum
    // setAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 2<<3, 1)

    // while(dedupSysInputData.nonEmpty) {
    //   dut.clockDomain.waitSampling(10)
    // }
    // // wait for tail pages processing
    // while (readAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 9 << 3) < uniquePageNum/16) {
    //   dut.clockDomain.waitSampling(100)
    // }

    // val rdDone1p5 = readAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 8<<3)
    // val wrDone1p5 = readAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 9<<3)
    // println(s"rdDone: $rdDone1p5, wrDone: $wrDone1p5")

    // var respIdx1p5 = 0
    // while(pgRespQ1p5.nonEmpty) {
    //   val respData     = pgRespQ1p5.dequeue()
    //   val bitOffset = SimHelpers.BitOffset()
    //   bitOffset.next(conf.htConf.hashValWidth)
    //   val sha3Hash     = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
    //   bitOffset.next(conf.htConf.ptrWidth)
    //   val RefCount     = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
    //   bitOffset.next(conf.htConf.ptrWidth)
    //   val SSDLBA       = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
    //   bitOffset.next(32)
    //   val nodeIdx      = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
    //   bitOffset.next(conf.htConf.ptrWidth)
    //   val hostLBAStart = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
    //   bitOffset.next(conf.htConf.ptrWidth)
    //   val hostLBALen   = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
    //   val isExec       = SimHelpers.bigIntTruncVal(respData, 509, 509)
    //   val opCode       = SimHelpers.bigIntTruncVal(respData, 511, 510)
      
    //   assert(sha3Hash == uniquePageSha3(respIdx1p5))
    //   assert(RefCount == dupFacotr)
    //   assert(nodeIdx == simNodeIdx)
    //   assert(hostLBAStart == 0)
    //   assert(hostLBALen == 1)
    //   assert(isExec == 1)
    //   assert(opCode == 3)

    //   // println(s"pageIdx: ${respIdx1p5}")
    //   // println(s"${RefCount} == ${goldenpgRefCount(respIdx1p5)}")
    //   // println(s"${hostLBAStart} == 0")
    //   // println(s"${hostLBALen} == 1")
    //   // println(s"${isExec} == 1")
    //   // println(s"${opCode} == 3")
    //   respIdx1p5 = respIdx1p5 + 1
    // }
    // assert(respIdx1p5 == uniquePageNum)
    // assert(pgRespQ1p5.isEmpty, "Ah???")

    // // erase all
    // println("clean all")
    // val inputBufferUsefulLen2 = pageNum
    // val inputBufferTotalLen2  = ((inputBufferUsefulLen2 + 63)/ 64)*64
    // val inputBufferPaddingLen2 = inputBufferTotalLen2 - inputBufferUsefulLen2
    // for (opIdx <- 0 until inputBufferPaddingLen2){
    //   dedupSysInputData2.enqueue(DedupCoreSimHelpers.nopGen())
    // }
    
    // val pgRespQ2: mutable.Queue[BigInt] = mutable.Queue()
    // println(s"clean data len = ${dedupSysInputData2.length} words")
    // hostModel(dut.clockDomain, dut.io.hostd, dedupSysInputData2, pgRespQ2, pageNum)
    // // for (opIdx <- 0 until inputBufferTotalLen2){
    // //   dedupSysInputData.enqueue(dedupSysInputData.dequeue())
    // // }

    // setAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 6<<3, inputBufferTotalLen2/64) // CNT = pageNum
    // setAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 2<<3, 1)

    // while(dedupSysInputData.nonEmpty) {
    //   dut.clockDomain.waitSampling(10)
    // }
    // // wait for tail pages processing
    // while (readAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 9 << 3) < pageNum/16) {
    //   dut.clockDomain.waitSampling(100)
    // }

    // val rdDone2 = readAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 8<<3)
    // val wrDone2 = readAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 9<<3)
    // println(s"rdDone: $rdDone2, wrDone: $wrDone2")

    // var respIdx2 = 0
    // while(pgRespQ2.nonEmpty) {
    //   val respData = pgRespQ2.dequeue()
    //   val sha3Hash = DedupCoreSimHelpers.dualDedupSysDecodeAndCheck(respData = respData,
    //                                                                   uniquePageIdx = respIdx2 % uniquePageNum,
    //                                                                   goldenPgRefCountAppeared = goldenPgRefCountAppeared2,
    //                                                                   goldenNodeIdx = simNodeIdx,
    //                                                                   goldenPgIdx = 0,
    //                                                                   goldenOpCode = 2,
    //                                                                   checkSha3 = true,
    //                                                                   goldenSha3 = uniquePageSha3(respIdx2 % uniquePageNum))
    //   // val SHA3Hash     = SimHelpers.bigIntTruncVal(respData, 255, 0)
    //   // val RefCount     = SimHelpers.bigIntTruncVal(respData, 287, 256)
    //   // val SSDLBA       = SimHelpers.bigIntTruncVal(respData, 319, 288)
    //   // val hostLBAStart = SimHelpers.bigIntTruncVal(respData, 351, 320)
    //   // val hostLBALen   = SimHelpers.bigIntTruncVal(respData, 383, 352)
    //   // val isExec       = SimHelpers.bigIntTruncVal(respData, 509, 509)
    //   // val opCode       = SimHelpers.bigIntTruncVal(respData, 511, 510)
      
    //   // assert(SHA3Hash == uniquePageSha3(respIdx2))
    //   // assert(RefCount == dupFacotr - 1)
    //   // assert(hostLBAStart == 0)
    //   // assert(hostLBALen == 1)
    //   // assert(isExec == { if (dupFacotr == 1) 1 else 0})
    //   // assert(opCode == 2)

    //   // println(s"pageIdx: ${respIdx2}")
    //   // println(s"${RefCount} == ${goldenpgRefCount(respIdx2)}")
    //   // println(s"${hostLBAStart} == ${goldenPgIdx(respIdx2)}")
    //   // println(s"${hostLBALen} == 1")
    //   // println(s"${isExec} == ${{ if (dupFacotr == 1) 1 else 0}}")
    //   // println(s"${opCode} == 0")
    //   respIdx2 = respIdx2 + 1
    // }
    // assert(respIdx2 == pageNum)
    // assert(pgRespQ2.isEmpty, "Ah???")
  }
}

case class DualDedupSysTB() extends Component{

  val conf = DedupCoreSimHelpers.dualCoreConf

  val io = new Bundle {
    // board 0
    val board_0_axi_ctrl  = slave(AxiLite4(AxiLite4Config(64, 64)))
    val board_0_hostd     = new HostDataIO
    val board_0_networkIo = new NetworkDataIO
    val board_0_axi_mem   = master(Axi4(Axi4ConfigAlveo.u55cHBM))

    // board 1
    val board_1_axi_ctrl  = slave(AxiLite4(AxiLite4Config(64, 64)))
    val board_1_hostd     = new HostDataIO
    val board_1_networkIo = new NetworkDataIO
    val board_1_axi_mem   = master(Axi4(Axi4ConfigAlveo.u55cHBM))
  }

  val dedupSys0 = new WrapDedupSys(conf)
  val dedupSys1 = new WrapDedupSys(conf)

  // auto connect
  dedupSys0.io.axi_ctrl  <> io.board_0_axi_ctrl
  dedupSys0.io.hostd     <> io.board_0_hostd
  dedupSys0.io.networkIo <> io.board_0_networkIo

  dedupSys1.io.axi_ctrl  <> io.board_1_axi_ctrl
  dedupSys1.io.hostd     <> io.board_1_hostd
  dedupSys1.io.networkIo <> io.board_1_networkIo

  // axi mux, RR arbitration
  val axiMux0 = AxiMux(conf.htConf.sizeFSMArray + 1)
  for (idx <- 0 until conf.htConf.sizeFSMArray + 1){
    axiMux0.io.axiIn(idx) << dedupSys0.io.axi_mem(idx)
  }
  axiMux0.io.axiOut >> io.board_0_axi_mem

  val axiMux1 = AxiMux(conf.htConf.sizeFSMArray + 1)
  for (idx <- 0 until conf.htConf.sizeFSMArray + 1){
    axiMux1.io.axiIn(idx) << dedupSys1.io.axi_mem(idx)
  }
  axiMux1.io.axiOut >> io.board_1_axi_mem
}