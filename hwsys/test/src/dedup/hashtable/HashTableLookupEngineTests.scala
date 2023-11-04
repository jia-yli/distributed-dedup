package dedup
package hashtable


import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import util.sim._
import util.sim.SimDriver._
import util.AxiMux

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
    dut.clockDomain.forkStimulus(period = 2)
    SimTimeout(2000000)
    dut.io.initEn            #= false
    dut.io.clearInitStatus   #= false
    dut.io.instrStrmIn.valid #= false
    dut.io.res.ready         #= false

    /** memory model for HashTab */
    SimDriver.instAxiMemSim(dut.io.axiMem, dut.clockDomain, None)

    dut.clockDomain.waitSampling(10)

    /** init */
    dut.io.initEn #= true
    dut.clockDomain.waitSampling()
    dut.io.initEn #= false
    dut.clockDomain.waitSamplingWhere(dut.io.initDone.toBoolean)

    /** generate page stream */
    val htConf = HashTableLookupHelpers.htConf
    val numBucketUsed = 64
    val bucketAvgLen = 8
    val numUniqueSHA3 = numBucketUsed * bucketAvgLen

    val uniqueSHA3refCount = 8

    assert(numBucketUsed <= htConf.nBucket)
    val bucketMask = ((BigInt(1) << (log2Up( htConf.nBucket) - log2Up(numBucketUsed)) - 1) << log2Up(numBucketUsed))
    val uniqueSHA3 = List.fill[BigInt](numUniqueSHA3)(BigInt(256, randomWithSeed) &~ bucketMask)

    val goldenHashTableRefCountLayout = ListBuffer.fill[BigInt](numUniqueSHA3)(0)
    val goldenHashTableSSDLBALayout = ListBuffer.fill[BigInt](numUniqueSHA3)(0)

    val goldenResponse: ListBuffer[execRes] = ListBuffer()
    var instrStrmData: ListBuffer[BigInt] = ListBuffer()

    // op gen
    // for (i <- 0 until opNum) {
    //   opStrmData.append(SimInstrHelpers.writeInstrGen(i*pagePerOp,pagePerOp))
    // }
    var pseudoAllocator: Seq[Int] = Array.tabulate(htConf.sizeFSMArray)(idx => idx + 1)

    // var pgStrmData: ListBuffer[BigInt] = ListBuffer()

    // 1,...,N, 1,...,N
    var instrIdx = 0
    for (i <- 0 until uniqueSHA3refCount) {
      for (j <- 0 until numUniqueSHA3) {
        instrStrmData.append(HashTableLookupHelpers.insertInstrGen(uniqueSHA3(j)))
        val isNew = (goldenHashTableRefCountLayout(j) == 0)
        goldenHashTableRefCountLayout.update(j, goldenHashTableRefCountLayout(j) + 1)
        if (isNew) {
          val fsmId = instrIdx % htConf.sizeFSMArray
          goldenHashTableSSDLBALayout.update(j, pseudoAllocator(fsmId))
          pseudoAllocator.update(fsmId, pseudoAllocator(fsmId) + htConf.sizeFSMArray)
        }
        goldenResponse.append(execRes(uniqueSHA3(j),goldenHashTableRefCountLayout(j),goldenHashTableSSDLBALayout(j),1))
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

    // random shuffle
    // var initialPgOrder: List[Int] = List()
    // for (j <- 0 until dupFacotr) {
    //   initialPgOrder = initialPgOrder ++ List.range(0, uniquePageNum) // page order: 1,2,...,N,1,2,...,N,...
    // }

    // val shuffledPgOrder = randomWithSeed.shuffle(initialPgOrder)
    // assert(shuffledPgOrder.length == pageNum, "Total page number must be the same as the predefined parameter")
    // for (pgIdx <- 0 until pageNum) {
    //   val uniquePgIdx = shuffledPgOrder(pgIdx)
    //   for (k <- 0 until pageSize/bytePerWord) {
    //     pgStrmData.append(uniquePgData(uniquePgIdx * pageSize/bytePerWord+k))
    //   }
    // }

    /* Stimuli injection */
    val instrStrmPush = fork {
      for (instrIdx <- 0 until (uniqueSHA3refCount * numUniqueSHA3)) {
        dut.clockDomain.waitSampling(63)
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
        assert(decodedRealOutput.SHA3Hash == goldenResponse(respIdx).SHA3Hash)
        assert(decodedRealOutput.RefCount == goldenResponse(respIdx).RefCount)
        assert(decodedRealOutput.opCode   == goldenResponse(respIdx).opCode)
      }
    }

    instrStrmPush.join()
    resWatch.join()

    // Test part 1.5: read all
    val goldenResponse1p5: ListBuffer[execRes] = ListBuffer()
    val instrStrmData1p5: ListBuffer[BigInt] = ListBuffer()

    // 1,...,N, 1,...,N
    for (i <- 0 until uniqueSHA3refCount) {
      for (j <- 0 until numUniqueSHA3) {
        instrStrmData1p5.append(HashTableLookupHelpers.readInstrGen(uniqueSHA3(j)))
        goldenResponse1p5.append(execRes(uniqueSHA3(j),goldenHashTableRefCountLayout(j),goldenHashTableSSDLBALayout(j),3))
      }
    }

    /* Stimuli injection */
    val instrStrmPush1p5 = fork {
      for (instrIdx <- 0 until (uniqueSHA3refCount * numUniqueSHA3)) {
        dut.io.instrStrmIn.sendData(dut.clockDomain, instrStrmData1p5(instrIdx))
      }
    }

    /* Res watch*/
    val resWatch1p5 = fork {
      for (respIdx <- 0 until (uniqueSHA3refCount * numUniqueSHA3)) {
        val respData = dut.io.res.recvData(dut.clockDomain)
        val decodedRealOutput = HashTableLookupHelpers.decodeRes(respData)
        assert(decodedRealOutput.SHA3Hash == goldenResponse1p5(respIdx).SHA3Hash)
        assert(decodedRealOutput.RefCount == goldenResponse1p5(respIdx).RefCount)
        assert(decodedRealOutput.opCode   == goldenResponse1p5(respIdx).opCode)
      }
    }

    instrStrmPush1p5.join()
    resWatch1p5.join()

    // Test part 2: delete all
    val goldenResponse2: ListBuffer[execRes] = ListBuffer()
    val instrStrmData2: ListBuffer[BigInt] = ListBuffer()
    val goldenFreeIdx2: ListBuffer[BigInt] = ListBuffer()

    // var pgStrmData: ListBuffer[BigInt] = ListBuffer()

    // 1,...,N, 1,...,N
    for (i <- 0 until uniqueSHA3refCount) {
      for (j <- 0 until numUniqueSHA3) {
        instrStrmData2.append(HashTableLookupHelpers.eraseInstrGen(uniqueSHA3(j)))
        val isGC = (goldenHashTableRefCountLayout(j) == 1)
        goldenHashTableRefCountLayout.update(j, goldenHashTableRefCountLayout(j) - 1)
        if (isGC) {
          goldenFreeIdx2.append(goldenHashTableSSDLBALayout(j))
        }
        goldenResponse2.append(execRes(uniqueSHA3(j),goldenHashTableRefCountLayout(j),goldenHashTableSSDLBALayout(j),2))
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
        assert(decodedRealOutput.SHA3Hash == goldenResponse2(respIdx).SHA3Hash)
        assert(decodedRealOutput.RefCount == goldenResponse2(respIdx).RefCount)
        assert(decodedRealOutput.opCode   == goldenResponse2(respIdx).opCode)
      }
    }

    instrStrmPush2.join()
    resWatch2.join()
  }
}

case class HashTableLookupEngineTB() extends Component{

  val htConf = HashTableLookupHelpers.htConf

  val io = new Bundle {
    val initEn      = in Bool()
    val clearInitStatus = in Bool()
    val initDone    = out Bool()
    // execution results
    val instrStrmIn = slave Stream(HashTableLookupFSMInstr(htConf))
    val res         = master Stream(HashTableLookupFSMRes(htConf))
    /** DRAM interface */
    val axiConf     = Axi4ConfigAlveo.u55cHBM
    val axiMem      = master(Axi4(axiConf))
  }

  val lookupEngine = HashTableLookupEngine(htConf)
  val memAllocator = MemManager(htConf)
  lookupEngine.io.initEn      := io.initEn
  lookupEngine.io.clearInitStatus := io.clearInitStatus
  // memAllocator.io.initEn      := io.initEn
  io.initDone                 := lookupEngine.io.initDone
  lookupEngine.io.instrStrmIn << io.instrStrmIn
  io.res                      << lookupEngine.io.res
  lookupEngine.io.mallocIdx   << memAllocator.io.mallocIdx
  lookupEngine.io.freeIdx     >> memAllocator.io.freeIdx

  val axiMux = AxiMux(htConf.sizeFSMArray + 1)
  axiMux.io.axiIn(htConf.sizeFSMArray) << memAllocator.io.axiMem
  // memAllocator.io.axiMem.setBlocked()
  for (idx <- 0 until htConf.sizeFSMArray){
    axiMux.io.axiIn(idx) << lookupEngine.io.axiMem(idx)
  }
  axiMux.io.axiOut >> io.axiMem
}