package dedup
package hashtable


import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import util.sim._
import util.sim.SimDriver._
import util.AxiMux

import routingtable.RoutedLookupInstr
import routingtable.RoutedWriteBackLookupRes

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._

import scala.collection.mutable
import scala.collection.mutable._
import scala.util.Random

class HashTableLookupEngineTests extends AnyFunSuite {
  test("HashTableLookupEngineTest"){
    // dummy allocator with sequential dispatcher in mallocIdx
    // we can predict the allocated address in simple golden model in this setup
    val compiledRTL = if (sys.env.contains("VCS_HOME")) SimConfig.withVpdWave.withVCS.compile(new HashTableLookupEngineTB())
    else SimConfig.withWave.compile(new HashTableLookupEngineTB())

    compiledRTL.doSim { dut =>
      HashTableLookupEngineSim.doSim(dut)
    }
  }
}

object HashTableLookupEngineSim {
  def doSim(dut: HashTableLookupEngineTB, verbose: Boolean = false): Unit = {
    // val randomWithSeed = new Random(1006045258)
    val randomWithSeed = new Random()
    val simNodeIdx = 7
    dut.clockDomain.forkStimulus(period = 2)
    SimTimeout(2000000)
    dut.io.initEn            #= false
    dut.io.clearInitStatus   #= false
    dut.io.instrStrmIn.valid #= false
    dut.io.res.ready         #= false
    dut.io.nodeIdx           #= simNodeIdx

    /** memory model for HashTab */
    SimDriver.instAxiMemSim(dut.io.axiMem, dut.clockDomain, None)

    dut.clockDomain.waitSampling(10)

    /** init */
    dut.io.initEn #= true
    dut.clockDomain.waitSampling()
    dut.io.initEn #= false
    dut.clockDomain.waitSamplingWhere(dut.io.initDone.toBoolean)

    /** generate page stream */
    val conf = HashTableLookupHelpers.conf
    val numBucketUsed = 32
    val bucketAvgLen = 8
    val numUniqueSHA3 = numBucketUsed * bucketAvgLen

    val uniqueSHA3refCount = 8

    assert(numBucketUsed <= conf.htConf.nBucket)
    val bucketMask = ((BigInt(1) << (log2Up(conf.htConf.nBucket) - log2Up(numBucketUsed))) - 1) << log2Up(numBucketUsed)
    val uniqueSHA3 = List.fill[BigInt](numUniqueSHA3)(BigInt(256, randomWithSeed) &~ bucketMask)

    val goldenHashTableRefCountLayout = ListBuffer.fill[BigInt](numUniqueSHA3)(0)
    val goldenHashTableSSDLBALayout = ListBuffer.fill[BigInt](numUniqueSHA3)(0)

    val goldenResponse: ListBuffer[ExecRes] = ListBuffer()
    val responseAppeared: ListBuffer[Boolean] = ListBuffer()
    var instrStrmData: ListBuffer[BigInt] = ListBuffer()

    // op gen
    // for (i <- 0 until opNum) {
    //   opStrmData.append(SimInstrHelpers.writeInstrGen(i*pagePerOp,pagePerOp))
    // }
    var pseudoAllocator = 1

    // var pgStrmData: ListBuffer[BigInt] = ListBuffer()

    val instrCount = uniqueSHA3refCount * numUniqueSHA3
    val tagList = List.fill[Int](instrCount)(randomWithSeed.nextInt(1 << 30))
    val nodeList = List.fill[Int](instrCount)(randomWithSeed.nextInt(1 << 30))
    // 1,...,N, 1,...,N
    var instrIdx = 0
    for (i <- 0 until uniqueSHA3refCount) {
      for (j <- 0 until numUniqueSHA3) {
        instrStrmData.append(HashTableLookupHelpers.instrGen(sha3 = uniqueSHA3(j), tag = tagList(instrIdx), opCode = 1, srcNode = nodeList(instrIdx)))
        val isNew = (goldenHashTableRefCountLayout(j) == 0)
        goldenHashTableRefCountLayout.update(j, goldenHashTableRefCountLayout(j) + 1)
        if (isNew) {
          goldenHashTableSSDLBALayout.update(j, pseudoAllocator)
          pseudoAllocator = pseudoAllocator + 1
        }
        goldenResponse.append(ExecRes(RefCount = goldenHashTableRefCountLayout(j),
                                      SSDLBA   = goldenHashTableSSDLBALayout(j),
                                      nodeIdx  = simNodeIdx,
                                      tag      = tagList(instrIdx),
                                      dstNode  = nodeList(instrIdx)))
        responseAppeared.append(false)
        instrIdx = instrIdx + 1
      }
    }

    // val maxAllocRound = Array.tabulate(htConf.sizeFSMArray)(idx => ((pseudoAllocator(idx) - 1)/htConf.sizeFSMArray)).max
    
    // 1,...,N, 1,...,N
    // for (j <- 0 until dupFacotr) {
    //   for (i <- 0 until uniquePageNum) {
    //     for (k <- 0 until pageSize/bytePerWord) {
    //       pgStrmData.append(uniquePgData(i*pageSize/bytePerWord+k))
    //     }
    //   }
    // }


    /* Stimuli injection */
    val instrStrmPush = fork {
      for (instrIdx <- 0 until (uniqueSHA3refCount * numUniqueSHA3)) {
        // dut.clockDomain.waitSampling(63)
        dut.io.instrStrmIn.sendData(dut.clockDomain, instrStrmData(instrIdx))
      }
    }

    /* Res watch*/
    val resWatch = fork {
      for (respIdx <- 0 until (uniqueSHA3refCount * numUniqueSHA3)) {
        val respData = dut.io.res.recvData(dut.clockDomain)
        val decodedRealOutput = HashTableLookupHelpers.decodeRes(respData)
        // println("we get:")
        // println(decodedRealOutput)
        // println("we expect:")
        // println(goldenResponse(respIdx))
        // assert(decodedRealOutput == goldenResponse(respIdx))
        val inputIdx = goldenResponse.indexWhere{ element =>
          val tagMatch = element.tag     == decodedRealOutput.tag
          val dstMatch = element.dstNode == decodedRealOutput.dstNode
          tagMatch && dstMatch
        }
        assert(inputIdx >= 0, "resp doesnot exist in input")
        assert(responseAppeared(inputIdx) == false)
        responseAppeared(inputIdx) = true
        // println(respIdx, inputIdx)
        // println(decodedRealOutput)
        // println(goldenResponse(inputIdx))
        // assert(decodedRealOutput == goldenResponse(inputIdx))
        assert(decodedRealOutput.RefCount == goldenResponse(inputIdx).RefCount)
        // assert(decodedRealOutput.SSDLBA   == goldenResponse(inputIdx).SSDLBA  )
        assert(decodedRealOutput.nodeIdx  == goldenResponse(inputIdx).nodeIdx )
        assert(decodedRealOutput.tag      == goldenResponse(inputIdx).tag     )
        assert(decodedRealOutput.dstNode  == goldenResponse(inputIdx).dstNode )
      }
    }

    instrStrmPush.join()
    resWatch.join()

    // Test part 1.5: read all
    val instrStrmData1p5: ListBuffer[BigInt] = ListBuffer()
    val goldenResponse1p5: ListBuffer[ExecRes] = ListBuffer()
    val responseAppeared1p5: ListBuffer[Boolean] = ListBuffer()

    val readRound = 2
    val instrCount1p5 = readRound * numUniqueSHA3
    // random shuffle
    var initialPgOrder1p5: List[Int] = List()
    for (j <- 0 until readRound) {
      initialPgOrder1p5 = initialPgOrder1p5 ++ List.range(0, numUniqueSHA3) // page order: 1,2,...,N,1,2,...,N,...
    }
    
    val tagList1p5 = List.fill[Int](instrCount1p5)(randomWithSeed.nextInt(1 << 30))
    val nodeList1p5 = List.fill[Int](instrCount1p5)(randomWithSeed.nextInt(1 << 30))

    val shuffledPgOrder1p5 = randomWithSeed.shuffle(initialPgOrder1p5)
    assert(shuffledPgOrder1p5.length == readRound * numUniqueSHA3, "Total page number must be the same as the predefined parameter")
    for (instrIdx <- 0 until instrCount1p5) {
      val pageIdx = shuffledPgOrder1p5(instrIdx)
      instrStrmData1p5.append(HashTableLookupHelpers.instrGen(sha3 = uniqueSHA3(pageIdx), tag = tagList1p5(instrIdx), opCode = 3, srcNode = nodeList1p5(instrIdx)))
      goldenResponse1p5.append(ExecRes(RefCount = goldenHashTableRefCountLayout(pageIdx),
                                       SSDLBA   = goldenHashTableSSDLBALayout(pageIdx),
                                       nodeIdx  = simNodeIdx,
                                       tag      = tagList1p5(instrIdx),
                                       dstNode  = nodeList1p5(instrIdx)))
      responseAppeared1p5.append(false)
    }


    /* Stimuli injection */
    val instrStrmPush1p5 = fork {
      for (instrIdx <- 0 until (readRound * numUniqueSHA3)) {
        dut.io.instrStrmIn.sendData(dut.clockDomain, instrStrmData1p5(instrIdx))
      }
    }

    /* Res watch*/
    val resWatch1p5 = fork {
      for (respIdx <- 0 until (readRound * numUniqueSHA3)) {
        val respData = dut.io.res.recvData(dut.clockDomain)
        val decodedRealOutput = HashTableLookupHelpers.decodeRes(respData)
        // println("we get:")
        // println(decodedRealOutput)
        // println("we expect:")
        // println(goldenResponse(respIdx))
        // assert(decodedRealOutput == goldenResponse(respIdx))
        val inputIdx = goldenResponse1p5.indexWhere{ element =>
          val tagMatch = element.tag     == decodedRealOutput.tag
          val dstMatch = element.dstNode == decodedRealOutput.dstNode
          tagMatch && dstMatch
        }
        assert(inputIdx >= 0, "resp doesnot exist in input")
        assert(responseAppeared1p5(inputIdx) == false)
        responseAppeared1p5(inputIdx) = true
        // println(respIdx, inputIdx)
        // println(decodedRealOutput)
        // println(goldenResponse1p5(inputIdx))
        // assert(decodedRealOutput == goldenResponse1p5(inputIdx))
        assert(decodedRealOutput.RefCount == goldenResponse1p5(inputIdx).RefCount)
        // assert(decodedRealOutput.SSDLBA   == goldenResponse1p5(inputIdx).SSDLBA  )
        assert(decodedRealOutput.nodeIdx  == goldenResponse1p5(inputIdx).nodeIdx )
        assert(decodedRealOutput.tag      == goldenResponse1p5(inputIdx).tag     )
        assert(decodedRealOutput.dstNode  == goldenResponse1p5(inputIdx).dstNode )
      }
    }

    instrStrmPush1p5.join()
    resWatch1p5.join()

    // Test part 2: delete all
    val goldenResponse2: ListBuffer[ExecRes] = ListBuffer()
    val responseAppeared2: ListBuffer[Boolean] = ListBuffer()
    val instrStrmData2: ListBuffer[BigInt] = ListBuffer()
    val goldenFreeIdx2: ListBuffer[BigInt] = ListBuffer()

    // var pgStrmData: ListBuffer[BigInt] = ListBuffer()

    val instrCount2 = uniqueSHA3refCount * numUniqueSHA3
    val tagList2 = List.fill[Int](instrCount2)(randomWithSeed.nextInt(1 << 30))
    val nodeList2 = List.fill[Int](instrCount2)(randomWithSeed.nextInt(1 << 30))
    // 1,...,N, 1,...,N
    var instrIdx2 = 0
    for (i <- 0 until uniqueSHA3refCount) {
      for (j <- 0 until numUniqueSHA3) {
        instrStrmData2.append(HashTableLookupHelpers.instrGen(sha3 = uniqueSHA3(j), tag = tagList2(instrIdx2), opCode = 2, srcNode = nodeList2(instrIdx2)))
        val isGC = (goldenHashTableRefCountLayout(j) == 1)
        goldenHashTableRefCountLayout.update(j, goldenHashTableRefCountLayout(j) - 1)
        if (isGC) {
          goldenFreeIdx2.append(goldenHashTableSSDLBALayout(j))
        }
        goldenResponse2.append(ExecRes(RefCount = goldenHashTableRefCountLayout(j),
                                       SSDLBA   = goldenHashTableSSDLBALayout(j),
                                       nodeIdx  = simNodeIdx,
                                       tag      = tagList2(instrIdx2),
                                       dstNode  = nodeList2(instrIdx2)))
        responseAppeared2.append(false)
        instrIdx2 = instrIdx2 + 1
      }
    }

    /* Stimuli injection */
    val instrStrmPush2 = fork {
      for (instrIdx <- 0 until (uniqueSHA3refCount * numUniqueSHA3)) {
        dut.io.instrStrmIn.sendData(dut.clockDomain, instrStrmData2(instrIdx))
      }
    }

    /* Res watch*/
    val resWatch2 = fork {
      for (respIdx <- 0 until (uniqueSHA3refCount * numUniqueSHA3)) {
        val respData = dut.io.res.recvData(dut.clockDomain)
        val decodedRealOutput = HashTableLookupHelpers.decodeRes(respData)
        // println("we get:")
        // println(decodedRealOutput)
        // println("we expect:")
        // println(goldenResponse(respIdx))
        // assert(decodedRealOutput == goldenResponse(respIdx))
        val inputIdx = goldenResponse2.indexWhere{ element =>
          val tagMatch = element.tag     == decodedRealOutput.tag
          val dstMatch = element.dstNode == decodedRealOutput.dstNode
          tagMatch && dstMatch
        }
        assert(inputIdx >= 0, "resp doesnot exist in input")
        assert(responseAppeared2(inputIdx) == false)
        responseAppeared2(inputIdx) = true
        // println(respIdx, inputIdx)
        // println(decodedRealOutput)
        // println(goldenResponse2(inputIdx))
        // assert(decodedRealOutput == goldenResponse2(inputIdx))
        assert(decodedRealOutput.RefCount == goldenResponse2(inputIdx).RefCount)
        // assert(decodedRealOutput.SSDLBA   == goldenResponse2(inputIdx).SSDLBA  )
        assert(decodedRealOutput.nodeIdx  == goldenResponse2(inputIdx).nodeIdx )
        assert(decodedRealOutput.tag      == goldenResponse2(inputIdx).tag     )
        assert(decodedRealOutput.dstNode  == goldenResponse2(inputIdx).dstNode )
      }
    }

    instrStrmPush2.join()
    resWatch2.join()
  }
}

case class HashTableLookupEngineTB() extends Component{

  val conf = HashTableLookupHelpers.conf

  val io = new Bundle {
    val initEn      = in Bool()
    val clearInitStatus = in Bool()
    val initDone    = out Bool()
    val nodeIdx     = in UInt(conf.nodeIdxWidth bits)
    // execution results
    val instrStrmIn = slave Stream(RoutedLookupInstr(conf))
    val res         = master Stream(RoutedWriteBackLookupRes(conf))
    /** DRAM interface */
    val axiConf     = Axi4ConfigAlveo.u55cHBM
    val axiMem      = master(Axi4(axiConf))
  }

  val lookupEngine = HashTableLookupEngine(conf)
  val memAllocator = MemManager(conf.htConf)
  lookupEngine.io.initEn                    := io.initEn
  lookupEngine.io.clearInitStatus           := io.clearInitStatus
  lookupEngine.io.updateRoutingTableContent := io.initEn
  lookupEngine.io.nodeIdx                   := io.nodeIdx
  // memAllocator.io.initEn      := io.initEn
  io.initDone                 := lookupEngine.io.initDone
  lookupEngine.io.instrStrmIn << io.instrStrmIn
  io.res                      << lookupEngine.io.res
  lookupEngine.io.mallocIdx   << memAllocator.io.mallocIdx
  lookupEngine.io.freeIdx     >> memAllocator.io.freeIdx

  val axiMux = AxiMux(conf.htConf.sizeFSMArray + 1)
  axiMux.io.axiIn(conf.htConf.sizeFSMArray) << memAllocator.io.axiMem
  // memAllocator.io.axiMem.setBlocked()
  for (idx <- 0 until conf.htConf.sizeFSMArray){
    axiMux.io.axiIn(idx) << lookupEngine.io.axiMem(idx)
  }
  axiMux.io.axiOut >> io.axiMem
}