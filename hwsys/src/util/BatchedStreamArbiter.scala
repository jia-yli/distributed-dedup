package util

import spinal.core._
import spinal.lib._


object BatchedStreamArbiterFactory{
  def apply() = new BatchedStreamArbiterFactory()
}

case class BatchedFrgmOutType[T <: Data](dataType: HardType[T], val bufferDepth: Int) extends Bundle{val payload = dataType(); val wordLen = UInt (log2Up(bufferDepth + 1) bits)}

case class BatchedStreamArbiterOutType[T <: Data](dataType: HardType[T], val portCount: Int, val bufferDepth: Int) extends Bundle {
  val output = Stream (Fragment(BatchedFrgmOutType(dataType, bufferDepth)))
  val chosenOH = Bits (portCount bit)
}

object BatchedStreamArbiter {
  /** An Arbitration will choose which input stream to take at any moment. */
  object Arbitration{
    def lowerFirst(core: BatchedStreamArbiter[_ <: Data]) = new Area {
      import core._
      maskProposal := OHMasking.first(Vec(bufferedInputs.map(_.io.pop.valid)))
    }

    /** This arbiter contains an implicit transactionLock */
    def sequentialOrder(core: BatchedStreamArbiter[_]) = new Area {
      import core._
      val counter = Counter(core.portCount, io.output.fire)
      for (i <- 0 to core.portCount - 1) {
        maskProposal(i) := False
      }
      maskProposal(counter) := True
    }

    def roundRobin(core: BatchedStreamArbiter[_ <: Data]) = new Area {
      import core._
      for(bitId  <- maskLocked.range){
        maskLocked(bitId) init(Bool(bitId == maskLocked.length-1))
      }
      //maskProposal := maskLocked
      maskProposal := OHMasking.roundRobin(Vec(bufferedInputs.map(_.io.pop.valid)),Vec(maskLocked.last +: maskLocked.take(maskLocked.length-1)))
    }
  }

  /** When a lock activates, the currently chosen input won't change until it is released. */
  object Lock {
    // def none(core: BatchedStreamArbiter[_]) = new Area {

    // }

    /**
     * Many handshaking protocols require that once valid is set, it must stay asserted and the payload
     *  must not changed until the transaction fires, e.g. until ready is set as well. Since some arbitrations
     *  may change their chosen input at any moment in time (which is not wrong), this may violate such
     *  handshake protocols. Use this lock to be compliant in those cases.
     */
    // def transactionLock(core: BatchedStreamArbiter[_]) = new Area {
    //   import core._
    //   locked setWhen(io.output.valid)
    //   locked.clearWhen(io.output.fire)
    // }

    /**
     * This lock ensures that once a fragmented transaction is started, it will be finished without
     * interruptions from other streams. Without this, fragments of different streams will get intermingled.
     * This is only relevant for fragmented streams.
     */
    def fragmentLock(core: BatchedStreamArbiter[_]) = new Area {
      val realCore = core.asInstanceOf[BatchedStreamArbiter[Fragment[_]]]
      import realCore._
      locked setWhen(io.output.valid)
      locked.clearWhen(io.output.fire && io.output.last)
    }
  }
}

/**
 *  A BatchedStreamArbiter is like a StreamMux, but with built-in complex selection logic that can arbitrate input
 *  streams based on a schedule or handle fragmented streams. Use a BatchedStreamArbiterFactory to create instances of this class.
 */
class BatchedStreamArbiter[T <: Data](dataType: HardType[T], val portCount: Int, val bufferDepth: Int)(val arbitrationFactory: (BatchedStreamArbiter[T]) => Area, val lockFactory: (BatchedStreamArbiter[T]) => Area) extends Component {
  val io = new Bundle {
    val inputs = Vec(slave Stream (dataType),portCount)
    val output = master Stream (Fragment(BatchedFrgmOutType(dataType, bufferDepth)))
    val chosen = out UInt (log2Up(portCount) bit)
    val chosenOH = out Bits (portCount bit)
  }
  val bufferedInputs = Array.fill(portCount)(new StreamFifo(dataType, bufferDepth))
  (io.inputs, bufferedInputs).zipped.foreach(_ >> _.io.push)

  val locked = RegInit(False).allowUnsetRegToAvoidLatch

  val maskProposal = Vec(Bool(),portCount)
  val maskLocked = Reg(Vec(Bool(),portCount))
  val maskRouted = Mux(locked, maskLocked, maskProposal)

  // len
  val lenProposal = Vec(UInt (log2Up(bufferDepth + 1) bits),portCount)
  val lenLocked = Reg(Vec(UInt (log2Up(bufferDepth + 1) bits),portCount))
  val lenRouted = Mux(locked, lenLocked, lenProposal)
  (lenProposal, bufferedInputs).zipped.foreach(_ := _.io.occupancy)

  // last
  val sendCounter = Counter(1, bufferDepth, io.output.fire)
  when(io.output.fire && io.output.last){
    sendCounter.clear()
  }
  val batchedInputStrmVec = Vec(Stream(Fragment(BatchedFrgmOutType(dataType, bufferDepth))), portCount)
  for (index <- 0 until portCount){
    batchedInputStrmVec(index).arbitrationFrom(bufferedInputs(index).io.pop)
    batchedInputStrmVec(index).payload.fragment.payload := bufferedInputs(index).io.pop.payload
    batchedInputStrmVec(index).payload.fragment.wordLen := lenRouted(index)
    batchedInputStrmVec(index).payload.last := (sendCounter === lenRouted(index))
  }

  when(io.output.valid) {
    maskLocked := maskRouted
    lenLocked := lenRouted
  }

  val arbitration = arbitrationFactory(this)
  val lock = lockFactory(this)

  io.output.valid := (batchedInputStrmVec, maskRouted).zipped.map(_.valid & _).reduce(_ | _)
  io.output.payload := MuxOH(maskRouted,Vec(batchedInputStrmVec.map(_.payload)))
  (batchedInputStrmVec, maskRouted).zipped.foreach(_.ready := _ & io.output.ready)

  io.chosenOH := maskRouted.asBits
  io.chosen := OHToUInt(io.chosenOH)
}

class BatchedStreamArbiterFactory {
  var arbitrationLogic: (BatchedStreamArbiter[_ <: Data]) => Area = BatchedStreamArbiter.Arbitration.lowerFirst
  var lockLogic: (BatchedStreamArbiter[_ <: Data]) => Area = BatchedStreamArbiter.Lock.fragmentLock

  def build[T <: Data](dataType: HardType[T], portCount: Int, bufferDepth: Int): BatchedStreamArbiter[T] = {
    new BatchedStreamArbiter(dataType, portCount, bufferDepth)(arbitrationLogic, lockLogic)
  }

  def onArgs[T <: Data](bufferDepth: Int, inputs: Stream[T]*): BatchedStreamArbiterOutType[T] = on(bufferDepth: Int, inputs.seq)
  def on[T <: Data](bufferDepth: Int, inputs: Seq[Stream[T]]): BatchedStreamArbiterOutType[T] = {
    val arbiter = build(inputs(0).payloadType, inputs.size, bufferDepth)
    (arbiter.io.inputs, inputs).zipped.foreach(_ << _)
    val factoryOutput = BatchedStreamArbiterOutType(inputs(0).payloadType, inputs.size, bufferDepth)
    factoryOutput.output << arbiter.io.output
    factoryOutput.chosenOH := arbiter.io.chosenOH
    return factoryOutput
  }

  def lowerFirst: this.type = {
    arbitrationLogic = BatchedStreamArbiter.Arbitration.lowerFirst
    this
  }
  def roundRobin: this.type = {
    arbitrationLogic = BatchedStreamArbiter.Arbitration.roundRobin
    this
  }
  def sequentialOrder: this.type = {
    arbitrationLogic = BatchedStreamArbiter.Arbitration.sequentialOrder
    this
  }
  // def noLock: this.type = {
  //   lockLogic = BatchedStreamArbiter.Lock.none
  //   this
  // }
  def fragmentLock: this.type = {
    lockLogic = BatchedStreamArbiter.Lock.fragmentLock
    this
  }
  // def transactionLock: this.type = {
  //   lockLogic = BatchedStreamArbiter.Lock.transactionLock
  //   this
  // }
}