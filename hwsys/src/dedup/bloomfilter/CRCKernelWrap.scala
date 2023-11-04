package dedup.bloomfilter

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import spinal.crypto.checksum._

import scala.collection.mutable.ArrayBuffer

case class CRCKernelWrapIO(bfConf: BloomFilterConfig) extends Bundle {
  val initEn   = in Bool()
  val frgmIn   = slave Stream (Fragment(Bits(bfConf.dataWidth bits)))
  val res      = master Stream (Vec(Bits(32 bits), bfConf.k))
}

class CRCKernelWrap(bfConf : BloomFilterConfig = BloomFilterConfig(1 << log2Up(131072), 3)) extends Component {
  val io     = CRCKernelWrapIO(bfConf)
  
  // CRC Modules
  val crcSchemes = List(
    CRC32.Standard,
    CRC32.Bzip2,
    CRC32.Jamcrc,
    CRC32.C,
    CRC32.D,
    CRC32.Mpeg2,
    CRC32.Posix,
    CRC32.Xfer
  )

  val crcKernel = ArrayBuffer.empty[CRCModule]
  for (i <- 0 until bfConf.k){
    crcKernel += CRCModule(CRCCombinationalConfig(crcSchemes(i), bfConf.dataWidth bits))
  }

  // initialization
  io.frgmIn.setBlocked()
  crcKernel.foreach(_.io.cmd.setIdle())
  io.res.setIdle()

  val fsm = new StateMachine {
    val CRCIDLE                     = new State with EntryPoint
    val CRCINIT, CRCRUN, RUN_RETURN = new State

    CRCIDLE.whenIsActive {
      when(io.initEn) (goto(CRCINIT))
    }
    
    CRCINIT.whenIsActive {
      io.frgmIn.ready := False
      io.res.valid := False
      // init logic
      crcKernel.foreach { x =>
        x.io.cmd.valid := True
        x.io.cmd.data  := 0
        x.io.cmd.mode  := CRCCombinationalCmdMode.INIT
      }
      goto(CRCRUN)
    }

    CRCRUN.whenIsActive {
      // logic
      io.frgmIn.ready := True
      io.res.valid := False
      crcKernel.foreach { x =>
        x.io.cmd.valid := io.frgmIn.valid
        x.io.cmd.data  := io.frgmIn.fragment
        x.io.cmd.mode  := CRCCombinationalCmdMode.UPDATE
      }
      when(io.frgmIn.isLast & io.frgmIn.fire){
        goto(RUN_RETURN)
      }
    }

    RUN_RETURN.whenIsActive {
      io.frgmIn.ready := False
      io.res.valid := True
      val crcResBits = Vec(crcKernel.map(_.io.crc))
      io.res.payload := crcResBits
      when(io.res.fire) {
        goto(CRCINIT)
      }
    }
  }
}
