package dedup
package bloomfilter

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

case class BloomFilterInstrIssuer(conf: DedupConfig) extends Component{

  val instrBitWidth = DedupCoreOp().getBitsWidth
  val bfConf = conf.bfConf

  val io = new Bundle {
    val initEn             = in Bool ()
    /* input instr stream */
    val readyInstrStream   = slave Stream (DecodedReadyInstr(conf)) 
    val waitingInstrStream = slave Stream (DecodedWaitingInstr(conf))
    val CRCResStream       = slave Stream (Vec(Bits(32 bits), bfConf.k))

    /* output FSM instr stream */
    val instrIssueStream = master Stream (BloomFilterLookupFSMInstr(bfConf))
  }

  // initialization
  io.readyInstrStream.setBlocked()
  io.waitingInstrStream.setBlocked()
  // io.CRCResStream.setBlocked()
  // io.instrIssueStream.setIdle()

  val tagGenerator = Counter(bfConf.instrTagWidth bits)
  
  when(io.initEn){
    tagGenerator.clear()
  }
  
  val waitingInstrJoiner = new Area{
    val payload = DecodedReadyInstr(conf)
    val valid = Bool() default (False)
    val ready = Bool() default (False)
    val fire  = Bool() default (False)

    // payload assignment
    payload.CRCHash := io.CRCResStream.payload
    payload.opCode  := io.waitingInstrStream.opCode
    payload.tag     := io.waitingInstrStream.tag

    valid := io.waitingInstrStream.valid & io.CRCResStream.valid
    io.CRCResStream.ready     := ready

    fire  := ready & valid

    // slicing: slice instruction with pagecount = 10 to 10x instr on one page 
    val slicingCounter = Counter(conf.LBAWidth bits)
    when(io.initEn){
      slicingCounter.clear()
    }.otherwise{
      when(fire){
        slicingCounter.increment()
      }
      // waiting instruction: write, 10 pages
      // fire this when CRC fire 10 times
      io.waitingInstrStream.ready := ((io.waitingInstrStream.payload.pageCount - 1) === slicingCounter.value) & fire

      when(io.waitingInstrStream.fire){
        slicingCounter.clear()
      }
    }
  }

  when(io.readyInstrStream.fire | io.waitingInstrStream.fire){
    tagGenerator.increment()
  }

  val instrIssuer = new Area {
    // issue based on the input order
    when(waitingInstrJoiner.valid & (waitingInstrJoiner.payload.tag === tagGenerator.value)){
      // issue waiting instr
      io.readyInstrStream.setBlocked()

      // payload assignment
      io.instrIssueStream.payload assignSomeByName waitingInstrJoiner.payload
      
      // hand shaking signals
      waitingInstrJoiner.ready := io.instrIssueStream.ready
      io.instrIssueStream.valid := waitingInstrJoiner.valid

    }.elsewhen(io.readyInstrStream.valid & (io.readyInstrStream.payload.tag === tagGenerator.value)){
      waitingInstrJoiner.ready := False

      // payload assignment
      io.instrIssueStream.payload assignSomeByName io.readyInstrStream.payload
      
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