package dedup
package hashtable


import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import util.sim._
import util.sim.SimDriver._

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._

// import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.Random

class MemManagerTests extends AnyFunSuite {
  test("MemManagerTest"){
    val totalIdxCount = 1 << 13
    val numAxiIdxBlocks = (totalIdxCount + 63)/64
    val compiledRTL = if (sys.env.contains("VCS_HOME")) SimConfig.withVpdWave.withVCS.compile(MemManagerTB(totalIdxCount, numAxiIdxBlocks))
    else SimConfig.withWave.compile(MemManagerTB(totalIdxCount, numAxiIdxBlocks))

    compiledRTL.doSim { dut =>
      MemManagerSim.doSim(dut, totalIdxCount)
    }
  }
}

object MemManagerSim {
  def doSim(dut: MemManagerTB, totalIdxCount: Int): Unit = {
    dut.clockDomain.forkStimulus(period = 2)
    SimTimeout(100000)
    dut.io.freeIdx.valid   #= false
    dut.io.mallocIdx.ready #= false
    /** memory model */
    SimDriver.instAxiMemSim(dut.io.axiMem, dut.clockDomain, None)
    dut.clockDomain.waitSampling(10)

    println(s"total idx: $totalIdxCount")

    /* Step 1, malloc half idx */
    val idxOutsideCount1: ListBuffer[BigInt] = ListBuffer.fill(totalIdxCount){0}

    dut.io.freeIdx.valid   #= false
    val mallocIdxPop1 = fork {
      for (idx <- 0 until totalIdxCount/2) {
        val mallocIdx = dut.io.mallocIdx.recvData(dut.clockDomain)
        assert(mallocIdx != 0)
        idxOutsideCount1((mallocIdx - 1).toInt) = idxOutsideCount1((mallocIdx - 1).toInt) + 1
      }
    }

    mallocIdxPop1.join()
    // check result
    for (idx <- 0 until totalIdxCount){
      assert(idxOutsideCount1(idx) == {if(idx < totalIdxCount/2) 1 else 0})
    }

    dut.clockDomain.waitSampling(1024)
    /* Step 2, free all prev malloced, malloc 1/8 at the same time */
    val idxToFree2: ListBuffer[BigInt] = ListBuffer.tabulate(totalIdxCount){idx => idxOutsideCount1(idx)}
    var countFreeIdx2 = 0
    val freeIdxPush2 = fork {
      for (idx <- 0 until totalIdxCount) {
        if (idxToFree2(idx) == 1){
          dut.io.freeIdx.sendData(dut.clockDomain, BigInt(idx) + 1)
          countFreeIdx2 = countFreeIdx2 + 1
        }
      }
      assert(countFreeIdx2 == (totalIdxCount / 2))
    }

    val idxOutsideCount2: ListBuffer[BigInt] = ListBuffer.fill(totalIdxCount){0}
    val mallocIdxPop2 = fork {
      dut.clockDomain.waitSampling(1024 + 512)
      for (idx <- 0 until totalIdxCount/8) {
        val mallocIdx = dut.io.mallocIdx.recvData(dut.clockDomain)
        assert(mallocIdx != 0)
        idxOutsideCount2((mallocIdx - 1).toInt) = idxOutsideCount2((mallocIdx - 1).toInt) + 1
      }
    }

    freeIdxPush2.join()
    mallocIdxPop2.join()
    //check result
    for (idx <- 0 until totalIdxCount){
      assert((idxOutsideCount2(idx) == 0) || (idxOutsideCount2(idx) == 1))
    }

    dut.clockDomain.waitSampling(1024)
    /* Step 3, free all prev malloced, malloc all at the same time */
    val idxToFree3: ListBuffer[BigInt] = ListBuffer.tabulate(totalIdxCount){idx => idxOutsideCount2(idx)}
    var countFreeIdx3 = 0
    val freeIdxPush3 = fork {
      dut.clockDomain.waitSampling(totalIdxCount - 1024 - 512)
      for (idx <- 0 until totalIdxCount) {
        if (idxToFree3(idx) == 1){
          dut.io.freeIdx.sendData(dut.clockDomain, BigInt(idx) + 1)
          countFreeIdx3 = countFreeIdx3 + 1
        }
      }
      assert(countFreeIdx3 == (totalIdxCount / 8))
    }

    val idxOutsideCount3: ListBuffer[BigInt] = ListBuffer.fill(totalIdxCount){0}
    val mallocIdxPop3 = fork {
      for (idx <- 0 until totalIdxCount) {
        val mallocIdx = dut.io.mallocIdx.recvData(dut.clockDomain)
        assert(mallocIdx != 0)
        idxOutsideCount3((mallocIdx - 1).toInt) = idxOutsideCount3((mallocIdx - 1).toInt) + 1
      }
    }

    freeIdxPush3.join()
    mallocIdxPop3.join()
    //check result
    for (idx <- 0 until totalIdxCount){
      assert(idxOutsideCount3(idx) == 1)
    }
  }
}

case class MemManagerTB(totalIdxCount: Int, numAxiIdxBlocks: Int) extends Component{
  val htConf = HashTableConfig (hashValWidth = 256, ptrWidth = 32, hashTableSize = totalIdxCount, expBucketSize = 1, hashTableOffset = (4*64) << log2Up(numAxiIdxBlocks))

  val io = MemManagerIO(htConf)
  val memManager = MemManager(htConf)
  memManager.io <> io
}