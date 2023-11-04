package dedup
import spinal.core._
import spinal.crypto.hash.sha3._
import spinal.lib.bus.amba4.axi._

// default generator config
object MySpinalConfig
    extends SpinalConfig(
      targetDirectory = "generated_rtl/",
      defaultConfigForClockDomains = ClockDomainConfig(
        resetKind = SYNC,
        resetActiveLevel = LOW
      )
    )

object MySpinalReport extends SpinalReport

object GenSHA3Core_Std {
  // import config if exists
  def main(args: Array[String]): Unit =
    MySpinalConfig.generateVerilog {
      val top = new SHA3Core_Std(SHA3_256)
      top.setDefinitionName("dedup_default")
      top
    }
}

// object GenBloomFilterCRC {
//   // import config if exists
//   def main(args: Array[String]): Unit =
//     MySpinalConfig
//       .generateVerilog({
//         val top = new BloomFilterCRC()
//         top.setDefinitionName("dedup_bloomfiltercrc")
//         top
//       })
//       .printPruned()
// }

object GenDefault {
  def main(args: Array[String]): Unit =
    MySpinalConfig
      .generateVerilog({
        val top = new WrapDedupCore()
        top.setDefinitionName("dedup_core")
        top
      })
//      .printPruned()
}

object GenDedupSys {
  def main(args: Array[String]): Unit =
    MySpinalConfig
      .generateVerilog({
        val top = new WrapDedupSys()
        top.renameIO()
        top.setDefinitionName("dedup_4k")
        top
      })
  //      .printPruned()
}


object Axi4ConfigAlveo {
  val u55cHBM = Axi4Config(
    addressWidth = 64,
    dataWidth = 512,
    idWidth = 6,
    useStrb = true,
    useBurst = true,
    useId = true,
    useLock = false,
    useRegion = false,
    useCache = false,
    useProt = false,
    useQos = false,
    useLen = true
  )
}