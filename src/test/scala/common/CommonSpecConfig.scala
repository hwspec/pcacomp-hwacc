package common

import chiseltest.ChiselScalatestTester
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.{BeforeAndAfterAllConfigMap, ConfigMap, Tag}

// Use scalatest's option flags to include or exclude tags.
//
// To include only RequiresTreadle
// sbt "testOnly snn.LIFSpec -- -n RequiresTreadle"
//
// To exclude RequiresTreadle, which selects RequiresVerilator
// sbt "testOnly snn.LIFSpec -- -l RequiresTreadle"
//
object RequiresVerilator extends Tag("RequiresVerilator")
object RequiresTreadle extends Tag("RequiresTreadle")

/**
 * To enable the formal test,
 * sbt "testOnly -- -DFORMAL=1"
 * Note: Formal testing is disabled by default. You need to add a dummy value (e.g., =1)
 * as Scalatest's configmap requires some value.
 */
class CommonSpecConfig extends AnyFlatSpec with BeforeAndAfterAllConfigMap with ChiselScalatestTester {
  private var _formalEnabled = false
  override def beforeAll(configMap: ConfigMap) = {
    _formalEnabled = configMap.contains("FORMAL")
  }
  def formalEnabled = _formalEnabled
}
