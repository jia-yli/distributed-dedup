package util.sim

import spinal.core._
import spinal.core.sim._

import scala.util.Random

import dedup.DedupCoreOp
import dedup.DedupConfig

object  SimInstrHelpers {
  val conf = DedupConfig()
  val instrBitWidth = DedupCoreOp().getBitsWidth

  // def randInstrGen(instrIdx : Int, printRes : Boolean = false) : BigInt = instrIdx match{
  //   case 0 => nopGen(printRes)
  //   case 1 => writeInstrGen(1 + Random.nextInt(32).abs, 1 + Random.nextInt(32).abs, printRes)
  //   case 2 => eraseInstrGen(1 + Random.nextInt(32).abs, 1 + Random.nextInt(32).abs, printRes)
  //   case 3 => readInstrGen (1 + Random.nextInt(32).abs, 1 + Random.nextInt(32).abs, printRes)
  //   case _ => readInstrGen (1 + Random.nextInt(32).abs, 1 + Random.nextInt(32).abs, printRes)
  // }
  
  // generate 512 bit representation of instruction
  def nopGen(printRes : Boolean = false) : BigInt = {
    if (printRes){
      println(s"[NOP] opcode = 0")
    }
    BigInt(0)
  }

  def writeInstrGen(start:BigInt, len:BigInt, printRes : Boolean = false) : BigInt = {
    val start_trunc = SimHelpers.bigIntTruncVal(start, conf.LBAWidth - 1, 0)
    val len_trunc   = SimHelpers.bigIntTruncVal(len  , conf.LBAWidth - 1, 0)
    if (printRes){
      println(s"[WRITE] opcode = 1, start=$start_trunc, len=$len_trunc")
    }
    var rawInstr = BigInt(0)
    rawInstr = rawInstr + (BigInt(1) << (conf.instrTotalWidth - instrBitWidth))
    rawInstr = rawInstr + (start_trunc << conf.LBAWidth)
    rawInstr = rawInstr + (len_trunc << 0)
    rawInstr
  }

  def eraseInstrGen(sha3:BigInt, printRes : Boolean = false) : BigInt = {
    val sha3_trunc = SimHelpers.bigIntTruncVal(sha3, 255, 0)
    if (printRes){
      println(s"[ERASE] opcode = 2, sha3=$sha3")
    }
    var rawInstr = BigInt(0)
    rawInstr = rawInstr + (BigInt(2) << (conf.instrTotalWidth - instrBitWidth))
    rawInstr = rawInstr + (sha3 << 0)
    rawInstr
  }

  def readInstrGen(sha3:BigInt, printRes : Boolean = false) : BigInt = {
    val sha3_trunc = SimHelpers.bigIntTruncVal(sha3, 255, 0)
    if (printRes){
      println(s"[READ] opcode = 3, sha3=$sha3")
    }
    var rawInstr = BigInt(0)
    rawInstr = rawInstr + (BigInt(3) << (conf.instrTotalWidth - instrBitWidth))
    rawInstr = rawInstr + (sha3 << 0)
    rawInstr
  }

  // // decodeinstruction from output results
  // def writeInstrFromRes(respData:BigInt, printRes : Boolean = false) : BigInt = {
  //   val opcode = SimHelpers.bigIntTruncVal(respData, conf.LBAWidth * 2 + instrBitWidth - 1, conf.LBAWidth * 2)
  //   val start = SimHelpers.bigIntTruncVal(respData, conf.LBAWidth * 2 - 1, conf.LBAWidth)
  //   val len = SimHelpers.bigIntTruncVal(respData, conf.LBAWidth-1, 0)
  //   assert(opcode.toInt == 0)
  //   // println(s"[WRITE RESP] opcode = $opcode")
  //   writeInstrGen(start, len, printRes)
  // }

  // def eraseInstrFromRes(respData:BigInt, printRes : Boolean = false) : BigInt = {
  //   val opcode = SimHelpers.bigIntTruncVal(respData, 256 + 96 + instrBitWidth - 1, 256 + 96)
  //   val crc = SimHelpers.bigIntTruncVal(respData, 256 + 96 - 1, 256)
  //   val sha3 = SimHelpers.bigIntTruncVal(respData, 255, 0)
  //   assert(opcode.toInt == 1)
  //   // println(s"[ERASE RESP] opcode = $opcode")
  //   eraseInstrGen(crc, sha3, printRes)
  // }

  // def readInstrFromRes(respData:BigInt, printRes : Boolean = false) : BigInt = {
  //   val opcode = SimHelpers.bigIntTruncVal(respData, conf.LBAWidth * 2 + instrBitWidth - 1, conf.LBAWidth * 2)
  //   val start = SimHelpers.bigIntTruncVal(respData, conf.LBAWidth * 2 - 1, conf.LBAWidth)
  //   val len = SimHelpers.bigIntTruncVal(respData, conf.LBAWidth - 1, 0)
  //   assert(opcode.toInt == 2)
  //   // println(s"[READ RESP] opcode = $opcode")
  //   readInstrGen(start, len, printRes)
  // }

}