package dedup
package funccheck


import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import util.sim._
import util.sim.SimDriver._

import spinal.core._
import spinal.lib._
import scala.collection.mutable
import scala.collection.mutable._
import scala.util.Random

class ArbitrationTests extends AnyFunSuite {
  test("ArbitrationTest: Arbitration order"){
    // dummy allocator with sequential dispatcher in mallocIdx
    // we can predict the allocated address in simple golden model in this setup
    val compiledRTL = if (sys.env.contains("VCS_HOME")) SimConfig.withVpdWave.withVCS.compile(new ArbitrationTestTB())
    else SimConfig.withWave.compile(new ArbitrationTestTB())

    compiledRTL.doSim { dut =>
      dut.clockDomain.forkStimulus(period = 2)
      SimTimeout(1000000)
      dut.io.in0.valid  #= false 
      dut.io.in1.valid  #= false
      dut.io.out0.ready #= false

      dut.clockDomain.waitSampling(10)

      /* Stimuli injection */
      val in0Push = fork {
        for (instrIdx <- 0 until 10) {
          dut.io.in0.sendData(dut.clockDomain, BigInt(0))
        }
      }

      val in1Push = fork {
        for (instrIdx <- 0 until 10) {
          dut.io.in1.sendData(dut.clockDomain, BigInt(1))
        }
      }

      var out0Res = ListBuffer[BigInt]()
      /* Res watch*/
      val out0Watch = fork {
        for (respIdx <- 0 until 20) {
          val respData = dut.io.out0.recvData(dut.clockDomain)
          out0Res.append(respData)
        }
      }

      in0Push.join()
      in1Push.join()
      out0Watch.join()

      for (idx <- 0 until out0Res.length){
        println(out0Res(idx))
      }
    }
  }
}

case class ArbitrationTestTB() extends Component{

  val io = new Bundle {
    val in0      = slave Stream(UInt(1 bits))
    val in1      = slave Stream(UInt(1 bits))
    val out0     = master Stream(UInt(1 bits))
  }
  io.out0 << StreamArbiterFactory.lowerFirst.transactionLock.onArgs(io.in0, io.in1) // in0 first, then in1
}