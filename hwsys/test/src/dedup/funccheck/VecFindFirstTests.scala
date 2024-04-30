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

class VecFindFirstTests extends AnyFunSuite {
  test("VecFindFirstTest:"){

    val compiledRTL = if (sys.env.contains("VCS_HOME")) SimConfig.withVpdWave.withVCS.compile(new VecFindFirstTB())
    else SimConfig.withWave.compile(new VecFindFirstTB())

    compiledRTL.doSim { dut =>
      dut.clockDomain.forkStimulus(period = 2)
      SimTimeout(10000)

      dut.clockDomain.waitSampling(10)

      val res0 = dut.io.result0.toBigInt
      val res1 = dut.io.result1.toBigInt

      println(res0)
      println(res1)
    }
  }
}


// Define a module with parameter N that indicates the number of nodes
case class VecFindFirstTB() extends Component {
  val io = new Bundle {
    val result0 = out Bool()
    val result1 = out UInt(2 bits)         // The result of the comparison
    // val result1 = out UInt(3 bits)          // The result of the comparison
  }

  val targetVec = Vec(Reg(Bool()), 4)
  targetVec(0) := False
  targetVec(1) := False
  targetVec(2) := False
  targetVec(3) := False
  // val findFirstRes = targetVec.sFindFirst(a => a)
  val findFirstRes = targetVec.sFindFirst(a => !a)
  // return True, index if found
  // return False, last index if not found
  io.result0 := findFirstRes._1
  io.result1 := findFirstRes._2
}
