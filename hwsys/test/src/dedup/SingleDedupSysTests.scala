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

class SingleDedupSysTests extends AnyFunSuite {
  def singleDedupSysSim(): Unit = {

    val compiledRTL = if (sys.env.contains("VCS_HOME")) SimConfig.withVpdWave.withVCS.compile(new SingleDedupSysTB())
    else SimConfig.withWave.compile(new SingleDedupSysTB())

    compiledRTL.doSim { dut =>
      SingleDedupSysSim.doSim(dut)
    }
  }

  test("Single DedupSys Test"){
    singleDedupSysSim()
  }
}


object SingleDedupSysSim {

  def doSim(dut: SingleDedupSysTB, verbose: Boolean = false): Unit = {
    dut.clockDomain.forkStimulus(period = 2)
    SimTimeout(1000000)
    /** init */
    initAxi4LiteBus(dut.io.axi_ctrl)
    dut.io.hostd.axis_host_sink.valid #= false
    dut.io.hostd.bpss_wr_done.valid   #= false
    dut.io.hostd.bpss_rd_done.valid   #= false
    dut.io.hostd.axis_host_src.ready  #= false
    dut.io.hostd.bpss_wr_req.ready    #= false
    dut.io.hostd.bpss_rd_req.ready    #= false
    dut.io.networkIo.remoteRecvStrmIn.valid  #= false
    dut.io.networkIo.remoteSendStrmOut.ready #= false

    /** memory model for HashTab */
    SimDriver.instAxiMemSim(dut.io.axi_mem, dut.clockDomain, None)
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
    val uniquePgData = List.tabulate[BigInt](uniquePageNum*pageSize/bytePerWord){idx => idx/(pageSize/bytePerWord)}
    
    val opStrmData: ListBuffer[BigInt] = ListBuffer()
    for (i <- 0 until opNum) {
      val newInstr = DedupCoreSimHelpers.writeInstrGen(2*i*pagePerOp,pagePerOp)
      opStrmData.append(newInstr)
    }

    var pgStrmData: ListBuffer[BigInt] = ListBuffer()

    // 1,...,N, 1,...,N
    for (j <- 0 until dupFacotr) {
      for (i <- 0 until uniquePageNum) {
        for (k <- 0 until pageSize/bytePerWord) {
          pgStrmData.append(uniquePgData(i*pageSize/bytePerWord+k))
        }
      }
    }

    val goldenPgRefCountAppeared = ListBuffer.tabulate[ListBuffer[Boolean]](uniquePageNum){idx => 
      ListBuffer.tabulate[Boolean](dupFacotr){idx => 
        false
      }
    }
    val goldenPgRefCountAppeared2 = ListBuffer.tabulate[ListBuffer[Boolean]](uniquePageNum){idx => 
      ListBuffer.tabulate[Boolean](dupFacotr){idx => 
        false
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

    // merge page stream and operation stream
    val inputBufferUsefulLen = pageNum * pageSize / bytePerWord + opNum * 1
    // round up to 64 word
    val inputBufferTotalLen = if (inputBufferUsefulLen % 64 == 0) {inputBufferUsefulLen} else {(inputBufferUsefulLen/64 + 1) *64}
    val inputBufferPaddingLen = inputBufferTotalLen - inputBufferUsefulLen

    val dedupSysInputData: mutable.Queue[BigInt] = mutable.Queue()
    var pgStart = 0
    for (opIdx <- 0 until opStrmData.length){
      val currentInstr = opStrmData(opIdx)
      val opCode = SimHelpers.bigIntTruncVal(currentInstr, 511, 510)
      dedupSysInputData.enqueue(currentInstr)
      if (opCode == 1){
        // write, get data
        for(pgIdx <- pgStart until (pgStart + pagePerOp)){
          for (k <- 0 until pageSize / bytePerWord) {
            dedupSysInputData.enqueue(pgStrmData(pgIdx * pageSize / bytePerWord + k))
          }
        }
        pgStart = pgStart + pagePerOp
      }
    }
    for (padIdx <- 0 until inputBufferPaddingLen){
      dedupSysInputData.enqueue(DedupCoreSimHelpers.nopGen())
    }
    
    val pgRespQ: mutable.Queue[BigInt] = mutable.Queue()
    println(s"Total input buffer size: ${dedupSysInputData.size}")
    // host model
    hostModel(dut.clockDomain, dut.io.hostd, dedupSysInputData, pgRespQ, pageNum)

    /** AXIL Control Reg */
    setAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 0, 1) // INITEN

    setAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 3<<3, 0) // RDHOSTADDR
    setAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 4<<3, 0x1000) // WRHOSTADDR
    setAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 5<<3, 4096) // LEN
    setAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 6<<3, inputBufferTotalLen/64) // CNT = pageNum
    setAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 7<<3, 0) // PID
    // init routing table
    val simNodeIdx = 3
    val conf = DedupCoreSimHelpers.singleCoreConf
    setAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 11 << 3, 1) // active channel count
    for (index <- 0 until conf.rtConf.routingChannelCount){
      setAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, (12 + 3 * index) << 3, {if (index == 0) simNodeIdx else (simNodeIdx + 1)}) // node
      setAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, (13 + 3 * index) << 3, 0) // start
      setAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, (14 + 3 * index) << 3, {if (index == 0) (1 << conf.rtConf.routingDecisionBits) else 0}) // len
    }
    setAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 10 << 3, 1)
    setAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, (8 + 4 + 3 * conf.rtConf.routingChannelCount) << 3, 8) // factorThrou

    // confirm initDone
    while(readAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 1<<3) == 0) {
      dut.clockDomain.waitSampling(10)
    }
    // set start
    setAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 2<<3, 1)

    while(dedupSysInputData.nonEmpty) {
      dut.clockDomain.waitSampling(10)
    }
    // wait for tail pages processing
    while (readAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 9 << 3) < pageNum/16) {
      dut.clockDomain.waitSampling(100)
    }

    val rdDone = readAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 8<<3)
    val wrDone = readAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 9<<3)
    println(s"rdDone: $rdDone, wrDone: $wrDone")

    // parse the pgRespQ
    val dedupSysInputData1p5: mutable.Queue[BigInt] = mutable.Queue()
    val dedupSysInputData2: mutable.Queue[BigInt] = mutable.Queue()
    val uniquePageSha3: mutable.ListBuffer[BigInt] = ListBuffer()

    var respIdx = 0
    while(pgRespQ.nonEmpty) {
      val respData = pgRespQ.dequeue()
      val sha3Hash = DedupCoreSimHelpers.singleDedupSysDecodeAndCheck(respData = respData,
                                                                      uniquePageIdx = respIdx % uniquePageNum,
                                                                      goldenPgRefCountAppeared = goldenPgRefCountAppeared2,
                                                                      goldenNodeIdx = simNodeIdx,
                                                                      goldenPgIdx =  goldenPgIdx(respIdx),
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
      if (respIdx < uniquePageNum){
        dedupSysInputData1p5.enqueue(DedupCoreSimHelpers.readInstrGen(sha3Hash))
        uniquePageSha3.append(sha3Hash)
      }

      dedupSysInputData2.enqueue(DedupCoreSimHelpers.eraseInstrGen(sha3Hash))

      // println(s"pageIdx: ${respIdx}")
      // println(s"${RefCount} == ${goldenpgRefCount(respIdx)}")
      // println(s"${hostLBAStart} == ${goldenPgIdx(respIdx)}")
      // println(s"${hostLBALen} == 1")
      // println(s"${isExec} == ${goldenPgIsNew(respIdx)}")
      // println(s"${opCode} == 0")
      respIdx = respIdx + 1
    }
    assert(respIdx == pageNum)
    assert(pgRespQ.isEmpty, "Ah???")

    // read all
    println("read all")
    val inputBufferUsefulLen1p5 = uniquePageNum
    val inputBufferTotalLen1p5  = ((inputBufferUsefulLen1p5 + 63)/ 64)*64
    val inputBufferPaddingLen1p5 = inputBufferTotalLen1p5 - inputBufferUsefulLen1p5
    for (opIdx <- 0 until inputBufferPaddingLen1p5){
      dedupSysInputData1p5.enqueue(DedupCoreSimHelpers.nopGen())
    }
    
    val pgRespQ1p5: mutable.Queue[BigInt] = mutable.Queue()
    println(s"read data len = ${dedupSysInputData1p5.length} words")
    hostModel(dut.clockDomain, dut.io.hostd, dedupSysInputData1p5, pgRespQ1p5, uniquePageNum)
    // for (opIdx <- 0 until inputBufferTotalLen1p5){
    //   dedupSysInputData.enqueue(dedupSysInputData.dequeue())
    // }

    setAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 6<<3, inputBufferTotalLen1p5/64) // CNT = pageNum
    setAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 2<<3, 1)

    while(dedupSysInputData.nonEmpty) {
      dut.clockDomain.waitSampling(10)
    }
    // wait for tail pages processing
    while (readAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 9 << 3) < uniquePageNum/16) {
      dut.clockDomain.waitSampling(100)
    }

    val rdDone1p5 = readAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 8<<3)
    val wrDone1p5 = readAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 9<<3)
    println(s"rdDone: $rdDone1p5, wrDone: $wrDone1p5")

    var respIdx1p5 = 0
    while(pgRespQ1p5.nonEmpty) {
      val respData     = pgRespQ1p5.dequeue()
      val bitOffset = SimHelpers.BitOffset()
      bitOffset.next(conf.htConf.hashValWidth)
      val sha3Hash     = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
      bitOffset.next(conf.htConf.ptrWidth)
      val RefCount     = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
      bitOffset.next(conf.htConf.ptrWidth)
      val SSDLBA       = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
      bitOffset.next(32)
      val nodeIdx      = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
      bitOffset.next(conf.htConf.ptrWidth)
      val hostLBAStart = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
      bitOffset.next(conf.htConf.ptrWidth)
      val hostLBALen   = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
      val isExec       = SimHelpers.bigIntTruncVal(respData, 509, 509)
      val opCode       = SimHelpers.bigIntTruncVal(respData, 511, 510)
      
      assert(sha3Hash == uniquePageSha3(respIdx1p5))
      assert(RefCount == dupFacotr)
      assert(nodeIdx == simNodeIdx)
      assert(hostLBAStart == 0)
      assert(hostLBALen == 1)
      assert(isExec == 1)
      assert(opCode == 3)

      // println(s"pageIdx: ${respIdx1p5}")
      // println(s"${RefCount} == ${goldenpgRefCount(respIdx1p5)}")
      // println(s"${hostLBAStart} == 0")
      // println(s"${hostLBALen} == 1")
      // println(s"${isExec} == 1")
      // println(s"${opCode} == 3")
      respIdx1p5 = respIdx1p5 + 1
    }
    assert(respIdx1p5 == uniquePageNum)
    assert(pgRespQ1p5.isEmpty, "Ah???")

    // erase all
    println("clean all")
    val inputBufferUsefulLen2 = pageNum
    val inputBufferTotalLen2  = ((inputBufferUsefulLen2 + 63)/ 64)*64
    val inputBufferPaddingLen2 = inputBufferTotalLen2 - inputBufferUsefulLen2
    for (opIdx <- 0 until inputBufferPaddingLen2){
      dedupSysInputData2.enqueue(DedupCoreSimHelpers.nopGen())
    }
    
    val pgRespQ2: mutable.Queue[BigInt] = mutable.Queue()
    println(s"clean data len = ${dedupSysInputData2.length} words")
    hostModel(dut.clockDomain, dut.io.hostd, dedupSysInputData2, pgRespQ2, pageNum)
    // for (opIdx <- 0 until inputBufferTotalLen2){
    //   dedupSysInputData.enqueue(dedupSysInputData.dequeue())
    // }

    setAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 6<<3, inputBufferTotalLen2/64) // CNT = pageNum
    setAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 2<<3, 1)

    while(dedupSysInputData.nonEmpty) {
      dut.clockDomain.waitSampling(10)
    }
    // wait for tail pages processing
    while (readAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 9 << 3) < pageNum/16) {
      dut.clockDomain.waitSampling(100)
    }

    val rdDone2 = readAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 8<<3)
    val wrDone2 = readAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 9<<3)
    println(s"rdDone: $rdDone2, wrDone: $wrDone2")

    var respIdx2 = 0
    while(pgRespQ2.nonEmpty) {
      val respData = pgRespQ2.dequeue()
      val sha3Hash = DedupCoreSimHelpers.singleDedupSysDecodeAndCheck(respData = respData,
                                                                      uniquePageIdx = respIdx2 % uniquePageNum,
                                                                      goldenPgRefCountAppeared = goldenPgRefCountAppeared2,
                                                                      goldenNodeIdx = simNodeIdx,
                                                                      goldenPgIdx = 0,
                                                                      goldenOpCode = 2,
                                                                      checkSha3 = true,
                                                                      goldenSha3 = uniquePageSha3(respIdx2 % uniquePageNum))
      // val SHA3Hash     = SimHelpers.bigIntTruncVal(respData, 255, 0)
      // val RefCount     = SimHelpers.bigIntTruncVal(respData, 287, 256)
      // val SSDLBA       = SimHelpers.bigIntTruncVal(respData, 319, 288)
      // val hostLBAStart = SimHelpers.bigIntTruncVal(respData, 351, 320)
      // val hostLBALen   = SimHelpers.bigIntTruncVal(respData, 383, 352)
      // val isExec       = SimHelpers.bigIntTruncVal(respData, 509, 509)
      // val opCode       = SimHelpers.bigIntTruncVal(respData, 511, 510)
      
      // assert(SHA3Hash == uniquePageSha3(respIdx2))
      // assert(RefCount == dupFacotr - 1)
      // assert(hostLBAStart == 0)
      // assert(hostLBALen == 1)
      // assert(isExec == { if (dupFacotr == 1) 1 else 0})
      // assert(opCode == 2)

      // println(s"pageIdx: ${respIdx2}")
      // println(s"${RefCount} == ${goldenpgRefCount(respIdx2)}")
      // println(s"${hostLBAStart} == ${goldenPgIdx(respIdx2)}")
      // println(s"${hostLBALen} == 1")
      // println(s"${isExec} == ${{ if (dupFacotr == 1) 1 else 0}}")
      // println(s"${opCode} == 0")
      respIdx2 = respIdx2 + 1
    }
    assert(respIdx2 == pageNum)
    assert(pgRespQ2.isEmpty, "Ah???")
  }
}

case class SingleDedupSysTB() extends Component{

  val conf = DedupCoreSimHelpers.singleCoreConf

  val io = new Bundle {
    val axi_ctrl = slave(AxiLite4(AxiLite4Config(64, 64)))
    val hostd = new HostDataIO
    val networkIo = new NetworkDataIO
    val axi_mem = master(Axi4(Axi4ConfigAlveo.u55cHBM))
  }

  val dedupSys = new WrapDedupSys()

  // auto connect
  dedupSys.io.axi_ctrl  <> io.axi_ctrl
  dedupSys.io.hostd     <> io.hostd
  dedupSys.io.networkIo <> io.networkIo

  // axi mux, RR arbitration
  val axiMux = AxiMux(conf.htConf.sizeFSMArray + 1)
  for (idx <- 0 until conf.htConf.sizeFSMArray + 1){
    axiMux.io.axiIn(idx) << dedupSys.io.axi_mem(idx)
  }
  axiMux.io.axiOut >> io.axi_mem
}