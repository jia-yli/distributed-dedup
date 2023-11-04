package dedup

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.crypto.hash.{BIG_endian, EndiannessMode, HashCoreIO, LITTLE_endian}
import spinal.crypto._
import spinal.core.sim._
import spinal.crypto.hash.sha3._
import ref.hash.SHA3

import scala.util.Random

class Sha3Tests extends AnyFunSuite {

  val NBR_ITERATION = 100

  test("Test: SHA3 512"){
    val compiledRTL = if(sys.env.contains("VCS_HOME")) SimConfig.withConfig(SpinalConfig(inlineRom = true)).withVpdWave.withVCS.compile(new SHA3Core_Std(SHA3_512))
    else SimConfig.withConfig(SpinalConfig(inlineRom = true)).withWave.compile(new SHA3Core_Std(SHA3_512))
    compiledRTL.doSim { dut =>
      dut.clockDomain.forkStimulus(2)
      HashIOsim.initializeIO(dut.io)
      dut.clockDomain.waitActiveEdge()
      for (i <- 0 to NBR_ITERATION) {
        HashIOsim.doSim(dut.io, dut.clockDomain, i, BIG_endian)(SHA3.digest(512))
      }
      dut.clockDomain.waitActiveEdge(5)
    }
  }

  test("Test: SHA3 256") {
    val compiledRTL = if (sys.env.contains("VCS_HOME")) SimConfig.withConfig(SpinalConfig(inlineRom = true)).withVpdWave.withVCS.compile(new SHA3Core_Std(SHA3_256))
    else SimConfig.withConfig(SpinalConfig(inlineRom = true)).withWave.compile(new SHA3Core_Std(SHA3_256))
    compiledRTL.doSim { dut =>
      dut.clockDomain.forkStimulus(2)
      HashIOsim.initializeIO(dut.io)
      dut.clockDomain.waitActiveEdge()
      for (i <- 0 to NBR_ITERATION) {
        HashIOsim.doSim(dut.io, dut.clockDomain, i, BIG_endian)(SHA3.digest(256))
      }
      dut.clockDomain.waitActiveEdge(5)
    }
  }
}

object HashIOsim {

  def initializeIO(dut: HashCoreIO): Unit ={
    dut.init      #= false
    dut.cmd.valid #= false
    dut.cmd.msg.randomize()
    dut.cmd.size.randomize()
    dut.cmd.last.randomize()
  }


  def doSim(dut: HashCoreIO, clockDomain: ClockDomain, lengthString: Int, endianess: EndiannessMode, msg: String = null)(refCrypto: (String) => Array[Byte]): Unit = {

    val byteSizeMsg = dut.cmd.msg.getWidth / 8

    // init Hash
    clockDomain.waitActiveEdge()
    dut.init      #= true
    clockDomain.waitActiveEdge()
    dut.init      #= false
    clockDomain.waitActiveEdge()

    // Generate a random message + compute the reference hash
    var msgHex    = if(msg == null) List.fill(lengthString)(Random.nextPrintableChar()).mkString("") else msg
    val refDigest = refCrypto(msgHex)

    // number of iteration
    var index = math.ceil(msgHex.length  / byteSizeMsg.toDouble).toInt

    // Send all block of message
    while(index != 0) {

      val (msg, isLast) = if (msgHex.length > byteSizeMsg) (msgHex.substring(0, byteSizeMsg) -> false) else (msgHex + 0.toChar.toString * (byteSizeMsg - msgHex.length) -> true)

      dut.cmd.valid #= true
      dut.cmd.msg   #= BigInt(0x00.toByte +: (if(endianess == LITTLE_endian) (msg.map(_.toByte).reverse.toArray) else (msg.map(_.toByte).toArray))  )// Add 00 in front in order to get a positif number
      dut.cmd.size  #= BigInt(if (isLast) msgHex.length - 1 else 0)
      dut.cmd.last  #= isLast

      // Wait the response
      if (isLast){
        clockDomain.waitSamplingWhere(dut.rsp.valid.toBoolean)

        val rtlDigest = CastByteArray(dut.rsp.digest.toBigInt.toByteArray, dut.rsp.digest.getWidth)

        if(endianess == LITTLE_endian){
          assert(CastByteArray(refDigest, dut.rsp.digest.getWidth).sameElements(Endianness(rtlDigest)), s"REF != RTL ${BigIntToHexString(BigInt(refDigest))} != ${BigIntToHexString(BigInt(Endianness(rtlDigest)))}")
        }else{
          assert(CastByteArray(refDigest, dut.rsp.digest.getWidth).sameElements(rtlDigest), s"REF != RTL ${BigIntToHexString(BigInt(refDigest))} != ${BigIntToHexString(BigInt(rtlDigest))}")
        }
      }else {
        clockDomain.waitSamplingWhere(dut.cmd.ready.toBoolean)
      }

      index -= 1
      msgHex = msgHex.drop(byteSizeMsg)
    }

    initializeIO(dut)
    clockDomain.waitActiveEdge()

  }
}