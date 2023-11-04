package util

import spinal.core._
import spinal.lib._

case class FrgmDistributorIO[T <: Data](n: Int, m: Int, frgmType: HardType[T]) extends Bundle {
  val strmI = slave Stream(Fragment(frgmType))
  val strmO = Vec(master Stream(Fragment(frgmType)), n)
}

case class FrgmDistributor[T <: Data](n: Int, m: Int, frgmType: HardType[T]) extends Component {
  val io = FrgmDistributorIO(n, m, frgmType)
  val cntSuperFrgm = Counter(m, io.strmI.lastFire)
  val cntOutSel = Counter(n, io.strmI.lastFire & cntSuperFrgm.willOverflow)
  (io.strmO, StreamDemux(io.strmI, cntOutSel, n)).zipped.foreach(_ << _)
}
