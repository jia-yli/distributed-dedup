package util

import spinal.core._
import spinal.lib._


object ReverseStreamArbiterFactory{
  def apply() = new ReverseStreamArbiterFactory()
}

object ReverseStreamArbiter {
  /** An Arbitration will choose which input stream to take at any moment. */
  object Arbitration{
    def lowerFirst(core: ReverseStreamArbiter[_ <: Data]) = new Area {
      import core._
      maskProposal := OHMasking.first(Vec(io.outputs.map(_.ready)))
    }

    /** This arbiter contains an implicit transactionLock */
    def sequentialOrder(core: ReverseStreamArbiter[_]) = new Area {
      import core._
      val counter = Counter(core.portCount, io.input.fire)
      for (i <- 0 to core.portCount - 1) {
        maskProposal(i) := False
      }
      maskProposal(counter) := True
    }

    def roundRobin(core: ReverseStreamArbiter[_ <: Data]) = new Area {
      import core._
      for(bitId  <- maskLocked.range){
        maskLocked(bitId) init(Bool(bitId == maskLocked.length-1))
      }
      //maskProposal := maskLocked
      maskProposal := OHMasking.roundRobin(Vec(io.outputs.map(_.ready)),Vec(maskLocked.last +: maskLocked.take(maskLocked.length-1)))
    }
  }

  /** When a lock activates, the currently chosen input won't change until it is released. */
  object Lock {
    def none(core: ReverseStreamArbiter[_]) = new Area {

    }

    /**
     * Many handshaking protocols require that once valid is set, it must stay asserted and the payload
     *  must not changed until the transaction fires, e.g. until ready is set as well. Since some arbitrations
     *  may change their chosen input at any moment in time (which is not wrong), this may violate such
     *  handshake protocols. Use this lock to be compliant in those cases.
     */
    def transactionLock(core: ReverseStreamArbiter[_]) = new Area {
      import core._
      locked setWhen(io.input.ready)
      locked.clearWhen(io.input.fire)
    }

    /**
     * This lock ensures that once a fragmented transaction is started, it will be finished without
     * interruptions from other streams. Without this, fragments of different streams will get intermingled.
     * This is only relevant for fragmented streams.
     */
    def fragmentLock(core: ReverseStreamArbiter[_]) = new Area {
      val realCore = core.asInstanceOf[ReverseStreamArbiter[Fragment[_]]]
      import realCore._
      locked setWhen(io.input.ready)
      locked.clearWhen(io.input.fire && io.input.last)
    }
  }
}

/**
 *  A ReverseStreamArbiter is like a StreamDemux, but with built-in complex selection logic that can arbitrate from output ready signal.
 *  Use a ReverseStreamArbiterFactory to create instances of this class.
 */
class ReverseStreamArbiter[T <: Data](dataType: HardType[T], val portCount: Int)(val arbitrationFactory: (ReverseStreamArbiter[T]) => Area, val lockFactory: (ReverseStreamArbiter[T]) => Area) extends Component {
  val io = new Bundle {
    val input = slave Stream (dataType)
    val outputs = Vec(master Stream (dataType), portCount)
    val chosen = out UInt (log2Up(portCount) bit)
    val chosenOH = out Bits (portCount bit)
  }

  val locked = RegInit(False).allowUnsetRegToAvoidLatch

  val maskProposal = Vec(Bool(),portCount)
  val maskLocked = Reg(Vec(Bool(),portCount))
  val maskRouted = Mux(locked, maskLocked, maskProposal)


  when(io.input.ready) {
    maskLocked := maskRouted
  }

  val arbitration = arbitrationFactory(this)
  val lock = lockFactory(this)

  (io.outputs, maskRouted).zipped.foreach{(outputPort, portMask) =>
    outputPort.valid := portMask & io.input.valid
    outputPort.payload := io.input.payload
  }

  io.input.ready := (io.outputs, maskRouted).zipped.map(_.ready & _).reduce(_ | _)

  // io.output.valid := (io.inputs, maskRouted).zipped.map(_.valid & _).reduce(_ | _)
  // io.output.payload := MuxOH(maskRouted,Vec(io.inputs.map(_.payload)))
  // (io.inputs, maskRouted).zipped.foreach(_.ready := _ & io.output.ready)

  io.chosenOH := maskRouted.asBits
  io.chosen := OHToUInt(io.chosenOH)
}

class ReverseStreamArbiterFactory {
  var arbitrationLogic: (ReverseStreamArbiter[_ <: Data]) => Area = ReverseStreamArbiter.Arbitration.lowerFirst
  var lockLogic: (ReverseStreamArbiter[_ <: Data]) => Area = ReverseStreamArbiter.Lock.transactionLock

  def build[T <: Data](dataType: HardType[T], portCount: Int): ReverseStreamArbiter[T] = {
    new ReverseStreamArbiter(dataType, portCount)(arbitrationLogic, lockLogic)
  }

  def onArgs[T <: Data](outputs: Stream[T]*): Stream[T] = on(outputs.seq)
  def on[T <: Data](outputs: Seq[Stream[T]]): Stream[T] = {
    val arbiter = build(outputs(0).payloadType, outputs.size)
    (arbiter.io.outputs, outputs).zipped.foreach(_ >> _)
    return arbiter.io.input
  }

  def lowerFirst: this.type = {
    arbitrationLogic = ReverseStreamArbiter.Arbitration.lowerFirst
    this
  }
  def roundRobin: this.type = {
    arbitrationLogic = ReverseStreamArbiter.Arbitration.roundRobin
    this
  }
  def sequentialOrder: this.type = {
    arbitrationLogic = ReverseStreamArbiter.Arbitration.sequentialOrder
    this
  }
  def noLock: this.type = {
    lockLogic = ReverseStreamArbiter.Lock.none
    this
  }
  def fragmentLock: this.type = {
    lockLogic = ReverseStreamArbiter.Lock.fragmentLock
    this
  }
  def transactionLock: this.type = {
    lockLogic = ReverseStreamArbiter.Lock.transactionLock
    this
  }
}