package dedup
package hashtable


import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import util.sim._
import util.sim.SimDriver._

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._

import scala.collection.mutable
import scala.collection.mutable._
import scala.util.Random

class HashTableLookupFSMTests extends AnyFunSuite {
  def hashTableLookupFSMSim(): Unit = {

    val compiledRTL = if (sys.env.contains("VCS_HOME")) SimConfig.withVpdWave.withVCS.compile(new HashTableLookupFSMTB())
    else SimConfig.withWave.compile(new HashTableLookupFSMTB())

    compiledRTL.doSim { dut =>
      HashTableFSMTBSim.doSim(dut)
    }
  }

  test("HashTableLookupFSMTest"){
    hashTableLookupFSMSim()
  }
}

case class execRes(SHA3Hash: BigInt, RefCount: BigInt, SSDLBA: BigInt, opCode: BigInt)

object HashTableFSMTBSim {

  def doSim(dut: HashTableLookupFSMTB, verbose: Boolean = false): Unit = {
    // val randomWithSeed = new Random(1006045258)
    val randomWithSeed = new Random()
    dut.clockDomain.forkStimulus(period = 2)
    SimTimeout(1000000)
    dut.io.initEn            #= false 
    dut.io.clearInitStatus   #= false
    dut.io.instrStrmIn.valid #= false
    dut.io.res.ready         #= false
    dut.io.mallocIdx.valid   #= false
    dut.io.freeIdx.ready     #= false

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
    val numBucketUsed = 16
    val bucketAvgLen = 8
    val numUniqueSHA3 = numBucketUsed * bucketAvgLen

    val uniqueSHA3refCount = 2
    val bucketMask = ((BigInt(1) << (log2Up(htConf.nBucket) - log2Up(numBucketUsed)) - 1) << log2Up(numBucketUsed))
    val uniqueSHA3 = List.fill[BigInt](numUniqueSHA3)(BigInt(256, randomWithSeed) &~ bucketMask)


    // Test part 1: insertion
    val goldenHashTableRefCountLayout = ListBuffer.fill[BigInt](numUniqueSHA3)(0)
    val goldenHashTableSSDLBALayout = ListBuffer.fill[BigInt](numUniqueSHA3)(0)

    val goldenResponse: ListBuffer[execRes] = ListBuffer()
    val instrStrmData: ListBuffer[BigInt] = ListBuffer()

    // op gen
    // for (i <- 0 until opNum) {
    //   opStrmData.append(SimInstrHelpers.writeInstrGen(i*pagePerOp,pagePerOp))
    // }
    var pseudoAllocator: Int = 1

    // var pgStrmData: ListBuffer[BigInt] = ListBuffer()

    // 1,...,N, 1,...,N
    for (i <- 0 until uniqueSHA3refCount) {
      for (j <- 0 until numUniqueSHA3) {
        instrStrmData.append(HashTableLookupHelpers.insertInstrGen(uniqueSHA3(j)))
        val isNew = (goldenHashTableRefCountLayout(j) == 0)
        goldenHashTableRefCountLayout.update(j, goldenHashTableRefCountLayout(j) + 1)
        if (isNew) {
          goldenHashTableSSDLBALayout.update(j, pseudoAllocator)
          pseudoAllocator = pseudoAllocator + 1
        }
        goldenResponse.append(execRes(uniqueSHA3(j),goldenHashTableRefCountLayout(j),goldenHashTableSSDLBALayout(j),1))
      }
    }
    
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
        dut.io.instrStrmIn.sendData(dut.clockDomain, instrStrmData(instrIdx))
      }
    }

    /* pseudo malloc*/
    val pseudoMallocIdxPush = fork {
      for (newBlkIdx <- 0 until numUniqueSHA3) {
        dut.io.mallocIdx.sendData(dut.clockDomain, BigInt(newBlkIdx+1))
      }
    }

    /* pseudo freeUp*/
    dut.io.freeIdx.ready     #= false

    /* Res watch*/
    val resWatch = fork {
      for (respIdx <- 0 until (uniqueSHA3refCount * numUniqueSHA3)) {
        val respData = dut.io.res.recvData(dut.clockDomain)
        val decodedRealOutput = HashTableLookupHelpers.decodeRes(respData)
        assert(decodedRealOutput == goldenResponse(respIdx))
      }
    }

    instrStrmPush.join()
    pseudoMallocIdxPush.join()
    resWatch.join()

    // Test part 1.5: read all
    val goldenResponse1p5: ListBuffer[execRes] = ListBuffer()
    val instrStrmData1p5: ListBuffer[BigInt] = ListBuffer()

    // var pgStrmData: ListBuffer[BigInt] = ListBuffer()

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

    dut.io.mallocIdx.valid   #= false
    dut.io.freeIdx.ready     #= false

    /* Res watch*/
    val resWatch1p5 = fork {
      for (respIdx <- 0 until (uniqueSHA3refCount * numUniqueSHA3)) {
        val respData = dut.io.res.recvData(dut.clockDomain)
        val decodedRealOutput = HashTableLookupHelpers.decodeRes(respData)
        assert(decodedRealOutput == goldenResponse1p5(respIdx))
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

    dut.io.mallocIdx.valid     #= false

    /* pseudo free*/
    val pseudoFreeIdxWatch2 = fork {
      for (newBlkIdx <- 0 until numUniqueSHA3) {
        val freeIdx = dut.io.freeIdx.recvData(dut.clockDomain)
        assert(freeIdx == goldenFreeIdx2(newBlkIdx))
      }
    }

    /* pseudo freeUp*/
    dut.io.freeIdx.ready     #= false

    /* Res watch*/
    val resWatch2 = fork {
      for (respIdx <- 0 until (uniqueSHA3refCount * numUniqueSHA3)) {
        val respData = dut.io.res.recvData(dut.clockDomain)
        val decodedRealOutput = HashTableLookupHelpers.decodeRes(respData)
        assert(decodedRealOutput == goldenResponse2(respIdx))
      }
    }

    instrStrmPush2.join()
    pseudoFreeIdxWatch2.join()
    resWatch2.join()

    // Test part 3: insert all to see BF reconstruction
    /* Stimuli injection */
    val instrStrmPush3 = fork {
      for (instrIdx <- 0 until (uniqueSHA3refCount * numUniqueSHA3)) {
        dut.io.instrStrmIn.sendData(dut.clockDomain, instrStrmData(instrIdx))
      }
    }

    /* pseudo malloc*/
    val pseudoMallocIdxPush3 = fork {
      for (newBlkIdx <- 0 until numUniqueSHA3) {
        dut.io.mallocIdx.sendData(dut.clockDomain, BigInt(newBlkIdx+1))
      }
    }

    /* pseudo freeUp*/
    dut.io.freeIdx.ready     #= false

    /* Res watch*/
    val resWatch3 = fork {
      for (respIdx <- 0 until (uniqueSHA3refCount * numUniqueSHA3)) {
        val respData = dut.io.res.recvData(dut.clockDomain)
        val decodedRealOutput = HashTableLookupHelpers.decodeRes(respData)
        assert(decodedRealOutput == goldenResponse(respIdx))
      }
    }

    instrStrmPush3.join()
    pseudoMallocIdxPush3.join()
    resWatch3.join()
  }
}

object HashTableLookupHelpers{
  val htConf = HashTableConfig (hashValWidth = 256, ptrWidth = 32, hashTableSize = 1024*8, expBucketSize = 128, hashTableOffset = (BigInt(1) << 30), bfEnable = false)
  // val htConf = DedupConfig().htConf

  def insertInstrGen(SHA3 : BigInt) : BigInt = {
    val sha3_trunc = SimHelpers.bigIntTruncVal(SHA3, 255, 0)
    val opCode = BigInt(1)

    var lookupInstr = BigInt(0)
    lookupInstr = lookupInstr + (opCode << (HashTableLookupFSMInstr(htConf).getBitsWidth - DedupCoreOp().getBitsWidth))
    lookupInstr = lookupInstr + (sha3_trunc << 0)
    lookupInstr
  }

  def eraseInstrGen(SHA3 : BigInt) : BigInt = {
    val sha3_trunc = SimHelpers.bigIntTruncVal(SHA3, 255, 0)
    val opCode = BigInt(2)

    var lookupInstr = BigInt(0)
    lookupInstr = lookupInstr + (opCode << (HashTableLookupFSMInstr(htConf).getBitsWidth - DedupCoreOp().getBitsWidth))
    lookupInstr = lookupInstr + (sha3_trunc << 0)
    lookupInstr
  }

  def readInstrGen(SHA3 : BigInt) : BigInt = {
    val sha3_trunc = SimHelpers.bigIntTruncVal(SHA3, 255, 0)
    val opCode = BigInt(3)

    var lookupInstr = BigInt(0)
    lookupInstr = lookupInstr + (opCode << (HashTableLookupFSMInstr(htConf).getBitsWidth - DedupCoreOp().getBitsWidth))
    lookupInstr = lookupInstr + (sha3_trunc << 0)
    lookupInstr
  }

  // decodeinstruction from output results
  def decodeRes(respData:BigInt, printRes : Boolean = false) : execRes = {
    val SHA3Hash = SimHelpers.bigIntTruncVal(respData, htConf.hashValWidth - 1, 0)
    val RefCount = SimHelpers.bigIntTruncVal(respData, htConf.hashValWidth + htConf.ptrWidth - 1, htConf.hashValWidth)
    val SSDLBA   = SimHelpers.bigIntTruncVal(respData, htConf.hashValWidth + 2 * htConf.ptrWidth - 1, htConf.hashValWidth + htConf.ptrWidth)
    val opCode   = SimHelpers.bigIntTruncVal(respData, HashTableLookupFSMRes(htConf).getBitsWidth, htConf.hashValWidth + 2 * htConf.ptrWidth)
    execRes(SHA3Hash, RefCount, SSDLBA, opCode)
  }
}

case class HashTableLookupFSMTB() extends Component{

  val htConf = HashTableLookupHelpers.htConf

  val io = new Bundle {
    val initEn      = in Bool()
    val clearInitStatus = in Bool()
    val initDone    = out Bool()
    // execution results
    val instrStrmIn = slave Stream(HashTableLookupFSMInstr(htConf))
    val res         = master Stream(HashTableLookupFSMRes(htConf))
    // interface to allocator
    val mallocIdx   = slave Stream(UInt(htConf.ptrWidth bits))
    val freeIdx     = master Stream(UInt(htConf.ptrWidth bits))

    /** DRAM interface */
    val axiConf     = Axi4ConfigAlveo.u55cHBM
    val axiMem      = master(Axi4(axiConf))
  }

  val memInitializer = HashTableMemInitializer(htConf)

  val lookupFSM = HashTableLookupFSM(htConf)
  
  memInitializer.io.initEn := io.initEn
  memInitializer.io.clearInitStatus := io.clearInitStatus
  lookupFSM.io.initEn      := io.initEn

  val isMemInitDone = memInitializer.io.initDone
  io.initDone := isMemInitDone

  // arbitrate AXI connection
  when(!isMemInitDone){
    memInitializer.io.axiMem >> io.axiMem
    lookupFSM.io.axiMem.setBlocked()
  }.otherwise{
    memInitializer.io.axiMem.setBlocked()
    lookupFSM.io.axiMem >> io.axiMem
  }

  io.mallocIdx >> lookupFSM.io.mallocIdx
  lookupFSM.io.freeIdx >> io.freeIdx
  io.instrStrmIn >> lookupFSM.io.instrStrmIn
  lookupFSM.io.res >> io.res
  lookupFSM.io.lockReq.ready := True
}