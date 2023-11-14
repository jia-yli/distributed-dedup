package dedup
package fingerprint

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import registerfile.UnregisteredLookupInstr

case class FingerprintEngineConfig (readyQueueLogDepth: Int = 7, 
                                    waitingQueueLogDepth: Int = 7,
                                    sha3ResQueueLogDepth: Int = 7) {
  val instrTagWidth = if (readyQueueLogDepth > waitingQueueLogDepth) (readyQueueLogDepth + 1) else (waitingQueueLogDepth + 1)
}

case class FingerprintEngineIO(conf: DedupConfig) extends Bundle {
  val initEn       = in Bool ()
  val opStrmIn     = slave Stream (Bits(conf.instrTotalWidth bits))
  val pgStrmFrgmIn = slave Stream (Fragment(Bits(conf.wordSizeBit bits)))
  val res          = master Stream (UnregisteredLookupInstr(conf))
}

case class FingerprintEngine(conf : DedupConfig) extends Component {
  val io     = FingerprintEngineIO(conf)

  val htConf = conf.htConf

  val instrDecoder = FingerprintInstrDecoder(conf)
  val sha3Grp      = SHA3Group(conf.sha3Conf)

  val decodedReadyInstrQueue = StreamFifo(DecodedReadyInstr(conf),  1 << htConf.readyQueueLogDepth)
  val decodedWaitingInstrQueue = StreamFifo(DecodedWaitingInstr(conf),  1 << htConf.waitingQueueLogDepth)
  val sha3ResQueue = StreamFifo(Bits(htConf.hashValWidth bits), 1 << conf.feConf.sha3ResQueueLogDepth)

  val instrIssuer = FingerprintInstrIssuer(conf)

  // SHA3 Group + SHA3 res Queue
  sha3Grp.io.initEn := io.initEn
  sha3Grp.io.frgmIn << io.pgStrmFrgmIn.pipelined(StreamPipe.FULL)

  sha3ResQueue.io.push  << sha3Grp.io.res.pipelined(StreamPipe.FULL)
  sha3ResQueue.io.flush := io.initEn

  // decoder + decoded instr Queue
  io.opStrmIn.pipelined(StreamPipe.FULL) >> instrDecoder.io.rawInstrStream
  instrDecoder.io.readyInstrStream       >> decodedReadyInstrQueue.io.push
  instrDecoder.io.waitingInstrStream     >> decodedWaitingInstrQueue.io.push

  decodedReadyInstrQueue.io.flush    := io.initEn
  decodedWaitingInstrQueue.io.flush  := io.initEn

  // instr issuer and lookup Engine
  instrIssuer.io.initEn              := io.initEn
  instrIssuer.io.readyInstrStream    << decodedReadyInstrQueue.io.pop.pipelined(StreamPipe.FULL)
  instrIssuer.io.waitingInstrStream  << decodedWaitingInstrQueue.io.pop.pipelined(StreamPipe.FULL)
  instrIssuer.io.SHA3ResStream       << sha3ResQueue.io.pop.pipelined(StreamPipe.FULL)

  instrIssuer.io.instrIssueStream.pipelined(StreamPipe.FULL) >> io.res
}

