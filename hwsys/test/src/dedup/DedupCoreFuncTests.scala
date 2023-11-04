package dedup

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._

import dedup.pagewriter.PageWriterResp
import dedup.pagewriter.SSDInstr

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import util.sim._
import util.sim.SimDriver._
import util.AxiMux

import scala.collection.mutable
import scala.collection.mutable._
import scala.util.Random

class DedupCoreFuncTests extends AnyFunSuite {
  def dedupCoreFuncSim(): Unit = {

    val compiledRTL = if (sys.env.contains("VCS_HOME")) SimConfig.withVpdWave.withVCS.compile(new WrapDedupCoreTB())
    else SimConfig.withWave.compile(new WrapDedupCoreTB())

    compiledRTL.doSim { dut =>
      DedupCoreFuncSim.doSim(dut)
    }
  }

  test("Core Func Test"){
    dedupCoreFuncSim()
  }
}


object DedupCoreFuncSim {

  def doSim(dut: WrapDedupCoreTB, verbose: Boolean = false): Unit = {
    dut.clockDomain.forkStimulus(period = 2)
    SimTimeout(1000000)
    dut.io.pgStrmIn.valid #= false
    dut.io.opStrmIn.valid #= false
    dut.io.initEn #=false
    dut.io.clearInitStatus #= false
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

    dut.io.factorThrou         #= 16
    dut.io.SSDDataIn .ready    #= true
    dut.io.SSDDataOut.valid    #= false
    dut.io.SSDDataOut.fragment #= 0
    dut.io.SSDDataOut.last     #= false
    dut.io.SSDInstrIn.ready    #= true

    /** generate page stream */
    val pageNum =  128
    val dupFacotr = 2
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
    
    var opStrmData: ListBuffer[BigInt] = ListBuffer()
    for (i <- 0 until opNum) {
      opStrmData.append(SimInstrHelpers.writeInstrGen(2*i*pagePerOp,pagePerOp))
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
    val goldenPgIsNew = List.tabulate[BigInt](pageNum){idx => if (idx < uniquePageNum) 1 else 0}
    val goldenpgRefCount = List.tabulate[BigInt](pageNum){idx => (idx/(uniquePageNum)) + 1}
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

    val pgWrRespWatch = fork {
      for (pageIdx <- 0 until pageNum) {
        val respData     = dut.io.pgResp.recvData(dut.clockDomain)
        val SHA3Hash     = SimHelpers.bigIntTruncVal(respData, 255, 0)
        val RefCount     = SimHelpers.bigIntTruncVal(respData, 287, 256)
        val SSDLBA       = SimHelpers.bigIntTruncVal(respData, 319, 288)
        val hostLBAStart = SimHelpers.bigIntTruncVal(respData, 351, 320)
        val hostLBALen   = SimHelpers.bigIntTruncVal(respData, 383, 352)
        val isExec       = SimHelpers.bigIntTruncVal(respData, 384, 384)
        val opCode       = SimHelpers.bigIntTruncVal(respData, 386, 385)
        
        assert(RefCount == goldenpgRefCount(pageIdx))
        assert(hostLBAStart == goldenPgIdx(pageIdx))
        assert(hostLBALen == 1)
        assert(isExec == goldenPgIsNew(pageIdx))
        assert(opCode == 1)

        // println(s"pageIdx: ${pageIdx}")
        // println(s"${RefCount} == ${goldenpgRefCount(pageIdx)}")
        // println(s"${hostLBAStart} == ${goldenPgIdx(pageIdx)}")
        // println(s"${hostLBALen} == 1")
        // println(s"${isExec} == ${goldenPgIsNew(pageIdx)}")
        // println(s"${opCode} == 0")
      }
    }

    opStrmPush.join()
    pgStrmPush.join()
    pgWrRespWatch.join()

  }
}

case class WrapDedupCoreTB() extends Component{

  val conf = DedupConfig()

  val io = new Bundle {
    /** input */
    val opStrmIn = slave Stream (Bits(conf.instrTotalWidth bits))
    val pgStrmIn = slave Stream (Bits(conf.wordSizeBit bits))
    /** output */
    val pgResp   = master Stream (PageWriterResp(conf))
    /** control signals */
    val initEn   = in Bool()
    val clearInitStatus = in Bool()
    val initDone = out Bool()

    /** hashTab memory interface */
    val axiMem   = master(Axi4(Axi4ConfigAlveo.u55cHBM))
    
    // SSD Intf for TB
    val SSDDataIn  = master Stream (Fragment(Bits(conf.wordSizeBit bits)))
    val SSDDataOut = slave Stream (Fragment(Bits(conf.wordSizeBit bits)))
    val SSDInstrIn = master Stream (SSDInstr(conf))

    /** pgStore throughput control factor */
    val factorThrou = in UInt(5 bits)
  }

  val dedupCore = WrapDedupCore()

  // auto connect
  dedupCore.io.opStrmIn    <> io.opStrmIn   
  dedupCore.io.pgStrmIn    <> io.pgStrmIn   
  dedupCore.io.pgResp      <> io.pgResp     
  dedupCore.io.initEn      <> io.initEn     
  dedupCore.io.initDone    <> io.initDone      
  dedupCore.io.SSDDataIn   <> io.SSDDataIn  
  dedupCore.io.SSDDataOut  <> io.SSDDataOut 
  dedupCore.io.SSDInstrIn  <> io.SSDInstrIn 
  dedupCore.io.factorThrou <> io.factorThrou

  dedupCore.io.clearInitStatus <> io.clearInitStatus

  // axi mux, RR arbitration
  val axiMux = AxiMux(conf.htConf.sizeFSMArray + 1)
  for (idx <- 0 until conf.htConf.sizeFSMArray + 1){
    axiMux.io.axiIn(idx) << dedupCore.io.axiMem(idx)
  }
  axiMux.io.axiOut >> io.axiMem
}