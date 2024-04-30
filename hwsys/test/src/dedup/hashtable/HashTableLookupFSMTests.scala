package dedup
package hashtable


import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import util.sim._
import util.sim.SimDriver._
import routingtable.RoutedLookupInstr
import routingtable.RoutedWriteBackLookupRes
import registerfile.RegisterFileConfig

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

case class ExecRes(val RefCount : BigInt,
                   val SSDLBA   : BigInt,
                   val nodeIdx  : BigInt,
                   val tag      : BigInt,
                   val dstNode  : BigInt)

object HashTableFSMTBSim {

  def doSim(dut: HashTableLookupFSMTB, verbose: Boolean = false): Unit = {
    // val randomWithSeed = new Random(1006045258)
    val randomWithSeed = new Random()
    val simNodeIdx = 5
    dut.clockDomain.forkStimulus(period = 2)
    SimTimeout(1000000)
    dut.io.initEn            #= false 
    dut.io.nodeIdx           #= simNodeIdx
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
    val conf = HashTableLookupHelpers.conf
    val numBucketUsed = 8
    val bucketAvgLen = 8
    val numUniqueSHA3 = numBucketUsed * bucketAvgLen

    val uniqueSHA3refCount = 16
    assert(numBucketUsed <= conf.htConf.nBucket)
    val bucketMask = ((BigInt(1) << (log2Up(conf.htConf.nBucket) - log2Up(numBucketUsed))) - 1) << log2Up(numBucketUsed)
    val uniqueSHA3 = List.fill[BigInt](numUniqueSHA3)(BigInt(256, randomWithSeed) &~ bucketMask)


    // Test part 1: insertion
    val goldenHashTableRefCountLayout = ListBuffer.fill[BigInt](numUniqueSHA3)(0)
    val goldenHashTableSSDLBALayout = ListBuffer.fill[BigInt](numUniqueSHA3)(0)

    val goldenResponse: ListBuffer[ExecRes] = ListBuffer()
    val instrStrmData: ListBuffer[BigInt] = ListBuffer()

    // op gen
    // for (i <- 0 until opNum) {
    //   opStrmData.append(SimInstrHelpers.writeInstrGen(i*pagePerOp,pagePerOp))
    // }
    var pseudoAllocator: Int = 1

    // var pgStrmData: ListBuffer[BigInt] = ListBuffer()

    // 1,...,N, 1,...,N
    for (j <- 0 until numUniqueSHA3) {
      for (i <- 0 until uniqueSHA3refCount) {
        val randTag = randomWithSeed.nextInt(1024)
        val randSrcNode = randomWithSeed.nextInt(1024)
        instrStrmData.append(HashTableLookupHelpers.instrGen(sha3 = uniqueSHA3(j), tag = randTag, opCode = 1, srcNode = randSrcNode))
        val isNew = (goldenHashTableRefCountLayout(j) == 0)
        goldenHashTableRefCountLayout.update(j, goldenHashTableRefCountLayout(j) + 1)
        if (isNew) {
          goldenHashTableSSDLBALayout.update(j, pseudoAllocator)
          pseudoAllocator = pseudoAllocator + 1
        }
        goldenResponse.append(ExecRes(RefCount = goldenHashTableRefCountLayout(j),
                                      SSDLBA   = goldenHashTableSSDLBALayout(j),
                                      nodeIdx  = simNodeIdx,
                                      tag      = randTag,
                                      dstNode  = randSrcNode))
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
        dut.io.mallocIdx.sendData(dut.clockDomain, BigInt(newBlkIdx + 1))
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
    val goldenResponse1p5: ListBuffer[ExecRes] = ListBuffer()
    val instrStrmData1p5: ListBuffer[BigInt] = ListBuffer()

    // var pgStrmData: ListBuffer[BigInt] = ListBuffer()

    // 1,...,N, 1,...,N
    for (i <- 0 until uniqueSHA3refCount) {
      for (j <- 0 until numUniqueSHA3) {
        val randTag = randomWithSeed.nextInt(1024)
        val randSrcNode = randomWithSeed.nextInt(1024)
        instrStrmData1p5.append(HashTableLookupHelpers.instrGen(sha3 = uniqueSHA3(j), tag = randTag, opCode = 3, srcNode = randSrcNode))
        goldenResponse1p5.append(ExecRes(RefCount = goldenHashTableRefCountLayout(j),
                                         SSDLBA   = goldenHashTableSSDLBALayout(j),
                                         nodeIdx  = simNodeIdx,
                                         tag      = randTag,
                                         dstNode  = randSrcNode))
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
    val goldenResponse2: ListBuffer[ExecRes] = ListBuffer()
    val instrStrmData2: ListBuffer[BigInt] = ListBuffer()
    val goldenFreeIdx2: ListBuffer[BigInt] = ListBuffer()

    // var pgStrmData: ListBuffer[BigInt] = ListBuffer()

    // 1,...,N, 1,...,N
    for (i <- 0 until uniqueSHA3refCount) {
      for (j <- 0 until numUniqueSHA3) {
        val randTag = randomWithSeed.nextInt(1024)
        val randSrcNode = randomWithSeed.nextInt(1024)
        instrStrmData2.append(HashTableLookupHelpers.instrGen(sha3 = uniqueSHA3(j), tag = randTag, opCode = 2, srcNode = randSrcNode))
        val isGC = (goldenHashTableRefCountLayout(j) == 1)
        goldenHashTableRefCountLayout.update(j, goldenHashTableRefCountLayout(j) - 1)
        if (isGC) {
          goldenFreeIdx2.append(goldenHashTableSSDLBALayout(j))
        }
        goldenResponse2.append(ExecRes(RefCount = goldenHashTableRefCountLayout(j),
                                       SSDLBA   = goldenHashTableSSDLBALayout(j),
                                       nodeIdx  = simNodeIdx,
                                       tag      = randTag,
                                       dstNode  = randSrcNode))
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
  val conf = DedupConfig(nodeIdxWidth = 32,
                         rfConf = RegisterFileConfig(tagWidth = 32),
                         htConf = HashTableConfig (hashValWidth = 256, 
                                                   ptrWidth = 32, 
                                                   hashTableSize = 128 * 64, 
                                                   expBucketSize = 128, 
                                                   hashTableOffset = (BigInt(1) << 30), 
                                                   bfEnable = true,
                                                   bfOptimizedReconstruct = false,
                                                   sizeFSMArray = 6))

  /**
    * Opcode: NOP 0, WRITE2FREE 1, ERASEREF 2, READSSD 3
    *
    * @param sha3
    * @param tag
    * @param opCode
    * @param srcNode
    * @return
    */
  def instrGen(sha3 : BigInt, tag : BigInt, opCode : BigInt, srcNode : BigInt) : BigInt = {
    val sha3_trunc    = SimHelpers.bigIntTruncVal(sha3, conf.htConf.hashValWidth - 1, 0)
    val tag_trunc     = SimHelpers.bigIntTruncVal(tag, conf.rfConf.tagWidth - 1, 0)
    val opCode_trunc  = SimHelpers.bigIntTruncVal(opCode, DedupCoreOp().getBitsWidth - 1, 0)
    val srcNode_trunc = SimHelpers.bigIntTruncVal(srcNode, conf.nodeIdxWidth - 1, 0)

    assert (sha3_trunc    == sha3, "sha3 too wide")
    assert (tag_trunc     == tag , "tag too wide")
    assert (opCode_trunc  == opCode , "opCode too wide")
    assert (srcNode_trunc == srcNode, "srcNode too wide")

    var lookupInstr = BigInt(0)
    val bitOffset = SimHelpers.BitOffset()

    bitOffset.next(conf.htConf.hashValWidth)
    lookupInstr = lookupInstr + (sha3_trunc << bitOffset.low)

    bitOffset.next(conf.rfConf.tagWidth)
    lookupInstr = lookupInstr + (tag_trunc << bitOffset.low)

    bitOffset.next(DedupCoreOp().getBitsWidth)
    lookupInstr = lookupInstr + (opCode_trunc << bitOffset.low)

    bitOffset.next(conf.nodeIdxWidth)
    lookupInstr = lookupInstr + (srcNode_trunc << bitOffset.low)    

    lookupInstr
  }

  // decodeinstruction from output results
  def decodeRes(respData:BigInt, printRes : Boolean = false) : ExecRes = {
    val bitOffset = SimHelpers.BitOffset()

    bitOffset.next(conf.lbaWidth)
    val RefCount = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
    bitOffset.next(conf.lbaWidth)
    val SSDLBA   = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
    bitOffset.next(conf.nodeIdxWidth)
    val nodeIdx  = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
    bitOffset.next(conf.rfConf.tagWidth)
    val tag      = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)
    bitOffset.next(conf.nodeIdxWidth)
    val dstNode  = SimHelpers.bigIntTruncVal(respData, bitOffset.high, bitOffset.low)

    ExecRes(RefCount = RefCount,
            SSDLBA   = SSDLBA  ,
            nodeIdx  = nodeIdx ,
            tag      = tag     ,
            dstNode  = dstNode )
  }
}

case class HashTableLookupFSMTB() extends Component{

  val conf = HashTableLookupHelpers.conf

  val io = new Bundle {
    val initEn      = in Bool()
    val nodeIdx     = in UInt(conf.nodeIdxWidth bits)
    val initDone    = out Bool()
    // execution results
    val instrStrmIn = slave Stream(RoutedLookupInstr(conf))
    val res         = master Stream(RoutedWriteBackLookupRes(conf))
    // interface to allocator
    val mallocIdx   = slave Stream(UInt(conf.htConf.ptrWidth bits))
    val freeIdx     = master Stream(UInt(conf.htConf.ptrWidth bits))

    /** DRAM interface */
    val axiConf     = Axi4ConfigAlveo.u55cHBM
    val axiMem      = master(Axi4(axiConf))
  }

  val memInitializer = HashTableMemInitializer(conf.htConf)

  val lookupFSM = HashTableLookupFSM(conf)
  
  memInitializer.io.initEn               := io.initEn
  memInitializer.io.clearInitStatus      := False
  lookupFSM.io.initEn                    := io.initEn
  lookupFSM.io.updateRoutingTableContent := io.initEn
  lookupFSM.io.nodeIdx                   := io.nodeIdx

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