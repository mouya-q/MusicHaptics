package com.mouya.musichaptics

data class MusicPersona(
    val name: String,
    val displayName: String,
    val description: String,

    val bassGain: Float = 1.0f,

    val vocalGain: Float = 1.0f,

    val textureGain: Float = 1.0f,

    val beatThreshold: Float = 1.2f,

    val transientThreshold: Float = 1.3f,

    val impactBias: Float = 1.0f,

    val pulseBias: Float = 1.0f,

    val textureBias: Float = 1.0f,

    val waveBias: Float = 1.0f,

    val gamma: Float = 0.5f,

    val impactDurationMin: Int = 10,
    val impactDurationMax: Int = 30,
    val pulsePeriodMin: Int = 30,
    val pulsePeriodMax: Int = 80,
    val textureDurationMin: Int = 100,
    val textureDurationMax: Int = 500,
    val waveDurationMin: Int = 50,
    val waveDurationMax: Int = 800
) {
    companion object {

        val EDM = MusicPersona(
            name = "EDM",
            displayName = "EDM",
            description = "电子舞曲：Kick / Drop / Bass，强 Impact + 快速 Pulse",
            bassGain = 1.4f,
            vocalGain = 0.7f,
            textureGain = 0.6f,
            beatThreshold = 1.15f,
            transientThreshold = 1.2f,
            impactBias = 1.3f,
            pulseBias = 1.2f,
            textureBias = 0.6f,
            waveBias = 0.5f,
            gamma = 0.45f,
            impactDurationMin = 12,
            impactDurationMax = 35,
            pulsePeriodMin = 25,
            pulsePeriodMax = 70
        )

        val POP = MusicPersona(
            name = "POP",
            displayName = "Pop",
            description = "流行：鼓点 + 人声平衡，Texture 中等",
            bassGain = 1.0f,
            vocalGain = 1.2f,
            textureGain = 1.0f,
            beatThreshold = 1.25f,
            transientThreshold = 1.35f,
            impactBias = 1.0f,
            pulseBias = 1.0f,
            textureBias = 1.0f,
            waveBias = 0.8f,
            gamma = 0.55f,
            impactDurationMin = 10,
            impactDurationMax = 28,
            pulsePeriodMin = 35,
            pulsePeriodMax = 80,
            textureDurationMin = 80,
            textureDurationMax = 400
        )

        val VOCAL = MusicPersona(
            name = "VOCAL",
            displayName = "Vocal",
            description = "人声：情绪为主，弱 Impact + 丰富 Texture",
            bassGain = 0.7f,
            vocalGain = 1.5f,
            textureGain = 1.4f,
            beatThreshold = 1.4f,
            transientThreshold = 1.5f,
            impactBias = 0.8f,
            pulseBias = 0.7f,
            textureBias = 1.3f,
            waveBias = 1.1f,
            gamma = 0.65f,
            impactDurationMin = 8,
            impactDurationMax = 22,
            textureDurationMin = 150,
            textureDurationMax = 500
        )

        val CLASSICAL = MusicPersona(
            name = "CLASSICAL",
            displayName = "Classical",
            description = "古典：钢琴 / 弦乐，Wave 为主 + 微弱 Impact",
            bassGain = 0.6f,
            vocalGain = 1.0f,
            textureGain = 0.8f,
            beatThreshold = 1.5f,
            transientThreshold = 1.6f,
            impactBias = 0.6f,
            pulseBias = 0.5f,
            textureBias = 0.9f,
            waveBias = 1.3f,
            gamma = 0.7f,
            impactDurationMin = 8,
            impactDurationMax = 20,
            waveDurationMin = 100,
            waveDurationMax = 800
        )

        val GAME_OST = MusicPersona(
            name = "GAME_OST",
            displayName = "Game / OST",
            description = "游戏原声：强节奏，Impact + Pulse 双重偏置",
            bassGain = 1.2f,
            vocalGain = 0.8f,
            textureGain = 0.8f,
            beatThreshold = 1.2f,
            transientThreshold = 1.25f,
            impactBias = 1.5f,
            pulseBias = 1.3f,
            textureBias = 0.7f,
            waveBias = 0.8f,
            gamma = 0.5f,
            impactDurationMin = 10,
            impactDurationMax = 35,
            pulsePeriodMin = 25,
            pulsePeriodMax = 70
        )

        val NETEASE = MusicPersona(
            name = "NETEASE",
            displayName = "网易云·嗨动",
            description = "网易云嗨动模式：干脆击打、低频厚重不拖沓、人声有质感、整体氛围感强",
            bassGain = 1.35f,  // 低频增强：厚重有体积，但不过度
            vocalGain = 1.15f,  // 人声稍增：歌词情绪传递
            textureGain = 1.1f,  // 纹理适中：吉他/钢琴泛音可感知
            beatThreshold = 1.1f,  // 更敏感的节拍检测：不漏击
            transientThreshold = 1.15f,  // 瞬态更敏感：鼓点清晰
            impactBias = 1.4f,  // 强击打偏置：每一下都有力
            pulseBias = 1.1f,  // 脉冲跟随：持续低频律动
            textureBias = 1.0f,  // 纹理平衡
            waveBias = 0.85f,  // 波浪略减：避免过度平滑
            gamma = 0.42f,  // 更低 gamma：动态范围大，强弱对比明显
            impactDurationMin = 8,
            impactDurationMax = 24,  // 更短击打：干脆利落，不拖泥带水
            pulsePeriodMin = 20,
            pulsePeriodMax = 65,  // 更快脉冲：律动感强
            textureDurationMin = 60,
            textureDurationMax = 350,  // 纹理不拖沓
            waveDurationMin = 40,
            waveDurationMax = 500  // 波浪适中
        )

        val ALL: List<MusicPersona> = listOf(
            EDM, POP, VOCAL, CLASSICAL, GAME_OST, NETEASE
        )

        val DEFAULT: MusicPersona = POP

        fun byName(name: String): MusicPersona? = ALL.find { it.name == name }
    }
}

interface PersonaEngine {

    fun detect(stats: AudioFeatureStats): MusicPersona
}

data class AudioFeatureStats(
    val avgBassEnergy: Float,
    val avgMidEnergy: Float,
    val avgTrebleEnergy: Float,
    val beatDensity: Float,
    val spectralCentroid: Float,
    val dynamicRange: Float
)