package dedup
package bloomfilter


import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import util.sim._
import util.sim.SimDriver._
import util.Stream2StreamFragment

import spinal.core._
import spinal.lib._

import scala.collection.mutable
import scala.collection.mutable._
import scala.util.Random

class BloomFilterSubSystemTests extends AnyFunSuite {
  def bloomFilterSubSystemSim(): Unit = {

    val compiledRTL = if (sys.env.contains("VCS_HOME")) SimConfig.withVpdWave.withVCS.compile(new BloomFilterSubSystemTB())
    else SimConfig.withWave.compile(new BloomFilterSubSystemTB())

    compiledRTL.doSim { dut =>
      BloomFilterSubSystemTBSim.doSim(dut)
    }
  }

  test("CoreIFTest"){
    bloomFilterSubSystemSim()
  }
}


object BloomFilterSubSystemTBSim {

  def doSim(dut: BloomFilterSubSystemTB, verbose: Boolean = false): Unit = {
    dut.clockDomain.forkStimulus(period = 2)
    SimTimeout(1000000)
    dut.io.initEn             #= false 
    dut.io.pgStrmInOld.valid  #= false
    dut.io.resOldBF.ready     #= false
    dut.io.opStrmIn.valid     #= false
    dut.io.pgStrmInNew.valid  #= false
    dut.io.resNewBF.ready     #= false

    dut.clockDomain.waitSampling(10)

    /** init */
    dut.io.initEn #= true
    dut.clockDomain.waitSampling()
    dut.io.initEn #= false
    dut.clockDomain.waitSamplingWhere(dut.io.initDone.toBoolean)

    /** generate page stream */
    val pageNum = 64
    val dupFacotr = 2
    val opNum = 32
    assert(pageNum%dupFacotr==0, "pageNumber must be a multiple of dupFactor")
    assert(pageNum%opNum==0, "pageNumber must be a multiple of operation number")
    val uniquePageNum = pageNum/dupFacotr
    val pagePerOp = pageNum/opNum
    val pageSize = 4096
    val bytePerWord = 64

    val uniquePgData = List.fill[BigInt](uniquePageNum*pageSize/bytePerWord)(BigInt(bytePerWord*8, Random))
    
    var opStrmData: ListBuffer[BigInt] = ListBuffer()
    for (i <- 0 until opNum) {
      opStrmData.append(SimInstrHelpers.writeInstrGen(i*pagePerOp,pagePerOp))
    }

    var pgStrmData: ListBuffer[BigInt] = ListBuffer()

    // // 1,1,2,2,...,N,N
    // for (i <- 0 until uniquePageNum) {
    //   for (j <- 0 until dupFacotr) {
    //     for (k <- 0 until pageSize/bytePerWord) {
    //       pgStrmData.append(uniquePgData(i*pageSize/bytePerWord+k))
    //     }
    //   }
    // }
    
    // 1,...,N, 1,...,N
    // for (j <- 0 until dupFacotr) {
    //   for (i <- 0 until uniquePageNum) {
    //     for (k <- 0 until pageSize/bytePerWord) {
    //       pgStrmData.append(uniquePgData(i*pageSize/bytePerWord+k))
    //     }
    //   }
    // }

    // random shuffle
    var initialPgOrder: List[Int] = List()
    for (j <- 0 until dupFacotr) {
      initialPgOrder = initialPgOrder ++ List.range(0, uniquePageNum) // page order: 1,2,...,N,1,2,...,N,...
    }

    val shuffledPgOrder = Random.shuffle(initialPgOrder)
    assert(shuffledPgOrder.length == pageNum, "Total page number must be the same as the predefined parameter")
    for (pgIdx <- 0 until pageNum) {
      val uniquePgIdx = shuffledPgOrder(pgIdx)
      for (k <- 0 until pageSize/bytePerWord) {
        pgStrmData.append(uniquePgData(uniquePgIdx * pageSize/bytePerWord+k))
      }
    }

    /* Stimuli injection: Old BF*/
    val pgStrmPushOld = fork {
      var wordIdx: Int = 0
      for (_ <- 0 until pageNum) {
        for (_ <- 0 until pageSize / bytePerWord) {
          dut.io.pgStrmInOld.sendData(dut.clockDomain, pgStrmData(wordIdx))
          wordIdx += 1
        }
      }
    }

    /* Output watching: Old BF*/
    var BFResOld: ListBuffer[BigInt] = ListBuffer()
    val pgWrRespWatchOld = fork {
      for (_ <- 0 until pageNum) {
        val respData = dut.io.resOldBF.recvData(dut.clockDomain)
        BFResOld.append(respData)
      }
    }

    /* Stimuli injection: New BF*/
    val opStrmPushNew = fork {
      var opIdx: Int = 0
      for (opIdx <- 0 until opNum) {
        dut.clockDomain.waitSampling(Random.nextInt(100))
        dut.io.opStrmIn.sendData(dut.clockDomain, opStrmData(opIdx))
      }
    }

    val pgStrmPushNew = fork {
      var wordIdx: Int = 0
      for (_ <- 0 until pageNum) {
        for (_ <- 0 until pageSize / bytePerWord) {
          dut.clockDomain.waitSampling(Random.nextInt(100))
          dut.io.pgStrmInNew.sendData(dut.clockDomain, pgStrmData(wordIdx))
          wordIdx += 1
        }
      }
    }

    /* Output watching: New BF*/
    var BFResNew: ListBuffer[BigInt] = ListBuffer()
    val pgWrRespWatchNew = fork {
      for (_ <- 0 until pageNum) {
        dut.clockDomain.waitSampling(Random.nextInt(100))
        val respData = dut.io.resNewBF.recvData(dut.clockDomain)
        val lookupRes = SimHelpers.bigIntTruncVal(respData, 0, 0)
        BFResNew.append(lookupRes)
      }
    }

    pgStrmPushOld.join()
    pgWrRespWatchOld.join()
    pgStrmPushNew.join()
    pgWrRespWatchNew.join()

    // check same result
    (BFResNew, BFResOld).zipped.map{ case (output, expected) =>
      assert(output == expected)
    }
  }
}

case class BloomFilterSubSystemTB() extends Component{

  val conf = DedupConfig()

  val io = new Bundle {
    val initEn       = in Bool () 
    val initDone     = out Bool ()

    // Old BF IO
    val pgStrmInOld  = slave Stream (Bits(conf.wordSizeBit bits))
    val resOldBF     = master Stream (Bits(1 bits))

    // New BF IO
    val opStrmIn     = slave Stream (Bits(conf.instrTotalWidth bits))
    val pgStrmInNew  = slave Stream (Bits(conf.wordSizeBit bits))
    val resNewBF     = master Stream (Bits(3 bits))
  }

  // To Old BF
  val pgStrmFrgmOld = Stream2StreamFragment(io.pgStrmInOld, conf.pgWord)
  val bloomFilterOld = new deprecated.BloomFilterCRC()
  bloomFilterOld.io.frgmIn.translateFrom(pgStrmFrgmOld){(a, b) => 
    /** use the lsb 32b of the input 512b for CRC */
    a.fragment := b.fragment(bloomFilterOld.bfConf.dataWidth-1 downto 0)
    a.last := b.last
  }
  io.resOldBF.translateFrom(bloomFilterOld.io.res){(a, b) =>
    a := b.asBits
  }
  
  // To New BF
  val pgStrmFrgmNew = Stream2StreamFragment(io.pgStrmInNew, conf.pgWord)
  val bloomFilterNew = new BloomFilterSubSystem(conf)
  pgStrmFrgmNew >> bloomFilterNew.io.pgStrmFrgmIn
  io.opStrmIn >> bloomFilterNew.io.opStrmIn
  io.resNewBF.translateFrom(bloomFilterNew.io.res) {(a, b)=>
    a := b.opCode.asBits ## b.lookupRes.asBits
  }

  bloomFilterOld.io.initEn := io.initEn
  bloomFilterNew.io.initEn := io.initEn
  io.initDone := bloomFilterOld.io.initDone & bloomFilterNew.io.initDone

}