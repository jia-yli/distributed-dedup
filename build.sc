import mill._, scalalib._, scalafmt._

val spinalVersion = "1.7.3"
val scalaTestVersion = "3.2.11"

trait CommonSpinalModule extends ScalaModule with ScalafmtModule {
  def scalaVersion = "2.12.16"
  override def scalacOptions = Seq("-unchecked", "-deprecation", "-feature")

  override def ivyDeps = Agg(
    ivy"com.github.spinalhdl::spinalhdl-core:$spinalVersion",
    ivy"com.github.spinalhdl::spinalhdl-lib:$spinalVersion",
    ivy"com.github.spinalhdl::spinalhdl-sim:$spinalVersion",
    ivy"com.lihaoyi::os-lib:0.8.0",
    ivy"org.scala-stm::scala-stm:0.11.0",
    ivy"com.github.spinalhdl::spinalhdl-crypto:1.1.1"
    // TODO: use pureconfig for better config
    // ivy"com.github.pureconfig::pureconfig:0.17.4" 
    )

  override def scalacPluginIvyDeps = Agg(ivy"com.github.spinalhdl::spinalhdl-idsl-plugin:$spinalVersion")
}

object hwsys extends CommonSpinalModule {
  object test extends Tests with TestModule.ScalaTest {
    override def ivyDeps = Agg(ivy"org.scalatest::scalatest:$scalaTestVersion")
    override def testFramework = "org.scalatest.tools.Framework"
    // in openjdk 17.0.6, if multiple -Xmx arg are used, the last one will take the effect, check with
    // java -version; java -Xmx1G -XX:+PrintFlagsFinal -Xmx2G 2>/dev/null | grep MaxHeapSize
    // override def forkArgs = T { super.forkArgs() ++ Seq("-Xmx16g") }
    def testSim(args: String*) = T.command {
      super.runMain("org.scalatest.run", args: _*)
    }
  }
}
