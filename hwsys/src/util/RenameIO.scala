package util

import spinal.core._

/*
 * If you want to rename the IO signals your module,
 * you should extends your class with this trait.
 * At the end of your class, call `addPrePopTask(renameIO)`.
 */

trait RenameIO {

  /*
   * Only subclasses of Component can extend this RenameIO trait
   * @this is the calling class, therefore able to use SpinalHDL methods
   * i.e., getAllIo, getName.
   */
  this : Component =>

  def renameIO(): Unit = {

    this.noIoPrefix()
    for (port <- this.getAllIo) {
      val newName = port.getName()
        .replaceAll("(_(a)?[wrb])_(payload_)?", "$1")
        .replaceAll("_payload_", "_")
        //        .replaceAll("_payload$", "_tdata")

        .replaceAll("sink_ready$", "sink_tready")
        .replaceAll("sink_valid$", "sink_tvalid")
        .replaceAll("src_ready$", "src_tready")
        .replaceAll("src_valid$", "src_tvalid")


      // For Axi Stream, Fragment Interface
      //        .replaceAll("_last$", "_tlast")
      //        .replaceAll("_payload_t", "_t")
      //        .replaceAll("_payload_fragment_t", "_t")
      port.setName(newName)
    }
  }
  //println(f"Renamed the IO signal of ${this.getClass().getSimpleName}")
}