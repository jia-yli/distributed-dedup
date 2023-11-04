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

class AxiFifoTests extends AnyFunSuite {
  test("AxiFifoTest: 16 index blocks, operate at full"){
    val numAxiIdxBlocks = 16
    val compiledRTL = if (sys.env.contains("VCS_HOME")) SimConfig.withVpdWave.withVCS.compile(AxiFifoTB(numAxiIdxBlocks))
    else SimConfig.withWave.compile(AxiFifoTB(numAxiIdxBlocks))

    compiledRTL.doSim { dut =>
      AxiFifoSim.simFull(dut, 64)
    }
  }
  
  test("AxiFifoTest: 16 index blocks, operate at empty"){
    val numAxiIdxBlocks = 16
    val compiledRTL = if (sys.env.contains("VCS_HOME")) SimConfig.withVpdWave.withVCS.compile(AxiFifoTB(numAxiIdxBlocks))
    else SimConfig.withWave.compile(AxiFifoTB(numAxiIdxBlocks))

    compiledRTL.doSim { dut =>
      AxiFifoSim.simEmpty(dut, 64)
    }
  }

  test("AxiFifoTest: 1 index blocks"){
    val numAxiIdxBlocks = 1
    val compiledRTL = if (sys.env.contains("VCS_HOME")) SimConfig.withVpdWave.withVCS.compile(AxiFifoTB(numAxiIdxBlocks))
    else SimConfig.withWave.compile(AxiFifoTB(numAxiIdxBlocks))

    compiledRTL.doSim { dut =>
      AxiFifoSim.simFull(dut, 16)
    }
  }
}

object AxiFifoSim {
  def simFull(dut: AxiFifoTB, testBlockCount: Int = 64): Unit = {
    dut.clockDomain.forkStimulus(period = 2)
    SimTimeout(100000)
    dut.io.push.valid #= false
    dut.io.pop.ready  #= false
    /** memory model */
    SimDriver.instAxiMemSim(dut.io.axiMem, dut.clockDomain, None)
    dut.clockDomain.waitSampling(10)

    val totalIdxCount = 64 * testBlockCount // 64 x blocks
    val mallocIdxList: ListBuffer[BigInt] = ListBuffer()

    /* Stimuli injection */
    val freeIdxPush = fork {
      for (idx <- 0 until totalIdxCount) {
        dut.io.push.sendData(dut.clockDomain, BigInt(idx))
      }
    }

    val mallocIdxPop = fork {
      dut.io.pop.ready #= false
      dut.clockDomain.waitSamplingWhere(dut.io.push.ready.toBoolean == false)
      for (idx <- 0 until totalIdxCount) {
        val mallocIdx = dut.io.pop.recvData(dut.clockDomain)
        mallocIdxList.append(mallocIdx)
      }
    }

    freeIdxPush.join()
    mallocIdxPop.join()
    
    for (idx <- 0 until totalIdxCount){
      assert(mallocIdxList(idx) == idx)
    }
  }

  def simEmpty(dut: AxiFifoTB, testBlockCount: Int = 64): Unit = {
    dut.clockDomain.forkStimulus(period = 2)
    SimTimeout(100000)
    dut.io.push.valid #= false
    dut.io.pop.ready  #= false
    /** memory model */
    SimDriver.instAxiMemSim(dut.io.axiMem, dut.clockDomain, None)
    dut.clockDomain.waitSampling(10)

    val totalIdxCount = 64 * testBlockCount // 64 x blocks
    val mallocIdxList: ListBuffer[BigInt] = ListBuffer()

    /* Stimuli injection */
    val freeIdxPush = fork {
      dut.clockDomain.waitSampling(1000)
      for (blockIdx <- 0 until testBlockCount) {
        for (idx <- 0 until 64){
          dut.io.push.sendData(dut.clockDomain, BigInt(idx + blockIdx * 64))
        }
        dut.clockDomain.waitSampling(128)
      }
    }

    val mallocIdxPop = fork {
      for (idx <- 0 until totalIdxCount) {
        val mallocIdx = dut.io.pop.recvData(dut.clockDomain)
        mallocIdxList.append(mallocIdx)
      }
    }

    freeIdxPush.join()
    mallocIdxPop.join()
    
    for (idx <- 0 until totalIdxCount){
      assert(mallocIdxList(idx) == idx)
    }
  }
}

case class AxiFifoTB(numAxiIdxBlocks: Int) extends Component{
  val htConf = HashTableConfig (hashValWidth = 256, ptrWidth = 32, hashTableSize = (1<<13), expBucketSize = 8, hashTableOffset = (4*64) << log2Up(numAxiIdxBlocks))
  val conf = MemManagerConfig(htConf)

  val io = new Bundle{
    val push = slave Stream (UInt(conf.idxWidth bits))
    val pop  = master Stream (UInt(conf.idxWidth bits))
    val flush = in Bool() default(False)
    val idxCount = out UInt((1 + conf.idxWidth) bits)

    val axiConf = Axi4ConfigAlveo.u55cHBM
    val axiMem  = master(Axi4(axiConf))
  }

  val axiFifo = AxiFifo(conf)
  axiFifo.io <> io
}