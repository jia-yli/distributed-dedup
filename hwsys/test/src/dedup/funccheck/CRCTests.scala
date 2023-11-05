package dedup.funccheck

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.crypto.checksum._
import spinal.crypto._
import ref.checksum.crc._

import scala.util.Random

// FIXME: the software reference is WRONG in some cases, cross checked the hw with https://crccalc.com
class CRCTests extends AnyFunSuite {


  /**
   * Compute the CRC reference
   */
  def computeCRC(data: List[BigInt], mode: AlgoParams, dataWidth: Int, verbose: Boolean = false): BigInt = {
    val calculator = new CrcCalculator(mode)

    val din = data.flatMap(_.toByteArray.takeRight(dataWidth / 8)).toArray
    val result = calculator.Calc(din, 0, din.length)

    if (verbose) {
      println(BigInt(result).toString(16))
    }

    BigInt(result)
  }


  /**
   * Simulate a CRC
   */
  def crcSimulation(crcMode: List[(CRCPolynomial, AlgoParams)], dataWidth: Int): Unit = {

    for (mode <- crcMode) {

      val config = CRCCombinationalConfig(
        crcConfig = mode._1,
        dataWidth = dataWidth bits
      )

      val compiledRTL = if(sys.env.contains("VCS_HOME")) SimConfig.withVpdWave.withVCS.compile(new CRCCombinational(config))
      else SimConfig.withWave.compile(new CRCCombinational(config))


      compiledRTL.doSim { dut =>
        dut.clockDomain.forkStimulus(2)
        for (i <- 0 until 1) {
          val data = List.fill[BigInt](64)(BigInt(dataWidth, Random))
          CRCCombinationalsim.doSim(dut.io, dut.clockDomain, data)(computeCRC(data, mode._2, dataWidth))
        }
      }
    }
  }

  /**
   * CRC32 with 32-bit data
   */
  test("CRC32_32_combinational"){

    val configurations = List(
      CRC32.Standard  -> Crc32.Crc32,
      CRC32.Bzip2      -> Crc32.Crc32Bzip2,
      CRC32.C      -> Crc32.Crc32C,
      CRC32.D      -> Crc32.Crc32D,
      CRC32.Jamcrc -> Crc32.Crc32Jamcrc,
      CRC32.Mpeg2 -> Crc32.Crc32Mpeg2,
      CRC32.Posix -> Crc32.Crc32Posix,
      CRC32.Xfer -> Crc32.Crc32Xfer
    )

    crcSimulation(configurations, 32)
  }
}



object CRCCombinationalsim {

  def doSim(dut: CRCCombinationalIO, clockDomain: ClockDomain, data: List[BigInt], verbose: Boolean = false)(result: BigInt): Unit = {

    require(data.nonEmpty)

    var index = 0

    // initialize CRC
    dut.cmd.valid #= true
    dut.cmd.data  #= 0
    dut.cmd.mode  #= CRCCombinationalCmdMode.INIT
    clockDomain.waitSampling()

    dut.cmd.valid #= false

    // Send all data
    for(_ <- data.indices){
      dut.cmd.mode  #= CRCCombinationalCmdMode.UPDATE
      dut.cmd.valid #= true
      dut.cmd.data  #= data(index)
      clockDomain.waitSampling()
      index += 1
    }

    dut.cmd.data #= 0
    dut.cmd.mode #= CRCCombinationalCmdMode.INIT
    dut.cmd.valid #= false

    // check crc
    clockDomain.waitSampling()
    val crcRTL = dut.crc.toBigInt

    clockDomain.waitSampling()
    clockDomain.waitSampling()
    clockDomain.waitSampling()

    dut.cmd.valid #= false

    if(verbose){
      println(s"0x${crcRTL.toString(16).toUpperCase}")
    }

    println(s"0x${crcRTL.toString(16).toUpperCase}")
    println(s"0x${result.toString(16).toUpperCase}")

    assert(crcRTL == result, "CRC error")
  }
}

object CRC32 {
  def Standard  = new CRCPolynomial(polynomial = p"32'x04C11DB7", initValue = BigInt("FFFFFFFF", 16), inputReflected = true,  outputReflected = true,  finalXor = BigInt("FFFFFFFF", 16))
  def Bzip2     = new CRCPolynomial(polynomial = p"32'x04C11DB7", initValue = BigInt("FFFFFFFF", 16), inputReflected = false, outputReflected = false, finalXor = BigInt("FFFFFFFF", 16))
  def C         = new CRCPolynomial(polynomial = p"32'x1EDC6F41", initValue = BigInt("FFFFFFFF", 16), inputReflected = true, outputReflected = true, finalXor = BigInt("FFFFFFFF", 16))
  def D         = new CRCPolynomial(polynomial = p"32'xA833982B", initValue = BigInt("FFFFFFFF", 16), inputReflected = true, outputReflected = true, finalXor = BigInt("FFFFFFFF", 16))
  def Jamcrc    = new CRCPolynomial(polynomial = p"32'x04C11DB7", initValue = BigInt("FFFFFFFF", 16), inputReflected = true, outputReflected = true, finalXor = BigInt("00000000", 16))
  def Mpeg2     = new CRCPolynomial(polynomial = p"32'x04C11DB7", initValue = BigInt("FFFFFFFF", 16), inputReflected = false, outputReflected = false, finalXor = BigInt("00000000", 16))
  def Posix     = new CRCPolynomial(polynomial = p"32'x04C11DB7", initValue = BigInt("00000000", 16), inputReflected = false, outputReflected = false, finalXor = BigInt("FFFFFFFF", 16))
  def Q         = new CRCPolynomial(polynomial = p"32'x814141AB", initValue = BigInt("00000000", 16), inputReflected = false, outputReflected = false, finalXor = BigInt("00000000", 16))
  def Xfer      = new CRCPolynomial(polynomial = p"32'x000000AF", initValue = BigInt("00000000", 16), inputReflected = false, outputReflected = false, finalXor = BigInt("00000000", 16))
}