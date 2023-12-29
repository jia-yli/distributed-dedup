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
import org.scalatest.tagobjects.Network

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

  def initHostDataIO(bus: HostDataIO): Unit = {
    bus.axis_host_sink.valid #= false
    bus.bpss_wr_done.valid   #= false
    bus.bpss_rd_done.valid   #= false
    bus.axis_host_src.ready  #= false
    bus.bpss_wr_req.ready    #= false
    bus.bpss_rd_req.ready    #= false
  }

  def initNetworkIO(bus: NetworkDataIO): Unit = {
    bus.rdma_0_sq       .ready #= false
    bus.rdma_0_ack      .valid #= false
    bus.rdma_0_rd_req   .valid #= false
    bus.rdma_0_wr_req   .valid #= false
    bus.axis_rdma_0_sink.valid #= false
    bus.axis_rdma_0_src .ready #= false
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

  def networkModel2Ends(cd: ClockDomain,
                        networkIoLst: Seq[NetworkDataIO],
                        networkAddrLst: Seq[BigInt],
                        printEn: Boolean = false
                        ): Unit = {

    assert(networkIoLst.length == 2)
    assert(networkAddrLst.length == 2)

    // 0 -> 1
    fork {
      while(true){
        val port0Sq = networkIoLst(0).rdma_0_sq.recvData(cd)
        val bitOffset = SimHelpers.BitOffset()
        bitOffset.next(256-192-4-24-4-10-5)
        bitOffset.next(192)
        val msg = SimHelpers.bigIntTruncVal(port0Sq, bitOffset.high, bitOffset.low)
        val len = SimHelpers.bigIntTruncVal(msg, 159, 128)
        bitOffset.next(4+24+4)
        bitOffset.next(10)
        val qpn = SimHelpers.bigIntTruncVal(port0Sq, bitOffset.high, bitOffset.low)
        assert(qpn == networkAddrLst(1))
        // println(s"board 0 send to qpn: $qpn, len: $len")
        bitOffset.next(5)
        val opCode = SimHelpers.bigIntTruncVal(port0Sq, bitOffset.high, bitOffset.low)
        cd.waitSampling(256)
        for (i <- 0 until len.toInt/(512/8)) {
          val port0Data = networkIoLst(0).axis_rdma_0_src.recvData(cd)
          networkIoLst(1).axis_rdma_0_sink.sendData(cd, port0Data)
          val last = SimHelpers.bigIntTruncVal(port0Data, 512 + 512/8 + 6, 512 + 512/8 + 6)
          val wordCount = len.toInt/(512/8)
          // println(s"board 0 send to qpn: $qpn, fragment: $i/$wordCount, last: $last")
          if (i < (wordCount - 1)){
            assert(last == 0)
          } else {
            assert(last == 1)
          }
        }
      }
    }

    // 1 -> 0
    fork {
      while(true){
        val port1Sq = networkIoLst(1).rdma_0_sq.recvData(cd)
        val bitOffset = SimHelpers.BitOffset()
        bitOffset.next(256-192-4-24-4-10-5)
        bitOffset.next(192)
        val msg = SimHelpers.bigIntTruncVal(port1Sq, bitOffset.high, bitOffset.low)
        val len = SimHelpers.bigIntTruncVal(msg, 159, 128)
        bitOffset.next(4+24+4)
        bitOffset.next(10)
        val qpn = SimHelpers.bigIntTruncVal(port1Sq, bitOffset.high, bitOffset.low)
        assert(qpn == networkAddrLst(0))
        // println(s"board 1 send to qpn: $qpn, len: $len")
        bitOffset.next(5)
        val opCode = SimHelpers.bigIntTruncVal(port1Sq, bitOffset.high, bitOffset.low)
        cd.waitSampling(128)
        for (i <- 0 until len.toInt/(512/8)) {
          val port1Data = networkIoLst(1).axis_rdma_0_src.recvData(cd)
          networkIoLst(0).axis_rdma_0_sink.sendData(cd, port1Data)
          val last = SimHelpers.bigIntTruncVal(port1Data, 512 + 512/8 + 6, 512 + 512/8 + 6)
          val wordCount = len.toInt/(512/8)
          // println(s"board 1 send to qpn: $qpn, fragment: $i/$wordCount, last: $last")
          if (i < (wordCount - 1)){
            assert(last == 0)
          } else {
            assert(last == 1)
          }
        }
      }
    }
  }

}




