package dedup.bloomfilter

import spinal.core._
import spinal.crypto._
import spinal.crypto.checksum._
import spinal.lib._

object CRC32 {
  def Standard = new CRCPolynomial(
    polynomial = p"32'x04C11DB7",
    initValue = BigInt("FFFFFFFF", 16),
    inputReflected = true,
    outputReflected = true,
    finalXor = BigInt("FFFFFFFF", 16)
  )
  def Bzip2 = new CRCPolynomial(
    polynomial = p"32'x04C11DB7",
    initValue = BigInt("FFFFFFFF", 16),
    inputReflected = false,
    outputReflected = false,
    finalXor = BigInt("FFFFFFFF", 16)
  )
  def C = new CRCPolynomial(
    polynomial = p"32'x1EDC6F41",
    initValue = BigInt("FFFFFFFF", 16),
    inputReflected = true,
    outputReflected = true,
    finalXor = BigInt("FFFFFFFF", 16)
  )
  def D = new CRCPolynomial(
    polynomial = p"32'xA833982B",
    initValue = BigInt("FFFFFFFF", 16),
    inputReflected = true,
    outputReflected = true,
    finalXor = BigInt("FFFFFFFF", 16)
  )
  def Jamcrc = new CRCPolynomial(
    polynomial = p"32'x04C11DB7",
    initValue = BigInt("FFFFFFFF", 16),
    inputReflected = true,
    outputReflected = true,
    finalXor = BigInt("00000000", 16)
  )
  def Mpeg2 = new CRCPolynomial(
    polynomial = p"32'x04C11DB7",
    initValue = BigInt("FFFFFFFF", 16),
    inputReflected = false,
    outputReflected = false,
    finalXor = BigInt("00000000", 16)
  )
  def Posix = new CRCPolynomial(
    polynomial = p"32'x04C11DB7",
    initValue = BigInt("00000000", 16),
    inputReflected = false,
    outputReflected = false,
    finalXor = BigInt("FFFFFFFF", 16)
  )
  def Q = new CRCPolynomial(
    polynomial = p"32'x814141AB",
    initValue = BigInt("00000000", 16),
    inputReflected = false,
    outputReflected = false,
    finalXor = BigInt("00000000", 16)
  )
  def Xfer = new CRCPolynomial(
    polynomial = p"32'x000000AF",
    initValue = BigInt("00000000", 16),
    inputReflected = false,
    outputReflected = false,
    finalXor = BigInt("00000000", 16)
  )
}

case class CRCModule(g: CRCCombinationalConfig) extends Component {

  assert(g.dataWidth.value            % 8 == 0, "Currently support only datawidth multiple of 8")
  assert(g.crcConfig.polynomial.order % 8 == 0, "Currently support only polynomial degree multiple of 8")

  import CRCCombinationalCmdMode._

  val io = CRCCombinationalIO(g)

  // Input operation
  val crc_reg = Reg(cloneOf(io.crc))
  val dataIn  = if (g.crcConfig.inputReflected) EndiannessSwap(Reverse(io.cmd.data)) else io.cmd.data

  // Compute the CRC
  val next_crc = CRCCombinationalCore(dataIn, crc_reg, g.crcConfig.polynomial)

  // Init
  when(io.cmd.valid && io.cmd.mode === INIT) {
    crc_reg := B(g.crcConfig.initValue, io.crc.getWidth bits)
  }

  // Update
  when(io.cmd.valid && io.cmd.mode === UPDATE) {
    crc_reg := next_crc
  }

  // Output operation
  val result_reflected = if (g.crcConfig.outputReflected) Reverse(crc_reg) else crc_reg

  io.crc := result_reflected ^ B(g.crcConfig.finalXor, crc_reg.getWidth bits)
}
