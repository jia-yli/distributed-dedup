package util

import spinal.core._
import spinal.lib._

case class FrgmDemuxIO[T <: Data](n: Int, frgmType: HardType[T]) extends Bundle {
  val strmI = slave Stream(Fragment(frgmType))
  val strmO = Vec(master Stream(Fragment(frgmType)), n)
  val sel = in UInt(log2Up(n) bits)
  val en = in Bool()
}

case class FrgmDemux[T <: Data](n: Int, frgmType: HardType[T]) extends Component {
  val io = FrgmDemuxIO(n, frgmType)
  (io.strmO, StreamDemux(io.strmI.continueWhen(io.en), io.sel, n)).zipped.foreach(_ << _)
}