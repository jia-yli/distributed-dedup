package dedup
package funccheck
import util.BatchedStreamArbiterFactory
import util.BatchedFrgmOutType

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import util.sim._
import util.sim.SimDriver._

import spinal.core._
import spinal.lib._
import scala.collection.mutable
import scala.collection.mutable._
import scala.util.Random

class BatchedStreamArbiterTests extends AnyFunSuite {
  test("BatchedStreamArbiterTest"){
    // dummy allocator with sequential dispatcher in mallocIdx
    // we can predict the allocated address in simple golden model in this setup
    val compiledRTL = if (sys.env.contains("VCS_HOME")) SimConfig.withVpdWave.withVCS.compile(new BatchedStreamArbiterTB())
    else SimConfig.withWave.compile(new BatchedStreamArbiterTB())

    compiledRTL.doSim { dut =>
      dut.clockDomain.forkStimulus(period = 2)
      SimTimeout(1000)
      dut.io.in0.valid  #= false 
      dut.io.in1.valid  #= false
      dut.io.outData.ready #= false

      dut.clockDomain.waitSampling(10)

      /* Stimuli injection */
      val in0Push = fork {
        for (instrIdx <- 0 until 10) {
          dut.io.in0.sendData(dut.clockDomain, BigInt(instrIdx))
        }
      }

      val in1Push = fork {
        for (instrIdx <- 0 until 10) {
          dut.io.in1.sendData(dut.clockDomain, BigInt(instrIdx))
        }
      }

      in0Push.join()
      in1Push.join()

      /* Res watch*/
      val outWatch = fork {
        for (respIdx <- 0 until 20) {
          val outData = dut.io.outData.recvData(dut.clockDomain)
          val outMask = dut.io.outMask.toBigInt
          val data = SimHelpers.bigIntTruncVal(outData, 7, 0)
          val len = SimHelpers.bigIntTruncVal(outData, 14, 8)
          val last = SimHelpers.bigIntTruncVal(outData, 15, 15)
          if (respIdx < 2){
            assert (outMask == 1)
            assert (last    == {if(respIdx == 1) 1 else 0})
            assert (len     == 2)
            assert (data    == respIdx)
            // println(outMask)
            // println(last)
            // println(len)
            // println(data)
            // println(outData.toString(2))
          } else if (respIdx < 12) {
            assert (outMask == 2)
            assert (last    == {if(respIdx - 2 == 9) 1 else 0})
            assert (len     == 10)
            assert (data    == respIdx - 2)
            // println(outMask)
            // println(last)
            // println(len)
            // println(data)
            // println(outData.toString(2))
          } else {
            assert (outMask == 1)
            assert (last    == {if(respIdx - 10 == 9) 1 else 0})
            assert (len     == 8)
            assert (data    == respIdx - 10)
            // println(outMask)
            // println(last)
            // println(len)
            // println(data)
            // println(outData.toString(2))
          }
        }
      }

      outWatch.join()
    }
  }
}

case class FlattenedOutput() extends Bundle{
  val data = UInt(8 bits)
  val len = UInt(7 bits)
  val last = Bool()
}
case class BatchedStreamArbiterTB() extends Component{
  val bufferDepth = 64
  val io = new Bundle {
    val in0      = slave Stream(UInt(8 bits))
    val in1      = slave Stream(UInt(8 bits))
    val outData  = master Stream (FlattenedOutput())
    val outMask  = out Bits (2 bit)
  }
  val arbiterOutput = BatchedStreamArbiterFactory().roundRobin.fragmentLock.onArgs(bufferDepth, io.in0, io.in1)
  io.outData.translateFrom(arbiterOutput.output){ (a, b) =>
    a.data := b.fragment.payload
    a.len := b.fragment.wordLen
    a.last := b.last
  }
  io.outMask := arbiterOutput.chosenOH
}