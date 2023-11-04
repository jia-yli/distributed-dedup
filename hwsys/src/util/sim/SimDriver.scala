package util.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axilite._
import SimHelpers._
import coyote._

import scala.collection.mutable
import scala.collection.mutable.Queue

object SimDriver {
  val axiMemSimConf = AxiMemorySimConfig(
    maxOutstandingReads = 128,
    maxOutstandingWrites = 128,
    readResponseDelay = 10,
    writeResponseDelay = 10
  )

  def instAxiMemSim(axi: Axi4, clockDomain: ClockDomain, memCtx: Option[Array[Byte]]): AxiMemorySim = {
    val mem = AxiMemorySim(axi, clockDomain, axiMemSimConf)
    mem.start()
    memCtx match {
      case Some(ctx) => {
        mem.memory.writeArray(0, ctx)
      }
      case None => mem.memory.writeArray(0, Array.fill[Byte](1 << 22)(0.toByte))
    }
    mem
  }

  // Axi4Lite
  def setAxi4LiteReg(cd: ClockDomain, bus: AxiLite4, addr: Int, data: BigInt): Unit = {
    val awa = fork {
      bus.aw.addr #= addr
      bus.w.data #= data
      bus.w.strb #= 0xFF
      bus.aw.valid #= true
      bus.w.valid #= true
      cd.waitSamplingWhere(bus.aw.ready.toBoolean && bus.w.ready.toBoolean)
      bus.aw.valid #= false
      bus.w.valid #= false
    }

    val b = fork {
      bus.b.ready #= true
      cd.waitSamplingWhere(bus.b.valid.toBoolean)
      bus.b.ready #= false
    }
    awa.join()
    b.join()
  }

  def readAxi4LiteReg(cd: ClockDomain, bus: AxiLite4, addr: Int): BigInt = {
    var data: BigInt = 1
    val ar = fork {
      bus.ar.addr #= addr
      bus.ar.valid #= true
      cd.waitSamplingWhere(bus.ar.ready.toBoolean)
      bus.ar.valid #= false
    }

    val r = fork {
      bus.r.ready #= true
      cd.waitSamplingWhere(bus.r.valid.toBoolean)
      data = bus.r.data.toBigInt
    }
    ar.join()
    r.join()
    data
  }

  def initAxi4LiteBus(bus: AxiLite4): Unit = {
    bus.ar.valid #= false
    bus.r.ready #= false
    bus.aw.valid #= false
    bus.w.valid #= false
    bus.b.ready #= false
  }

  // Axi4
  def axiMonitor(cd: ClockDomain, bus: Axi4): Unit = {
    fork {
      while (true) {
        cd.waitSamplingWhere(bus.readCmd.isFire)
        println(s"[AXI RdCmd]: ReadAddr: ${bus.readCmd.addr.toBigInt}")
      }
    }

    fork {
      while (true) {
        cd.waitSamplingWhere(bus.readRsp.isFire)
        println(s"[AXI RdResp]: ReadData: ${bus.readRsp.data.toBigInt}")
      }
    }

    fork {
      while (true) {
        cd.waitSamplingWhere(bus.writeCmd.isFire)
        println(s"[AXI WrCmd]: WrAddr: ${bus.writeCmd.addr.toBigInt}")
      }
    }

    fork {
      while (true) {
        cd.waitSamplingWhere(bus.writeData.isFire)
        println(s"[AXI WrData]: WrData: ${bus.writeData.data.toBigInt}")
      }
    }
  }

  implicit class StreamUtils[T <: Data](stream: Stream[T]) {
    def isFire: Boolean = {
      stream.valid.toBoolean && stream.ready.toBoolean
    }

    def simIdle(): Unit = {
      stream.valid #= false
    }

    def simBlocked(): Unit = {
      stream.ready #= true
    }
  }

  // TODO: how to constraint the type scope for specific method in the class? Then I can combine these above and below.
  implicit class StreamUtilsBitVector[T <: BitVector](stream: Stream[T]) {

    def sendData[T1 <: BigInt](cd: ClockDomain, data: T1): Unit = {
      stream.valid #= true
      stream.payload #= data
      cd.waitSamplingWhere(stream.ready.toBoolean)
      stream.valid #= false
    }

    def recvData(cd: ClockDomain): BigInt = {
      stream.ready #= true
      cd.waitSamplingWhere(stream.valid.toBoolean)
      stream.ready #= false
      stream.payload.toBigInt
    }

    def <<#(that: Stream[T]): Unit = {
      stream.payload #= that.payload.toBigInt
      stream.valid #= that.valid.toBoolean
      that.ready #= stream.ready.toBoolean
    }

    def #>>(that: Stream[T]) = {
      that <<# stream
    }
  }

  implicit class StreamUtilsBundle[T <: Bundle](stream: Stream[T]) {

    def sendData[T1 <: BigInt](cd: ClockDomain, data: T1): Unit = {
      stream.valid #= true
      stream.payload #= data
      cd.waitSamplingWhere(stream.ready.toBoolean)
      stream.valid #= false
    }

    def recvData(cd: ClockDomain): BigInt = {
      stream.ready #= true
      cd.waitSamplingWhere(stream.valid.toBoolean)
      stream.ready #= false
      stream.payload.toBigInt()
    }

    def <<#(that: Stream[T]): Unit = {
      stream.payload #= that.payload.toBigInt()
      stream.valid #= that.valid.toBoolean
      that.ready #= stream.ready.toBoolean
    }

    def #>>(that: Stream[T]) = {
      that <<# stream
    }
  }

  def hostModel(cd: ClockDomain,
                hostIO: HostDataIO,
                hostSendQ: mutable.Queue[BigInt],
                hostRecvQ: mutable.Queue[BigInt],
                expRespCount: Int,
                printEn: Boolean = false
             ): Unit = {

    val dWidth = hostIO.axis_host_sink.payload.tdata.getBitsWidth

    // read path
    fork {
      assert(hostSendQ.nonEmpty, "hostSendQ is empty!")
      while(hostSendQ.nonEmpty){
        val rdReqD = hostIO.bpss_rd_req.recvData(cd)
        val reqByte = bigIntTruncVal(rdReqD, 47, 20).toInt
        for (i <- 0 until reqByte/(dWidth/8)) {
          val tkeep = ((BigInt(1) << dWidth/8) - 1) << dWidth
          val tlast = if (i == reqByte/(dWidth/8)-1) BigInt(1) << (dWidth+dWidth/8) else 0.toBigInt
          val tdata = hostSendQ.dequeue()
          hostIO.axis_host_sink.sendData(cd, tdata + tkeep + tlast)
          if (printEn) {
            println(tdata)
          }
        }
        hostIO.bpss_rd_done.sendData(cd, 0.toBigInt) // pid
      }
    }

    // write path
    fork {
      while(hostRecvQ.length < expRespCount){
        val wrReqD = hostIO.bpss_wr_req.recvData(cd)
        val reqByte = bigIntTruncVal(wrReqD, 47, 20).toInt
        for (i <- 0 until reqByte/(dWidth/8)) {
          val d = hostIO.axis_host_src.recvData(cd)
          if (i == reqByte/(dWidth/8)-1) assert((d >> (dWidth+dWidth/8)) > 0) // confirm tlast
          hostRecvQ.enqueue(d & ((BigInt(1) << 512)-1))
        }
        hostIO.bpss_wr_done.sendData(cd, 0.toBigInt) // pid
      }
    }
  }

}




