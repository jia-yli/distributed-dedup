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

class RingCompTests extends AnyFunSuite {
  test("RingComparatorTest:"){
    // dummy allocator with sequential dispatcher in mallocIdx
    // we can predict the allocated address in simple golden model in this setup
    val compiledRTL = if (sys.env.contains("VCS_HOME")) SimConfig.withVpdWave.withVCS.compile(new RingComparator())
    else SimConfig.withWave.compile(new RingComparator())

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
case class RingComparator(width:Int = 4) extends Component {
  val io = new Bundle {
    val result0 = out Bool()
    val result1 = out Bool()          // The result of the comparison
    // val result1 = out UInt(3 bits)          // The result of the comparison
  }

  val valueIn = U(3)
  val bar0 = Reg(UInt(2 bits)) init 2
  val bar1 = Reg(UInt(2 bits)) init 0
  val delta = Reg(UInt(2 bits)) init 2
  bar0 := 2
  bar1 := 0
  delta := 2
  io.result0 := valueIn >= bar0
  io.result1 := valueIn < bar0 +^ delta // a + b will wrap around, lead to wrong res, a +^ b will consider carry
}
