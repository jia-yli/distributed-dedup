package util.sim

import spinal.core._
import spinal.core.sim._

/** SimHelpers */
object SimHelpers {

  def bigIntTruncVal(value: BigInt, hi: Int, lo: Int): BigInt = {
    assert(hi >= lo)
    (value >> lo) & (BigInt(1)<<(hi-lo+1))-1
  }

  @deprecated
  def genFromBigInt[T <: Bundle](gen: T, value: BigInt): T = {
    val bd = gen
    bd.assignFromBigInt(value)
    bd
  }

  implicit class BundleUtils(bd: Bundle) {

    /** AutoConnect the bundle with an other bundle by name */
    def connectAllByName(that: Bundle): Unit = {
      for ((name, element) <- bd.elements) {
        val other = that.find(name)
        if (other == null)
          LocatedPendingError(s"Bundle assignment is not complete. Missing $name")
        else
          element <> other // NOTE: no recursive is required -> bundle has autoConnect
      }
    }

    /** AutoConnect all possible signal fo the bundle with an other bundle by name */
    def connectSomeByName(that: Bundle): Unit = {
      for ((name, element) <- bd.elements) {
        val other = that.find(name)
        if (other != null)
          element <> other
      }
    }

  }


  implicit class SimBundlePimper(bd: Bundle) {

    def assignFromBigInt(value: BigInt): Unit = {
      var offset = 0
      for ((_, e) <- bd.elements) {
        val truncVal = bigIntTruncVal(value, offset + e.getBitsWidth - 1, offset)
        e match {
          case e: Bundle => e.assignFromBigInt(truncVal)
          case e: BaseType => {
            setBigInt(e, truncVal)
          }
        }
        offset += e.getBitsWidth
      }
    }

    def toBigInt(startOffs: Int = 0, startVal: BigInt = 0): BigInt = {
      var offset = startOffs
      var value = startVal

      for ((_, e) <- bd.elements) {
        e match {
          case e: Bundle => value += e.toBigInt(offset, value)
          case e: BaseType => value += (e.toBigInt << offset)
        }
        offset += e.getBitsWidth
      }
      value
    }

    def #=(value: BigInt) = bd.assignFromBigInt(value)
    def #=(value: Long) = bd.assignFromBigInt(BigInt(value))
    def #=(value: Int) = bd.assignFromBigInt(BigInt(value))

  }

}