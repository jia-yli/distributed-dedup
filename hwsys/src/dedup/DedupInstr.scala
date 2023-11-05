package dedup

import spinal.core._
import spinal.lib._

object DedupCoreOp extends SpinalEnum(binarySequential) {
  val NOP, WRITE2FREE, ERASEREF, READSSD = newElement()
}

/* instr format: total 512 bit wide
  WRITE2FREE: opcode, host LBA start, host LBA len
  ERASEREF:   opcode, SHA3
  READSSD:    opcode, SHA3 */

case class WRITE2FREEInstr (conf: DedupConfig) extends Bundle{
  val hostLBALen = UInt(conf.LBAWidth bits)
  val hostLBAStart = UInt(conf.LBAWidth bits)
  val opCode = DedupCoreOp()

  def decodeFromRawBits() : (WRITE2FREEInstr, Bits) => Unit = {
    (decoded, raw) => {
      decoded.hostLBALen   assignFromBits raw(0, conf.LBAWidth bits)
      decoded.hostLBAStart assignFromBits raw(conf.LBAWidth, conf.LBAWidth bits)
      decoded.opCode       assignFromBits raw((conf.instrTotalWidth - 1) downto (conf.instrTotalWidth - DedupCoreOp().getBitsWidth))
    }
  }
}

case class ERASEREFInstr (conf: DedupConfig) extends Bundle{
  val SHA3Hash = Bits(256 bits)
  val opCode = DedupCoreOp()

  def decodeFromRawBits() : (ERASEREFInstr, Bits) => Unit = {
    (decoded, raw) => {
      decoded.SHA3Hash     :=             raw(0, 256 bits)
      decoded.opCode       assignFromBits raw((conf.instrTotalWidth - 1) downto (conf.instrTotalWidth - DedupCoreOp().getBitsWidth))
    }
  }
}

case class READSSDInstr (conf: DedupConfig) extends Bundle{
  val SHA3Hash = Bits(256 bits)
  val opCode = DedupCoreOp()

  def decodeFromRawBits() : (READSSDInstr, Bits) => Unit = {
    (decoded, raw) => {
      decoded.SHA3Hash     :=             raw(0, 256 bits)
      decoded.opCode       assignFromBits raw((conf.instrTotalWidth - 1) downto (conf.instrTotalWidth - DedupCoreOp().getBitsWidth))
    }
  }
}

// case class BloomFilterCRCInstr(conf: DedupConfig) extends Bundle{
//   val CRCHash = Vec(Bits(conf.bfConf.dataWidth bits), conf.bfConf.k)
//   val opCode = DedupCoreOp()
// }

// case class HashTableSHA3Instr(conf: DedupConfig) extends Bundle{
//   val SHA3Hash = Bits(conf.sha3Conf.resWidth bits)
//   val opCode = DedupCoreOp()
// }

// case class pageWriterInstr(conf: DedupConfig) extends Bundle{
//   val LBALen = UInt(conf.LBAWidth bits)
//   val LBA = UInt(conf.LBAWidth bits)
//   val opCode = DedupCoreOp()
// }