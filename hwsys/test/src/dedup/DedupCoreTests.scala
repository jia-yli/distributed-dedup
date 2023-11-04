// package dedup

// import org.scalatest.funsuite.AnyFunSuite
// import spinal.core.sim._
// import util.sim._
// import util.sim.SimDriver._

// import scala.collection.mutable
// import scala.collection.mutable._
// import scala.util.Random

// case class PageResp() {
//   var pgIdx: Int = 0
//   var pgPtr: BigInt = 0
//   var isExit: Boolean = false
// }

// class CoreTests extends AnyFunSuite {
//   def dedupCoreSim(): Unit = {

//     val compiledRTL = if (sys.env.contains("VCS_HOME")) SimConfig.withVpdWave.withVCS.compile(new WrapDedupCore())
//     else SimConfig.withWave.compile(new WrapDedupCore())

//     compiledRTL.doSim { dut =>
//       DedupCoreSim.doSim(dut)
//     }
//   }

//   test("DedupCoreTest"){
//     dedupCoreSim()
//   }
// }


// class SysTests extends AnyFunSuite {
//   def dedupSysSim(): Unit = {

//     val compiledRTL = if (sys.env.contains("VCS_HOME")) SimConfig.withVpdWave.withVCS.compile(new WrapDedupSys())
//     else SimConfig.withWave.compile(new WrapDedupSys())

//     compiledRTL.doSim { dut =>
//       DedupSysSim.doSim(dut)
//     }
//   }

//   test("DedupSysTest"){
//     dedupSysSim()
//   }
// }

// object DedupCoreSim {

//   def doSim(dut: WrapDedupCore, verbose: Boolean = false): Unit = {
//     dut.clockDomain.forkStimulus(period = 2)
//     SimTimeout(1000000)
//     dut.io.pgStrmIn.valid #= false
//     dut.io.pgResp.ready #= false
//     /** memory model for HashTab */
//     SimDriver.instAxiMemSim(dut.io.axiMem, dut.clockDomain, None)

//     dut.clockDomain.waitSampling(10)

//     /** init */
//     dut.io.initEn #= true
//     dut.clockDomain.waitSampling()
//     dut.io.initEn #= false
//     dut.clockDomain.waitSamplingWhere(dut.io.initDone.toBoolean)

//     dut.io.factorThrou #= 16

//     /** generate page stream */
//     val pageNum = 64
//     val dupFacotr = 2
//     assert(pageNum%dupFacotr==0, "pageNumber must be a multiple of dupFactor")
//     val uniquePageNum = pageNum/dupFacotr
//     val pageSize = 4096
//     val bytePerWord = 64

//     val uniquePgData = List.fill[BigInt](uniquePageNum*pageSize/bytePerWord)(BigInt(bytePerWord*8, Random))
//     var pgStrmData: ListBuffer[BigInt] = ListBuffer()
// //    for (i <- 0 until uniquePageNum) {
// //      for (j <- 0 until dupFacotr) {
// //        for (k <- 0 until pageSize/bytePerWord) {
// //          pgStrmData.append(uniquePgData(i*pageSize/bytePerWord+k))
// //        }
// //      }
// //    }

//     for (j <- 0 until dupFacotr) {
//       for (i <- 0 until uniquePageNum) {
//         for (k <- 0 until pageSize/bytePerWord) {
//           pgStrmData.append(uniquePgData(i*pageSize/bytePerWord+k))
//         }
//       }
//     }

//     val pgStrmPush = fork {
//       var wordIdx: Int = 0
//       for (_ <- 0 until pageNum) {
//         for (_ <- 0 until pageSize / bytePerWord) {
//           dut.io.pgStrmIn.sendData(dut.clockDomain, pgStrmData(wordIdx))
//           wordIdx += 1
//         }
//       }
//     }

//     val pgWrRespWatch = fork {
//       for (_ <- 0 until pageNum) {
//         val respData = dut.io.pgResp.recvData(dut.clockDomain)
//         val pgIdx = SimHelpers.bigIntTruncVal(respData, 31, 0)
//         val pgPtr = SimHelpers.bigIntTruncVal(respData, 95, 32)
//         val pgIsExist = SimHelpers.bigIntTruncVal(respData, 96, 96)
//         //TODO: now it's simple printing
//         println(s"[PageResp] pgIdx=$pgIdx, pgPtr=$pgPtr, pgIsExist=$pgIsExist")
//       }
//     }

//     pgStrmPush.join()
//     pgWrRespWatch.join()

//   }
// }


// object DedupSysSim {

//   def doSim(dut: WrapDedupSys, verbose: Boolean = false): Unit = {
//     dut.clockDomain.forkStimulus(period = 2)
//     SimTimeout(1000000)
//     /** memory model for HashTab */
//     SimDriver.instAxiMemSim(dut.io.axi_mem_0, dut.clockDomain, None)
//     dut.clockDomain.waitSampling(10)

//     /** init */
//     initAxi4LiteBus(dut.io.axi_ctrl)
//     dut.io.hostd.axis_host_sink.valid #= false
//     dut.io.hostd.bpss_wr_done.valid #= false
//     dut.io.hostd.bpss_rd_done.valid #= false
//     dut.io.hostd.axis_host_src.ready #= false
//     dut.io.hostd.bpss_wr_req.ready #= false
//     dut.io.hostd.bpss_rd_req.ready #= false

//     /** generate pages and push to pre-load queue that serves the axis_host_sink */
//     val pageNum = 256
//     val dupFacotr = 8
//     assert(pageNum % dupFacotr == 0, "pageNumber must be a multiple of dupFactor")
//     val uniquePageNum = pageNum / dupFacotr
//     val pageSize = 4096
//     val bytePerWord = 64

//     val uniquePgData = List.fill[BigInt](uniquePageNum * pageSize / bytePerWord)(BigInt(bytePerWord * 8, Random))
//     val pgStrmData: mutable.Queue[BigInt] = mutable.Queue()
//     for (i <- 0 until uniquePageNum) {
//       for (j <- 0 until dupFacotr) {
//         for (k <- 0 until pageSize / bytePerWord) {
//           pgStrmData.enqueue(uniquePgData(i * pageSize / bytePerWord + k))
//         }
//       }
//     }

//     val pgRespQ: mutable.Queue[BigInt] = mutable.Queue()

//     // host model
//     hostModel(dut.clockDomain, dut.io.hostd, pgStrmData, pgRespQ)

//     /** AXIL Control Reg */
//     setAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 0, 1) // INITEN

//     setAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 3<<3, 0) // RDHOSTADDR
//     setAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 4<<3, 0x1000) // WRHOSTADDR
//     setAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 5<<3, 4096) // LEN
//     setAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 6<<3, pageNum) // CNT
//     setAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 7<<3, 0) // PID
//     setAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 10<<3, 8) // factorThrou

//     // confirm initDone
//     while(readAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 1<<3) == 0) {
//       dut.clockDomain.waitSampling(10)
//     }
//     // set start
//     setAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 2<<3, 1)

//     while(pgStrmData.nonEmpty) {
//       dut.clockDomain.waitSampling(10)
//     }
//     // wait for tail pages processing
//     while (readAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 9 << 3) < pageNum/16) {
//       dut.clockDomain.waitSampling(100)
//     }

//     val rdDone = readAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 8<<3)
//     val wrDone = readAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 9<<3)
//     println(s"rdDone: $rdDone, wrDone: $wrDone")

//     // parse the pgRespQ
//     val pgRespData: mutable.Queue[PageResp] = mutable.Queue()
//     while(pgRespQ.nonEmpty) {
//       val dWide = pgRespQ.dequeue() // 512b contains 4 pgResp (128 bit for each))
//       for (i <- 0 until 4) {
//         val d = (dWide >> (i*128)) & ((BigInt(1)<<128) -1)
//         val pgResp = PageResp( )
//         pgResp.isExit = if ((d&1)>0) true else false
//         pgResp.pgIdx = ((d >> 1) & ((BigInt(1)<<32)-1)).toInt
//         pgResp.pgPtr = d>>64
//         pgRespData.enqueue(pgResp)
//         println(s"pgIdx: ${pgResp.pgIdx}, pgIsExit: ${pgResp.isExit}, pgPtr: ${pgResp.pgPtr}")
//       }
//     }
//   }
// }