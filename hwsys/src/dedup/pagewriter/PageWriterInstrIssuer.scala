package dedup
package pagewriter

import spinal.core._
import spinal.lib._
import registerfile.HashTableLookupRes

case class PageWriterInstrIssuer(conf: DedupConfig) extends Component{

  val instrBitWidth = DedupCoreOp().getBitsWidth
  val pwConf = conf.pwConf

  val io = new Bundle {
    val initEn             = in Bool ()
    /* input instr stream */
    val readyInstrStream   = slave Stream (DecodedReadyInstr(conf)) 
    val waitingInstrStream = slave Stream (DecodedWaitingInstr(conf))
    val lookupResStream    = slave Stream (HashTableLookupRes(conf))

    /* output FSM instr stream */
    val instrIssueStream = master Stream (CombinedFullInstr(conf))
  }

  val tagGenerator = Counter(pwConf.instrTagWidth bits)
  
  when(io.initEn){
    tagGenerator.clear()
  }
  
  // instr: slice write instr, throw del instr for RefCount != 0
  // join lookup Res and waiting instr, produce ready-to-issue instruction
  val waitingInstrJoiner = new Area{
    val payload = CombinedFullInstr(conf)
    val valid = Bool() default (False)
    val ready = Bool() default (False)
    val fire  = Bool() default (False)

    // slicing: slice instruction with pagecount = 10 to 10x instr on one page 
    val slicingCounter = Counter(conf.LBAWidth bits)

    // payload assignment
    payload.SHA3Hash     := io.lookupResStream.payload.SHA3Hash
    payload.RefCount     := io.lookupResStream.payload.RefCount
    payload.SSDLBA       := io.lookupResStream.payload.SSDLBA
    payload.nodeIdx      := io.lookupResStream.payload.nodeIdx
    payload.hostLBAStart := io.waitingInstrStream.hostLBAStart + slicingCounter
    payload.hostLBALen   := 1  // sliced
    payload.opCode       := io.lookupResStream.payload.opCode
    payload.tag          := io.waitingInstrStream.tag

    valid := io.lookupResStream.valid & io.waitingInstrStream.valid
    io.lookupResStream.ready     := ready

    fire  := ready & valid

    when(io.initEn){
      slicingCounter.clear()
      io.waitingInstrStream.setBlocked()
    }.otherwise{
      when(fire){
        slicingCounter.increment()
      }
      // waiting instruction: write, 10 pages
      // fire this when CRC fire 10 times
      io.waitingInstrStream.ready := ((io.waitingInstrStream.payload.hostLBALen - 1) === slicingCounter.value) & fire

      when(io.waitingInstrStream.fire){
        slicingCounter.clear()
      }
    }
  } // now we have sliced and combined all info instr issue

  when(io.readyInstrStream.fire | io.waitingInstrStream.fire){
    tagGenerator.increment()
  }
  // Step 1, arbitrate instr from waiting Q and ready Q
  val instrIssuer = new Area {
    // issue based on the input order
    when(waitingInstrJoiner.valid & (waitingInstrJoiner.payload.tag === tagGenerator.value)){
      // issue waiting instr
      io.readyInstrStream.setBlocked()

      // payload assignment
      io.instrIssueStream.payload assignAllByName waitingInstrJoiner.payload
      
      // hand shaking signals
      waitingInstrJoiner.ready := io.instrIssueStream.ready
      io.instrIssueStream.valid := waitingInstrJoiner.valid

    }.elsewhen(io.readyInstrStream.valid & (io.readyInstrStream.payload.tag === tagGenerator.value)){
      waitingInstrJoiner.ready := False

      // payload assignment
      io.instrIssueStream.payload.SHA3Hash     := 0
      io.instrIssueStream.payload.RefCount     := 0
      io.instrIssueStream.payload.SSDLBA       := io.readyInstrStream.SSDLBAStart
      io.instrIssueStream.payload.nodeIdx      := 0
      io.instrIssueStream.payload.hostLBAStart := 0
      io.instrIssueStream.payload.hostLBALen   := io.readyInstrStream.SSDLBALen
      io.instrIssueStream.payload.opCode       := io.readyInstrStream.opCode
      io.instrIssueStream.payload.tag          := io.readyInstrStream.tag
      
      // hand shaking signals
      io.readyInstrStream.ready := io.instrIssueStream.ready
      io.instrIssueStream.valid := io.readyInstrStream.valid
    }.otherwise{
      // wait
      io.readyInstrStream.setBlocked()
      waitingInstrJoiner.ready := False
      io.instrIssueStream.setIdle()
    }
  }
}