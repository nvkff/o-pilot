package io.github.nvkff.opilot.shared

data class ControlPoint(
    val id: Int,
    val label: String,
    val lat: Double,
    val lon: Double,
    val taken: Boolean = false
)

object DataLayerContract {
    const val PATH_ROUTE = "/opilot/route"
    const val KEY_ROUTE_JSON = "route_json"
}
