package app.medicinecabinet.domain

import kotlinx.serialization.Serializable

@Serializable
enum class InterfaceSize(val scale: Float, val label: String) {
    COMPACT(0.9f, "紧凑"),
    STANDARD(1f, "标准"),
    COMFORTABLE(1.1f, "宽松"),
}
