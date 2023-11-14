package dedup.fingerprint

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import spinal.crypto.hash.sha3._
import spinal.crypto.hash._
import util.FrgmDistributor

case class SHA3Config(dataWidth: Int = 512, sha3Type: SHA3_Type = SHA3_256, groupSize: Int = 64) {
  val resWidth = sha3Type.hashWidth
}

case class SHA3GroupIO(conf: SHA3Config) extends Bundle {
  val initEn   = in Bool () // trigger zeroing SRAM content
  val frgmIn   = slave Stream (Fragment(Bits(conf.dataWidth bits)))
  val res      = master Stream (Bits(conf.resWidth bits))
}

case class SHA3Group(sha3Conf : SHA3Config = SHA3Config()) extends Component { 
  val io = SHA3GroupIO(sha3Conf)

  val sizeFastBufferGrp = 16
  val sizeSlowBufferGrp = sha3Conf.groupSize
  val sizeSuperFrgm = sizeSlowBufferGrp/sizeFastBufferGrp

  /** Fast buffer WxDxN: 512bx512x16(sizeFastBufferGrp) */
  val fastBufferGrp = Array.fill(sizeFastBufferGrp)(StreamFifo(Fragment(Bits(512 bits)), 512))
  val fastBufferDistr = FrgmDistributor(sizeFastBufferGrp, 1, Bits(512 bits))
  fastBufferDistr.io.strmI << io.frgmIn
  (fastBufferGrp, fastBufferDistr.io.strmO).zipped.foreach(_.io.push << _)
  fastBufferGrp.foreach(_.io.flush := io.initEn) // flush all fast buffer

  /** stream frgm adapter 512 -> 32 */
  val slowBufferAdptStrm = Vec(Stream(Fragment(Bits(32 bits))), sizeFastBufferGrp)
  (fastBufferGrp, slowBufferAdptStrm).zipped.foreach { (a, b) =>
    StreamFragmentWidthAdapter(a.io.pop.pipelined(StreamPipe.FULL), b)
  }

  /** Slow buffer WxDxN: 32bx1024x64(sizeSlowBufferGrp) */
  val slowBufferGrp = Array.fill(sizeSlowBufferGrp)(StreamFifo(Fragment(Bits(32 bits)), 1024))
  val slowBufferDistr = Array.fill(sizeFastBufferGrp)(FrgmDistributor(sizeSlowBufferGrp / sizeFastBufferGrp, 1, Bits(32 bits)))
  (slowBufferDistr, slowBufferAdptStrm).zipped.foreach(_.io.strmI << _)

  slowBufferGrp.zipWithIndex.foreach { case (e, i) =>
    val f = sizeSlowBufferGrp/sizeFastBufferGrp
    /** send page fragment to slowBuffer following the page order  */
    e.io.push << slowBufferDistr(i%sizeFastBufferGrp).io.strmO(i*f/sizeSlowBufferGrp).pipelined(StreamPipe.FULL)
  }
  slowBufferGrp.foreach(_.io.flush := io.initEn)

  /** SHA3 cores */
  val sha3CoreGrp = Array.fill(sizeSlowBufferGrp)(SHA3CoreWrap(SHA3_256))

  sha3CoreGrp.foreach(_.io.initEn := io.initEn)
  sha3CoreGrp.zipWithIndex.foreach { case (e, i) =>
    e.io.cmd.translateFrom(slowBufferGrp(i).io.pop.pipelined(true, true))((a, b) => {
      a.msg := b.fragment
      a.size := (32 / 8) - 1
      a.last := b.last
    })
  }

  /** Arbiter the results */
  val cntSel = Counter(sizeSlowBufferGrp, io.res.fire)
  io.res.translateFrom(StreamMux(cntSel, sha3CoreGrp.map(_.io.rsp.pipelined(StreamPipe.FULL))))(_ := _.digest)

  /** pipeline interface (initEn, cmd, res) of some SHA3Core to enable SLR allocation in implementation
   * Normall one SLR in u55c device can have 48 SHA3 cores
   */
  /**
  sha3CoreGrp.zipWithIndex.foreach { case (e, i) =>
    if (i<16) {
      e.io.initEn := io.initEn
    } else {
      e.io.initEn := RegNext(RegNext(RegNext(io.initEn)))
    }
  }

  sha3CoreGrp.zipWithIndex.foreach { case (e, i) =>
    val sha3Cmd = Stream(Fragment(Bits(32 bits)))
    if (i<16) {
      sha3Cmd << slowBufferGrp(i).io.pop.stage()
    } else {
      sha3Cmd << slowBufferGrp(i).io.pop.pipelined(true, true).pipelined(true, true)
    }

    e.io.cmd.translateFrom(sha3Cmd)((a, b) => {
      a.msg := b.fragment
      a.size := (32/8)-1
      a.last := b.last
    })
  }

  /** Arbiter the results */
  val cntSel = Counter(groupSize, io.res.fire)

  val sha3Rsp = Vec(Stream(sha3CoreGrp(0).io.rsp.payloadType), groupSize)
  sha3Rsp.zipWithIndex.foreach { case (e, i) =>
    if (i < 16) {
      e << sha3CoreGrp(i).io.rsp
    } else {
      e << sha3CoreGrp(i).io.rsp.pipelined(true, true).pipelined(true, true)
    }
  }
  io.res.translateFrom(StreamMux(cntSel, sha3Rsp))(_ := _.digest)
  */

}


case class SHA3CoreWrapIO(conf: SHA3Config) extends Bundle {
  val configCore = HashCoreConfig(
    dataWidth = conf.dataWidth bits,
    hashWidth = conf.sha3Type.hashWidth bits,
    hashBlockWidth = 0 bits
  )

  val initEn = in Bool() // wrap fsm init
  val cmd = slave Stream(Fragment(HashCoreCmd(configCore)))
  val rsp = master Stream(HashCoreRsp(configCore))

}

case class SHA3CoreWrap(sha3Type: SHA3_Type) extends Component {
  val sha3CoreConf = SHA3Config(32, sha3Type)
  val io = SHA3CoreWrapIO(sha3CoreConf)

  val sha3Core = new SHA3Core_Std(sha3Type)
  sha3Core.io.init := False
  sha3Core.io.cmd.setIdle()
  io.rsp.setIdle()
  io.cmd.setBlocked()

  io.rsp.payload := RegNextWhen(sha3Core.io.rsp, sha3Core.io.rsp.valid)

  val fsm = new StateMachine {
    val IDLE = new State with EntryPoint
    val INIT, CMD, RES = new State

    IDLE.whenIsActive {
      when(io.initEn) (goto(INIT))
    }

    INIT.whenIsActive {
      // init logic
      sha3Core.io.init := True
      goto(CMD)
    }

    CMD.whenIsActive {
      // cmd
      sha3Core.io.cmd << io.cmd
      when(io.cmd.lastFire) (goto(RES))
    }

    RES.whenIsActive {
      // waiting for output stream fire
      io.rsp.valid := True
      when(io.rsp.fire) (goto(INIT))
    }
  }
}
